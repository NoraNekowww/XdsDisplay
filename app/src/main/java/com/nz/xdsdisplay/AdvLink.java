package com.nz.xdsdisplay;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 广播直连：只扫描、不连接。
 *
 * 为什么不用标准 GATT 了：中转那台小米 HyperOS 的 GATT Server 对客户端的 CCCD 写请求
 * 全程不回调也不响应（空口抓包确认过：写请求发出去了，服务端一个字节都不回），
 * 标准 0x1818 订阅在那台机器上根本走不通。
 *
 * 改成中转机把功率/定位数据直接塞进广播包的厂商数据里，显示端只靠扫描拿数据：
 * 没有连接、没有握手、没有订阅、没有断连 —— 稳定性和刷新率都取决于广播本身。
 *
 * 负载格式与中转端 CpsServer.buildTelemetry 一一对应（19 字节，小端）：
 *   [0] 标志 bit0 有功率 bit1 有定位 bit2 有踏频
 *   [1..2] 功率W | [3] 踏频 | [4] 电量% | [5..6] 速度*100 m/s (FFFF=无效)
 *   [7] 航向/2 度 (FF=无效) | [8..10] 纬度*1e5 | [11..13] 经度*1e5
 *   [14..15] MSL海拔(米) | [16] 定位质量 | [17] 卫星数 | [18] 左腿平衡*2 (FF=无效)
 */
public class AdvLink {

    private static final String TAG = "ADV";

    /** 必须与中转端 CpsServer.XDS_COMPANY_ID 一致 */
    public static final int XDS_COMPANY_ID = 0x0A0C;

    private static final long PACKET_TIMEOUT_MS = 6000L;
    /** 定期重建扫描会话：长时间不换会话，有些 ROM 的扫描会"变聋" */
    private static final long SCAN_RESTART_MS = 60000L;
    /** 中转端约 4Hz 在播，静默超过这么久就说明扫描卡了 */
    private static final long SILENT_RESTART_MS = 1500L;
    /** 重开扫描本身有几百毫秒盲区，两次重开至少隔这么久，别自己把自己抖死 */
    private static final long SILENT_RESTART_COOLDOWN_MS = 4000L;

    // 注：ctx 不声明成 final —— scanWatchdog 是**字段初始化器**里的匿名类，
    // 它引用空 final 字段会被 javac 判成"might not have been initialized"
    private Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BleCentral.Listener listener;

    private boolean running;
    private BleCentral.State state = BleCentral.State.IDLE;
    private long lastPacketMs;
    private long scanStartedMs;
    private long packets;
    private long lastLogMs;
    private long firstPacketMs;
    private String sourceAddr = "";
    private String sourceName = "";

    // 踏频合成（沿用界面既有的曲柄推算逻辑）
    private int crankRevs;
    private int crankEvent;
    private long lastCpsMs;
    private double crankAccum;
    private double crankTicks;

    public AdvLink(Context c) {
        // 不要取 getApplicationContext()：AppCompat 的 per-app 语言只包 Activity 的 Context，
        // 用 Application 的拿到的永远是系统语言 —— 切英文时状态栏就会一个人说中文
        this.ctx = c;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
    }

    public void setListener(BleCentral.Listener l) {
        this.listener = l;
    }

    public BleCentral.State getState() {
        return state;
    }

    /** 收到的功率包数（界面底部统计用） */
    public long cpsCount() {
        return cpsPackets;
    }

    /** 收到的定位包数 */
    public long lnCount() {
        return lnPackets;
    }

    private long cpsPackets, lnPackets;
    /** 连续重开扫描的次数，收不到就升级为重建扫描子系统 */
    private int advWatchRestarts;
    private long lastSilentRestartMs;

    // ---- 数据通道仲裁：WiFi 直连 / BLE 直连(连接) / BLE 广播 ----
    public static final int CH_AUTO = 0, CH_WIFI = 1, CH_BLE_CONN = 2, CH_BLE_ADV = 3;

    public static final String CH_WIFI_NAME = "WiFi";
    public static final String CH_CONN_NAME = "BLE直连";
    public static final String CH_ADV_NAME = "BLE广播";

    /** 通道多久没数据就算"不新鲜" */
    private static final long FRESH_MS = 1500L;

    private volatile long lastWifiMs;
    private volatile long lastConnMs;

    /** 中继的记录状态（随遥测 flags bit4/bit5 过来），仪表端按钮与本地计时都用它 */
    public volatile boolean relayRecording;
    public volatile boolean relayPaused;
    public volatile long relayStateMs;

    /** 记录状态是否新鲜（3 秒内收到过遥测） */
    public boolean relayStateFresh() {
        return relayStateMs > 0 && System.currentTimeMillis() - relayStateMs < 3000L;
    }
    private volatile int channelPref = CH_AUTO;
    private volatile String activeChannel = CH_ADV_NAME;
    private long wifiPackets, connPackets, wifiLastLogMs, bleBackupPackets;

    public long wifiPackets() {
        return wifiPackets;
    }

    public long connPackets() {
        return connPackets;
    }

    public String activeChannel() {
        return activeChannel;
    }

    public int channelPref() {
        return channelPref;
    }

    public void setChannelPref(int p) {
        this.channelPref = p;
        Logs.add(TAG, "通道偏好 → " + channelPrefName(p));
    }

    public static String channelPrefName(int p) {
        switch (p) {
            case CH_WIFI:
                return "WiFi only";
            case CH_BLE_CONN:
                return "BLE link only";
            case CH_BLE_ADV:
                return "BLE adv only";
            default:
                return "Auto (WiFi > BLE)";
        }
    }

    /** 自动模式下按优先级挑当前该采信的那一路 */
    private String pickChannel(long now) {
        if (now - lastWifiMs < FRESH_MS) return CH_WIFI_NAME;
        if (now - lastConnMs < FRESH_MS) return CH_CONN_NAME;
        return CH_ADV_NAME;
    }

    /** 这个通道的数据现在该不该拿去解码 */
    private boolean shouldDecode(String channel, long now) {
        switch (channelPref) {
            case CH_WIFI:
                return CH_WIFI_NAME.equals(channel);
            case CH_BLE_CONN:
                return CH_CONN_NAME.equals(channel);
            case CH_BLE_ADV:
                return CH_ADV_NAME.equals(channel);
            default:
                return pickChannel(now).equals(channel);
        }
    }

    /** 换通道会让合成曲柄序列跳变，必须清掉，否则踏频会算出一个鬼值 */
    private void resetCrank() {
        crankAccum = 0;
        crankTicks = 0;
        lastCpsMs = 0;
    }

    private void switchChannelIfNeeded(String channel, long now) {
        if (!channel.equals(activeChannel)) {
            activeChannel = channel;
            resetCrank();
            Logs.add(TAG, "数据通道 → " + channel);
        }
    }

    public boolean wifiFresh(long now) {
        return lastWifiMs > 0 && now - lastWifiMs < FRESH_MS;
    }

    public boolean connFresh(long now) {
        return lastConnMs > 0 && now - lastConnMs < FRESH_MS;
    }

    /**
     * 最近一次收到中继遥测（任意通道都算）的时刻，0 = 从没收到过。
     * 仪表端用它判断"中继是不是断流了" —— 只要还有任一通道在来数据，就不算断。
     */
    public long lastAnyMs() {
        long m = lastPacketMs > lastWifiMs ? lastPacketMs : lastWifiMs;
        return m > lastConnMs ? m : lastConnMs;
    }

    /** 各通道最近收包时刻（心跳日志用） */
    public long advLastMs() {
        return lastPacketMs;
    }

    public long wifiLastMs() {
        return lastWifiMs;
    }

    public long connLastMs() {
        return lastConnMs;
    }

    /**
     * WiFi 通道 / BLE 直连通道收到的遥测（内容与广播里的完全一样，都是 20 字节）。
     * 三路传同一份数据，若都拿去解码踏频会翻倍、时间轴也会乱 —— 所以只让被选中的那一路驱动解码。
     */
    public void feedExternal(byte[] t, long now, String channel) {
        if (t == null || t.length < 20) return;

        if (CH_WIFI_NAME.equals(channel)) {
            lastWifiMs = now;
            wifiPackets++;
            if (wifiPackets <= 3 || now - wifiLastLogMs > 10000) {
                wifiLastLogMs = now;
                Logs.add(TAG, String.format(Locale.CHINA,
                        "WiFi 遥测 #%d  功率=%dW 踏频=%d 电量=%d%%",
                        wifiPackets, u16(t, 1), t[3] & 0xFF, t[4] & 0xFF));
            }
        } else {
            lastConnMs = now;
            connPackets++;
            if (connPackets <= 3 || now - wifiLastLogMs > 10000) {
                wifiLastLogMs = now;
                Logs.add(TAG, String.format(Locale.CHINA,
                        "BLE直连 遥测 #%d  功率=%dW 踏频=%d 电量=%d%%",
                        connPackets, u16(t, 1), t[3] & 0xFF, t[4] & 0xFF));
            }
        }

        if (!shouldDecode(channel, now)) return;
        switchChannelIfNeeded(channel, now);
        decode(t, now);
    }

    public boolean isRunning() {
        return running;
    }

    // ================================================================

    @SuppressLint("MissingPermission")
    public void start() {
        if (running) return;
        if (adapter == null || !adapter.isEnabled()) {
            setState(BleCentral.State.ERROR, ctx.getString(R.string.sta_bt_off));
            return;
        }
        running = true;
        packets = 0;
        lastPacketMs = 0;
        Logs.add(TAG, "启动广播直连：只扫描，不建立连接");
        startScan();
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        running = false;
        stopScan();
        handler.removeCallbacksAndMessages(null);
        setState(BleCentral.State.IDLE, ctx.getString(R.string.sta_stopped));
    }

    @SuppressLint("MissingPermission")
    public void reconnect() {
        stopScan();
        handler.removeCallbacks(scanWatchdog);
        lastPacketMs = 0;
        packets = 0;
        Logs.add(TAG, "重新开始扫描");
        if (running) startScan();
    }

    // ================================================================

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!running) return;
        if (scanner == null) scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setState(BleCentral.State.ERROR, ctx.getString(R.string.sta_scanner_unavailable));
            return;
        }

        // 中转机的广播里同时带 0x1818 和厂商数据，所以按 0x1818 过滤就行，
        // 既能拿到我们的数据，也不会被一堆无关设备刷屏。
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(BleUuids.CPS_SERVICE))
                .build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        try {
            scanner.startScan(filters, settings, scanCallback);
            scanStartedMs = System.currentTimeMillis();
            handler.removeCallbacks(scanWatchdog);
            handler.postDelayed(scanWatchdog, 2000L);
            if (packets == 0) setState(BleCentral.State.SCANNING, ctx.getString(R.string.sta_scanning));
        } catch (Exception e) {
            Logs.add(TAG, "开始扫描失败: " + e);
            setState(BleCentral.State.ERROR, ctx.getString(R.string.sta_scan_failed));
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        try {
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (Exception ignore) {}
    }

    /** 扫描本身有时会自己停掉；长时间收不到数据时重启扫描 */
    private final Runnable scanWatchdog = () -> {
        if (!running) return;
        long now = System.currentTimeMillis();
        boolean silent = lastPacketMs == 0 || now - lastPacketMs > PACKET_TIMEOUT_MS;
        boolean verySilent = lastPacketMs > 0 && now - lastPacketMs > SILENT_RESTART_MS;
        boolean tooOld = now - scanStartedMs > SCAN_RESTART_MS;

        if (advWatchRestarts >= 2) {
            // 连着几次重开还是收不到 —— 把扫描子系统整个重建（协议栈有时会卡死）
            advWatchRestarts = 0;
            Logs.add(TAG, "连续重开扫描仍无数据，重建扫描子系统");
            stopScan();
            scanner = null;
            handler.postDelayed(() -> {
                if (running) startScan();
            }, 800L);
            rescheduleWatchdog(3000L);
            return;
        }
        if (verySilent && !tooOld && !scanGraceActive(now)
                && now - lastSilentRestartMs > SILENT_RESTART_COOLDOWN_MS) {
            // 断流了就别干等：重开扫描（这是之前"偶尔远低于 1Hz"的主因）
            advWatchRestarts++;
            lastSilentRestartMs = now;
            Logs.add(TAG, "广播断流 " + ((now - lastPacketMs) / 1000) + "s，立即重开扫描");
            stopScan();
            handler.postDelayed(this::startScan, 100L);
            rescheduleWatchdog(SILENT_RESTART_MS);
            return;
        }
        if (silent && packets > 0) {
            setState(BleCentral.State.RETRY, ctx.getString(R.string.sta_adv_lost));
        }
        if (tooOld) {
            stopScan();
            handler.postDelayed(this::startScan, 300L);
        } else {
            rescheduleWatchdog(1500L);
        }
    };

    private void rescheduleWatchdog(long delay) {
        handler.removeCallbacks(scanWatchdog);
        handler.postDelayed(scanWatchdog, delay);
    }

    /** 刚起扫描的一小段时间不判"断流"，否则一上来就误重开（重开本身也会丢包） */
    private boolean scanGraceActive(long now) {
        return now - scanStartedMs < 4000L;
    }

    private final ScanCallback scanCallback = new ScanCallback() {

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult r : results) handleResult(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Logs.add(TAG, "扫描失败 code=" + errorCode);
            setState(BleCentral.State.ERROR, ctx.getString(R.string.sta_scan_failed_fmt, errorCode));
        }
    };

    @SuppressLint("MissingPermission")
    private void handleResult(ScanResult result) {
        if (!running || result == null) return;
        ScanRecord rec = result.getScanRecord();
        if (rec == null) return;

        byte[] t;
        try {
            t = rec.getManufacturerSpecificData(XDS_COMPANY_ID);
        } catch (Exception e) {
            return;
        }
        if (t == null || t.length < 20) return;      // 不是我们的中转机

        if (result.getDevice() != null) sourceAddr = result.getDevice().getAddress();
        if (rec.getDeviceName() != null) sourceName = rec.getDeviceName();

        long now = System.currentTimeMillis();
        boolean first = packets == 0;
        packets++;
        lastPacketMs = now;
        advWatchRestarts = 0;      // 有数据就把失败计数清零
        if (first) firstPacketMs = now;

        if (!shouldDecode(CH_ADV_NAME, now)) {
            // 有更快的通道在用（WiFi / BLE直连），广播只统计不解码
            bleBackupPackets++;
            return;
        }
        switchChannelIfNeeded(CH_ADV_NAME, now);

        decode(t, now);

        if (first) {
            Logs.add(TAG, "收到中转机广播 " + (sourceName == null ? "" : sourceName)
                    + "  RSSI=" + result.getRssi());
            setState(BleCentral.State.READY, ctx.getString(R.string.sta_relay_ok));
        }
    }

    // ================================================================
    //  解码
    // ================================================================

    /**
     * ADV 广播包 payload（20 字节，小端；类注释里那份是旧格式，以这里为准）：
     *   [0]flags  [1..2]功率W  [3]踏频  [4]电量%  [5..6]车速*100  [7..10]纬度*1e6
     *   [11..14]经度*1e6  [15..16]海拔m  [17]定位质量  [18]卫星数  [19]左右平衡*2
     */
    private void decode(byte[] t, long now) {
        if (firstPacketMs == 0) firstPacketMs = now;   // 速率统计的起点（不走广播通道时原来一直是 0，速率算出来是 0.0Hz）
        int flags = t[0] & 0xFF;
        int powerW = u16(t, 1);
        int cadence = t[3] & 0xFF;
        int battery = t[4] & 0xFF;
        int rawSpeed = u16(t, 5);
        double lat = i32(t, 7) / 1e6;
        double lon = i32(t, 11) / 1e6;
        int altM = i16(t, 15);
        int fixQuality = t[17] & 0xFF;
        int sats = t[18] & 0xFF;
        int rawBal = t[19] & 0xFF;

        boolean hasPower = (flags & 1) != 0;
        boolean hasGps = (flags & 2) != 0;

        // bit4/bit5：中继的记录状态（仪表端据此回显暂停按钮，按下后也会自己纠正）
        relayRecording = (flags & 0x10) != 0;
        relayPaused = (flags & 0x20) != 0;
        relayStateMs = now;

        // ---- 功率包 ----
        if (hasPower) {
            CpsParser.Data d = new CpsParser.Data();
            d.timestampMs = now;
            d.flags = 0;
            d.powerW = powerW;
            d.hasEnergy = false;

            // 用合成曲柄转数表达踏频，界面的 updateCadence 就能算出真实踏频。
            // 关键：转数和"事件时间"必须成对消耗 —— 之前只把单个包的 dt 加到事件时间上，
            // 而转数是跨了整段才凑够 1 圈，于是算出 500 转/分这种鬼数字。
            if (lastCpsMs > 0 && now > lastCpsMs) {
                double dt = (now - lastCpsMs) / 1000.0;
                if (dt > 0 && dt < 3.0) {
                    if (cadence > 0) {
                        crankAccum += cadence / 60.0 * dt;        // 累积转数
                        crankTicks += dt * 1024.0;                // 累积事件时间(1/1024 s)
                        int whole = (int) crankAccum;
                        if (whole >= 1 && whole <= 4) {
                            crankAccum -= whole;
                            crankRevs = (crankRevs + whole) & 0xFFFF;
                            crankEvent = (crankEvent + (int) Math.min(crankTicks, 32000.0)) & 0xFFFF;
                            crankTicks = 0;
                            d.hasCrank = true;
                        } else if (whole > 4) {
                            crankAccum = 0;
                            crankTicks = 0;
                        }
                    } else {
                        crankAccum = 0;
                        crankTicks = 0;
                    }
                }
            } else {
                d.hasCrank = true;
            }
            lastCpsMs = now;
            d.crankRevs = crankRevs;
            d.crankEvent = crankEvent;
            // 真实踏频直接带上（界面优先用它），曲柄序列只是为了兼容标准格式
            d.cadenceRpm = cadence;
            d.hasCadence = cadence > 0;

            if (rawBal != 0xFF) {
                d.balanceLeftPct = rawBal / 2.0;
                d.hasBalance = true;
            }

            BleCentral.Listener l = listener;
            if (l != null) {
                cpsPackets++;
                l.onCps(d);
                if (battery >= 0 && battery <= 100) l.onBattery(battery);
            }
        }

        // ---- 定位包 ----
        if (hasGps && (lat != 0.0 || lon != 0.0)
                && Math.abs(lat) <= 90.0 && Math.abs(lon) <= 180.0) {
            LnParser.Data d = new LnParser.Data();
            d.timestampMs = now;
            d.lat = lat;
            d.lon = lon;
            d.hasLocation = true;
            d.posStatus = fixQuality > 0 ? 1 : 0;
            d.flags = (1 << 2);
            if (rawSpeed != 0xFFFF) {
                d.speedMps = rawSpeed / 100.0f;
                d.hasSpeed = true;
                d.flags |= 1;
            }
            d.elevationM = altM;
            d.hasElevation = true;
            d.flags |= (1 << 3);
            // GPS 信号状态（顶栏 Link 卡右侧显示星数/RTK）：定位质量 + 参与定位的卫星数
            d.fixQuality = fixQuality;
            d.sats = sats;
            // 航向不单独传了（省字节给 4 字节经纬度），由轨迹自己推算
            // 能走到这里说明坐标已经校验过（非零、在范围内、中继那边也确认是新鲜定位），
            // 直接置为有效 —— 之前跟 fixQuality 走，而中继真实定位走 Android Location、
            // fixQuality 常为 0，结果"速度里程都有、地图却没有 GPS"。
            d.posStatus = 1;

            BleCentral.Listener l = listener;
            if (l != null) {
                lnPackets++;
                l.onLn(d);
            }
        }

        String chTxt = CH_WIFI_NAME.equals(activeChannel)
                ? ctx.getString(R.string.sta_wifi_link)
                : (CH_CONN_NAME.equals(activeChannel)
                ? ctx.getString(R.string.sta_ble_link)
                : ctx.getString(R.string.sta_adv_link));
        // 状态栏文字固定成通道名：星数会偶尔掉到 0，那个也会让文字闪一下
        setStateIfNeeded(BleCentral.State.READY, chTxt);

        if (packets <= 3 || now - lastLogMs > 10000) {
            lastLogMs = now;
            Logs.add(TAG, String.format(Locale.CHINA,
                    "广播直连 #%d  功率=%dW 踏频=%d 速度=%.1fkm/h 位置=%.5f,%.5f 海拔=%dm 星=%d 电量=%d%%",
                    packets, powerW, cadence,
                    rawSpeed == 0xFFFF ? -1f : rawSpeed / 100.0f * 3.6f,
                    lat, lon, altM, sats, battery));
            long span = now - firstPacketMs;
            if (span > 3000) {
                Logs.add(TAG, String.format(Locale.CHINA, "接收速率 %.1f Hz（%d 包 / %.0f 秒）",
                        packets * 1000.0 / span, packets, span / 1000.0));
            }
        }
    }

    private long lastStateMs;

    private void setStateIfNeeded(BleCentral.State s, String detail) {
        long now = System.currentTimeMillis();
        if (state == s && now - lastStateMs < 1000) return;
        lastStateMs = now;
        setState(s, detail);
    }

    private void setState(BleCentral.State s, String detail) {
        state = s;
        // 收到过数据之后，状态栏只由通道文本（setStateIfNeeded）维护：
        // 否则扫描看门狗/"广播中断，正在重新搜索…"之类的调试消息会偶尔闪进状态栏，
        // 几秒后又变回去 —— 看起来就是"抽搐 + 混进奇怪的东西"。
        if (packets > 0 && s != BleCentral.State.READY) {
            Logs.add(TAG, "状态(" + s + ")：" + detail);
            return;
        }
        BleCentral.Listener l = listener;
        if (l != null) l.onState(s, detail);
    }

    // ================================================================

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int i16(byte[] b, int o) {
        return (short) u16(b, o);
    }

    /** 32 位小端有符号 */
    private static int i32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
}
