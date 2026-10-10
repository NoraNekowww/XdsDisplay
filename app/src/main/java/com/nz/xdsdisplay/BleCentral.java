package com.nz.xdsdisplay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 显示终端的 BLE 中央：扫描并连接"中转软件"广播出来的标准蓝牙功率计
 * (0x1818 + 0x1819)，订阅功率与定位，掉线自动重连。
 */
public class BleCentral {

    private static final String TAG = "BLE";

    public enum State { IDLE, SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING, READY, RETRY, ERROR }

    public interface Listener {
        void onState(State state, String detail);
        void onCps(CpsParser.Data data);
        void onLn(LnParser.Data data);
        void onBattery(int percent);
    }

    private static final long RECONNECT_MS = 1500L;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice device;

    private State state = State.IDLE;
    private String peerName = "";

    private boolean running;
    private boolean manualStop;

    // Android 同一条连接同时只允许一个 GATT 操作，所以订阅过程拆成严格串行的阶段，
    // 每个阶段等自己的回调（并带超时兜底），避免"后发起的操作被静默丢弃"。
    private static final int STAGE_NONE = 0;
    private static final int STAGE_CPS = 1;
    private static final int STAGE_LN = 2;
    private static final int STAGE_BATTERY = 3;
    private static final int STAGE_MTU = 4;
    private static final long STAGE_TIMEOUT_MS = 6000L;

    private BluetoothGattCharacteristic chCps, chLn, chBattery;
    private int stage = STAGE_NONE;
    private boolean gotCps, gotLn;
    private long cpsCount, lnCount;
    private long lastCpsAt, lastLnAt;
    private int failStreak;
    /** 断开专用 handler：不能被 cancelPending() 的 removeCallbacksAndMessages(null) 清掉 */
    private final Handler teardown = new Handler(Looper.getMainLooper());

    private int scanPhase;              // 0 = 带服务过滤, 1 = 全量
    private final Set<String> loggedAddrs = new HashSet<>();

    public BleCentral(Context ctx) {
        // 同 AdvLink：per-app 语言只包 Activity 的 Context，用 Application 的会拿系统语言
        this.ctx = ctx;
        BluetoothManager bm = (BluetoothManager) this.ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) scanner = adapter.getBluetoothLeScanner();
    }

    public void setListener(Listener l) { this.listener = l; }

    public State getState() { return state; }
    public String getPeerName() { return peerName; }
    public boolean isReady() { return state == State.READY; }
    public long cpsCount() { return cpsCount; }
    public long lnCount() { return lnCount; }

    // ================================================================

    @SuppressLint("MissingPermission")
    public void start() {
        if (adapter == null) {
            setState(State.ERROR, ctx.getString(R.string.ble_no_adapter));
            return;
        }
        if (!adapter.isEnabled()) {
            Logs.add(TAG, "蓝牙未开启，请先打开蓝牙");
            setState(State.ERROR, ctx.getString(R.string.sta_bt_off));
            return;
        }
        running = true;
        manualStop = false;
        startScan();
    }

    public void stop() {
        manualStop = true;
        running = false;
        stopScan();
        cancelPending();
        disconnect();
        setState(State.IDLE, ctx.getString(R.string.sta_stopped));

    }

    @SuppressLint("MissingPermission")
    public void reconnect() {
        Logs.add(TAG, "手动重新连接");
        cancelPending();
        stopScan();
        disconnect();
        if (adapter != null && !adapter.isEnabled()) {
            setState(State.ERROR, ctx.getString(R.string.sta_bt_off));
            return;
        }
        running = true;
        manualStop = false;
        handler.postDelayed(this::startScan, 600L);
    }

    // ================================================================
    //  扫描
    // ================================================================

    @SuppressLint("MissingPermission")
    private void startScan() {
        startScanInternal(true);
    }

    @SuppressLint("MissingPermission")
    private void startScanInternal(boolean filtered) {
        if (!running || manualStop) return;
        if (!hasScanPermission()) {
            setState(State.ERROR, ctx.getString(R.string.ble_no_perm));

            Logs.add(TAG, "缺少蓝牙扫描权限，无法搜索中继设备");
            return;
        }
        if (scanner == null) scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setState(State.ERROR, ctx.getString(R.string.sta_scanner_unavailable));

            return;
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        List<ScanFilter> filters = null;
        if (filtered) {
            // 带服务过滤器的扫描在后台也能持续回调（无过滤器会被系统限流到几分钟一次），
            // 中继端一定会在广播包里带上 0x1818 / 0x1819。
            filters = new ArrayList<>();
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(BleUuids.CPS_SERVICE)).build());
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(BleUuids.LNS_SERVICE)).build());
        } else {
            loggedAddrs.clear();
        }
        scanPhase = filtered ? 0 : 1;

        try {
            scanner.startScan(filters, settings, scanCallback);
            setState(State.SCANNING, filtered ? "搜索中继设备…" : "全量排查扫描…");
            Logs.add(TAG, filtered
                    ? "开始扫描：过滤 0x1818 + 0x1819"
                    : "开始扫描：不过滤（把周围所有蓝牙设备都列出来，便于排查）");
        } catch (SecurityException e) {
            Logs.add(TAG, "扫描被拒绝: " + e.getMessage());
            setState(State.ERROR, ctx.getString(R.string.ble_scan_denied));

        } catch (Exception e) {
            Logs.add(TAG, "扫描失败: " + e);
            setState(State.ERROR, ctx.getString(R.string.sta_scan_failed));

        }

        handler.removeCallbacks(escalate);
        if (filtered) {
            handler.postDelayed(escalate, 12000L);
        }
    }

    /** 过滤扫描 12 秒还没结果，就切成全量扫描并把看到的设备全部打出来。 */
    private final Runnable escalate = () -> {
        if (!running || manualStop || state != State.SCANNING || scanPhase != 0) return;
        Logs.add(TAG, "12 秒内没有发现带标准功率服务的广播，切换为全量扫描排查");
        stopScan();
        handler.postDelayed(() -> {
            if (running && !manualStop && state == State.SCANNING) startScanInternal(false);
        }, 400L);
    };

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanner == null) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (Exception ignore) {
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handle(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) handle(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Logs.add(TAG, "扫描失败 code=" + errorCode);
            setState(State.ERROR, ctx.getString(R.string.sta_scan_failed_fmt, errorCode));

        }
    };

    @SuppressLint("MissingPermission")
    private void handle(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        if (state != State.SCANNING) return;

        BluetoothDevice d = result.getDevice();
        ScanRecord rec = result.getScanRecord();
        String name = rec != null && rec.getDeviceName() != null ? rec.getDeviceName() : safeName(d);
        if (name == null) name = "";

        boolean powerMeter = advHasService(rec, BleUuids.CPS_SERVICE_SHORT)
                || advHasService(rec, BleUuids.LNS_SERVICE_SHORT)
                || name.toUpperCase(Locale.ROOT).contains("XDS")
                || name.toUpperCase(Locale.ROOT).contains("RELAY");

        // 全量排查阶段：把看到的每一个设备都打出来，方便判断到底是谁在广播
        if (scanPhase == 1 && loggedAddrs.add(d.getAddress())) {
            Logs.add(TAG, "看到设备 " + (name.isEmpty() ? "(无名)" : name)
                    + "  " + d.getAddress() + "  RSSI=" + result.getRssi()
                    + "  服务=" + describeServices(rec)
                    + (powerMeter ? "   ★像中继" : ""));
        }

        if (!powerMeter) return;

        Logs.add(TAG, "找到中继设备 " + (name.isEmpty() ? "(无名)" : name)
                + "  " + d.getAddress() + "  RSSI=" + result.getRssi());
        handler.removeCallbacks(escalate);
        stopScan();
        connect(d);
    }

    private static String describeServices(ScanRecord rec) {
        if (rec == null) return "-";
        StringBuilder sb = new StringBuilder();
        List<ParcelUuid> uuids = rec.getServiceUuids();
        if (uuids != null && !uuids.isEmpty()) {
            for (ParcelUuid pu : uuids) sb.append(shortUuid(pu.getUuid())).append(' ');
        } else {
            byte[] raw = rec.getBytes();
            if (raw != null) {
                int index = 0;
                while (index < raw.length) {
                    int fl = raw[index] & 0xFF;
                    if (fl == 0 || index + fl >= raw.length) break;
                    int type = raw[index + 1] & 0xFF;
                    if (type == 0x02 || type == 0x03) {
                        for (int vi = index + 2; vi + 1 < index + fl + 1; vi += 2) {
                            int v = (raw[vi] & 0xFF) | ((raw[vi + 1] & 0xFF) << 8);
                            sb.append(String.format(Locale.ROOT, "0x%04X ", v));
                        }
                    }
                    index += fl + 1;
                }
            }
        }
        return sb.length() == 0 ? "-" : sb.toString().trim();
    }

    private static boolean advHasService(ScanRecord rec, int shortUuid) {
        if (rec == null) return false;
        List<ParcelUuid> uuids = rec.getServiceUuids();
        if (uuids != null) {
            for (ParcelUuid pu : uuids) {
                UUID u = pu.getUuid();
                if (u.getLeastSignificantBits() == 0x800000805F9B34FBL
                        && (int) ((u.getMostSignificantBits() >>> 32) & 0xFFFF) == shortUuid) {
                    return true;
                }
            }
        }
        byte[] raw = rec.getBytes();
        if (raw == null) return false;
        int index = 0;
        while (index < raw.length) {
            int fieldLength = raw[index] & 0xFF;
            if (fieldLength == 0) return false;
            if (index + fieldLength >= raw.length) return false;
            int type = raw[index + 1] & 0xFF;
            if (type == 0x02 || type == 0x03) {
                int vi = index + 2;
                int ve = index + fieldLength + 1;
                while (vi + 1 < ve) {
                    int found = (raw[vi] & 0xFF) | ((raw[vi + 1] & 0xFF) << 8);
                    if (found == shortUuid) return true;
                    vi += 2;
                }
            }
            index += fieldLength + 1;
        }
        return false;
    }

    // ================================================================
    //  连接
    // ================================================================

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice d) {
        device = d;
        peerName = safeName(d);
        if (peerName == null || peerName.isEmpty()) peerName = d.getAddress();
        setState(State.CONNECTING, "连接 " + peerName);
        try {
            gatt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? d.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    : d.connectGatt(ctx, false, gattCallback);
        } catch (SecurityException e) {
            Logs.add(TAG, "连接被拒绝: " + e.getMessage());
            setState(State.ERROR, ctx.getString(R.string.ble_connect_refused));

        }
        if (gatt == null) setState(State.ERROR, ctx.getString(R.string.ble_gatt_null));

    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Logs.add(TAG, "已连接，发现服务");
                setState(State.DISCOVERING, "发现服务");
                try {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                } catch (Exception ignore) {}
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    Logs.add(TAG, "discoverServices 被拒绝");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Logs.add(TAG, "连接断开 status=" + status);
                closeGatt();
                if (running && !manualStop) {
                    setState(State.RETRY, "断开，重连中…");
                    handler.postDelayed(() -> {
                        if (running && !manualStop && state == State.RETRY) startScan();
                    }, RECONNECT_MS);
                } else {
                    setState(State.IDLE, ctx.getString(R.string.ble_disconnected));

                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Logs.add(TAG, "服务发现失败 status=" + status);
                scheduleRetry();
                return;
            }

            BluetoothGattCharacteristic cps = null;
            BluetoothGattCharacteristic ln = null;
            BluetoothGattCharacteristic battery = null;

            BluetoothGattService cpsSvc = g.getService(BleUuids.CPS_SERVICE);
            if (cpsSvc != null) cps = cpsSvc.getCharacteristic(BleUuids.CPS_MEASUREMENT);
            BluetoothGattService lnSvc = g.getService(BleUuids.LNS_SERVICE);
            if (lnSvc != null) ln = lnSvc.getCharacteristic(BleUuids.LN_LOCATION_SPEED);
            BluetoothGattService batSvc = g.getService(BleUuids.BATTERY_SERVICE);
            if (batSvc != null) battery = batSvc.getCharacteristic(BleUuids.BATTERY_LEVEL);

            if (cps == null && ln == null) {
                Logs.add(TAG, "对方没有标准功率/定位服务，放弃");
                scheduleRetry();
                return;
            }

            chCps = cps;
            chLn = ln;
            chBattery = battery;
            gotCps = false;
            gotLn = false;
            lastCpsAt = lastLnAt = 0;

            Logs.add(TAG, "服务清单: " + describe(g));
            failStreak = 0;
            setState(State.SUBSCRIBING, "订阅数据");

            // 关键一步：先把本机的"通知接收"注册打开。
            // 之后即使对面不回 CCCD 写请求，只要中转端推通知，本机协议栈就会把它交给我们。
            armLocalNotifications(g);

            // 关键：不能在 onServicesDiscovered 回调里同步发起下一个 GATT 操作，
            // 底层此时仍认为"服务发现"占用着连接，会把请求静默丢掉（返回 true 却永远没有回调）。
            // 延迟一下再开始订阅。
            handler.postDelayed(() -> {
                if (gatt != g) return;
                if (chCps != null) {
                    startStage(STAGE_CPS);
                } else {
                    Logs.add(TAG, "对方没有 0x2A63，直接跳到定位订阅");
                    startStage(STAGE_LN);
                }
            }, 500L);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            UUID u = descriptor.getCharacteristic().getUuid();
            int current = stage;
            boolean expected = (current == STAGE_CPS && BleUuids.CPS_MEASUREMENT.equals(u))
                    || (current == STAGE_LN && BleUuids.LN_LOCATION_SPEED.equals(u));
            if (!expected) return;

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (current == STAGE_CPS) {
                    gotCps = true;
                    Logs.add(TAG, "已订阅骑行功率 0x2A63");
                } else {
                    gotLn = true;
                    Logs.add(TAG, "已订阅位置与速度 0x2A67");
                }
            } else {
                Logs.add(TAG, "订阅 " + shortUuid(u) + " 失败 status=" + status);
            }
            advanceStage(current);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (stage == STAGE_BATTERY && BleUuids.BATTERY_LEVEL.equals(c.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    byte[] v = c.getValue();
                    if (v != null && v.length > 0) {
                        int pct = v[0] & 0xFF;
                        if (listener != null) listener.onBattery(pct);
                        Logs.add(TAG, "中继端电量 " + pct + "%");
                    }
                }
                advanceStage(STAGE_BATTERY);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                        byte[] value, int status) {
            if (stage == STAGE_BATTERY && BleUuids.BATTERY_LEVEL.equals(c.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.length > 0) {
                    int pct = value[0] & 0xFF;
                    if (listener != null) listener.onBattery(pct);
                    Logs.add(TAG, "中继端电量 " + pct + "%");
                }
                advanceStage(STAGE_BATTERY);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            Logs.add(TAG, "MTU=" + mtu + " status=" + status);
            if (stage == STAGE_MTU) advanceStage(STAGE_MTU);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            onNotify(c.getUuid(), c.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onNotify(c.getUuid(), value);
        }
    };

    // ================================================================
    //  订阅阶段机
    // ================================================================

    private void startStage(int s) {
        stage = s;
        handler.removeCallbacks(stageTimeout);
        if (s == STAGE_NONE) return;
        handler.postDelayed(stageTimeout, STAGE_TIMEOUT_MS);

        boolean ok = false;
        BluetoothGatt g = gatt;
        if (g == null) {
            Logs.add(TAG, "连接已失效，停止订阅");
            stage = STAGE_NONE;
            return;
        }
        try {
            if (s == STAGE_CPS && chCps != null) {
                ok = writeCccd(g, chCps);
            } else if (s == STAGE_LN) {
                ok = chLn != null && writeCccd(g, chLn);
            } else if (s == STAGE_BATTERY) {
                ok = chBattery != null && g.readCharacteristic(chBattery);
            } else if (s == STAGE_MTU) {
                ok = g.requestMtu(247);
            }
        } catch (SecurityException e) {
            Logs.add(TAG, "GATT 操作被拒绝: " + e.getMessage());
        } catch (Exception e) {
            Logs.add(TAG, "GATT 操作异常: " + e);
        }

        if (!ok) {
            Logs.add(TAG, "阶段 " + stageName(s) + " 无法发起，跳过");
            final int cur = s;
            handler.postDelayed(() -> advanceStage(cur), 400L);
        }
    }

    private void advanceStage(int from) {
        if (stage != from) return;
        handler.removeCallbacks(stageTimeout);
        final int next;
        switch (from) {
            case STAGE_CPS: next = STAGE_LN; break;
            case STAGE_LN: next = STAGE_BATTERY; break;
            case STAGE_BATTERY: next = STAGE_MTU; break;
            default:
                stage = STAGE_NONE;
                finishSubscribe();
                return;
        }
        stage = STAGE_NONE;
        // 每个操作之间留一点间隔，避免连续 GATT 操作被底层丢掉
        handler.postDelayed(() -> startStage(next), 250L);
    }

    /** 某个阶段迟迟没有回调时跳过它，保证流程一定能走完 */
    private final Runnable stageTimeout = () -> {
        int s = stage;
        if (s == STAGE_NONE) return;
        Logs.add(TAG, "阶段 " + stageName(s) + " 超时未回调，跳过");
        stage = STAGE_NONE;
        final int next;
        switch (s) {
            case STAGE_CPS: next = STAGE_LN; break;
            case STAGE_LN: next = STAGE_BATTERY; break;
            case STAGE_BATTERY: next = STAGE_MTU; break;
            default:
                finishSubscribe();
                return;
        }
        handler.postDelayed(() -> startStage(next), 250L);
    };

    /**
     * 打开本机的通知接收（本地状态）。
     * 这一步和"写对面 CCCD"是两件事：CCCD 写是告诉对面"请推给我"，
     * setCharacteristicNotification 是告诉本机协议栈"收到这个特征的通知就交给我"。
     * 对面不回 CCCD 时，只要它推了，本机照样能收到 —— 所以这一步必须无条件做。
     */
    @SuppressLint("MissingPermission")
    private void armLocalNotifications(BluetoothGatt g) {
        BluetoothGattCharacteristic[] list = new BluetoothGattCharacteristic[]{chCps, chLn};
        for (BluetoothGattCharacteristic ch : list) {
            if (ch == null) continue;
            try {
                boolean ok = g.setCharacteristicNotification(ch, true);
                Logs.add(TAG, "本地注册通知 " + shortUuid(ch.getUuid()) + " -> " + ok);
            } catch (Exception e) {
                Logs.add(TAG, "本地注册通知失败 " + shortUuid(ch.getUuid()) + " : " + e);
            }
        }
    }

    private void finishSubscribe() {
        if (gotCps || gotLn) {
            Logs.add(TAG, "订阅完成：功率=" + gotCps + " 定位=" + gotLn);
        } else if (gatt != null) {
            // CCCD 写请求对面可能不回（实测某些协议栈组合会这样），
            // 但本机的通知接收已经注册好了，中转端也会主动推 —— 所以不断开，直接进入等数据状态。
            Logs.add(TAG, "订阅握手没有回应，但通知已在本地注册，继续接收（中转端会主动推）");
        } else {
            Logs.add(TAG, "连接已断开，重连");
            scheduleRetry();
            return;
        }
        setState(State.READY, "已连接 " + peerName);
        scheduleDataWatchdog();
    }

    /** 连上了却一直没数据，也要能自己恢复 */
    private void scheduleDataWatchdog() {
        handler.removeCallbacks(dataWatchdogRunnable);
        handler.postDelayed(dataWatchdogRunnable, 20000L);
    }

    private final Runnable dataWatchdogRunnable = () -> {
        if (state != State.READY) return;
        long now = System.currentTimeMillis();
        // 不再依赖"订阅成功"标志：只要到了数据就刷新时间戳，长时间没有任何数据才重连
        boolean cpsSilent = lastCpsAt == 0 || now - lastCpsAt > 30000;
        boolean lnSilent = lastLnAt == 0 || now - lastLnAt > 30000;
        if (cpsSilent && lnSilent) {
            Logs.add(TAG, "已连接但 30 秒没有任何数据，重新连接");
            scheduleRetry();
        } else {
            scheduleDataWatchdog();
        }
    };

    private static String stageName(int s) {
        switch (s) {
            case STAGE_CPS: return "订阅功率";
            case STAGE_LN: return "订阅定位";
            case STAGE_BATTERY: return "读电量";
            case STAGE_MTU: return "协商 MTU";
            default: return "无";
        }
    }

    @SuppressLint("MissingPermission")
    private boolean writeCccd(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        BluetoothGattDescriptor cccd = ch.getDescriptor(BleUuids.CCCD);
        if (cccd == null) {
            Logs.add(TAG, shortUuid(ch.getUuid()) + " 没有 CCCD");
            return false;
        }
        try {
            if (!g.setCharacteristicNotification(ch, true)) {
                Logs.add(TAG, "setCharacteristicNotification 返回 false: " + shortUuid(ch.getUuid()));
            }
        } catch (SecurityException e) {
            Logs.add(TAG, "setCharacteristicNotification 被拒绝: " + e.getMessage());
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ 的这个重载返回 int 状态码（BluetoothStatusCodes），不是 boolean
            int rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (rc != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                Logs.add(TAG, "writeDescriptor 返回状态 " + rc);
                return false;
            }
            return true;
        }
        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        return g.writeDescriptor(cccd);
    }

    private void onNotify(UUID uuid, byte[] value) {
        if (value == null) return;
        if (BleUuids.CPS_MEASUREMENT.equals(uuid)) {
            CpsParser.Data d = new CpsParser.Data();
            if (CpsParser.parse(value, 0, value.length, d)) {
                cpsCount++;
                lastCpsAt = System.currentTimeMillis();
                if (cpsCount <= 3 || cpsCount % 100 == 0) {
                    Logs.add(TAG, "功率包 #" + cpsCount + "  " + d.describe());
                }
                if (listener != null) listener.onCps(d);
            }
        } else if (BleUuids.LN_LOCATION_SPEED.equals(uuid)) {
            LnParser.Data d = new LnParser.Data();
            if (LnParser.parse(value, 0, value.length, d)) {
                lnCount++;
                lastLnAt = System.currentTimeMillis();
                if (lnCount <= 3 || lnCount % 60 == 0) {
                    Logs.add(TAG, "定位包 #" + lnCount + "  " + d.describe());
                }
                if (listener != null) listener.onLn(d);
            }
        }
    }

    private void scheduleRetry() {
        cancelPending();
        softDisconnect();
        if (running && !manualStop) {
            // 失败越多等越久：刚断开就猛冲很容易连吃 133（对方还握着旧链路），
            // 拉长间隔能让对面的 ACL 真正释放掉。
            failStreak++;
            long delay = Math.min(RECONNECT_MS + failStreak * 1500L, 9000L);
            Logs.add(TAG, "第 " + failStreak + " 次重连，等 " + delay + "ms（让对面释放旧链路）");
            setState(State.RETRY, "重试中…");
            handler.postDelayed(() -> {
                if (running && !manualStop && state == State.RETRY) startScan();
            }, delay);
        }
    }

    private void cancelPending() {
        handler.removeCallbacksAndMessages(null);
    }

    @SuppressLint("MissingPermission")
    private void disconnect() {
        if (gatt == null) return;
        try {
            gatt.disconnect();
        } catch (Exception ignore) {}
    }

    /**
     * 温柔断开：先发 disconnect()，等 2.5 秒再 close()。
     * 之前是 disconnect() + 立刻 close()，结果对面（中转机）一直没察觉到断开、
     * 还握着那条旧链路，这边重连就连续吃 133。
     */
    @SuppressLint("MissingPermission")
    private void softDisconnect() {
        final BluetoothGatt g0 = gatt;
        if (g0 == null) return;
        try {
            g0.disconnect();
        } catch (Exception ignore) {}
        teardown.removeCallbacksAndMessages(null);
        teardown.postDelayed(() -> {
            if (gatt == g0) closeGatt();
        }, 2500L);
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (gatt == null) return;
        try {
            gatt.close();
        } catch (Exception ignore) {}
        gatt = null;
        stage = STAGE_NONE;
        gotCps = false;
        gotLn = false;
        chCps = chLn = chBattery = null;
    }

    private void setState(State s, String detail) {
        state = s;
        if (listener != null) listener.onState(s, detail);
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice d) {
        try {
            return d.getName();
        } catch (SecurityException e) {
            return "";
        }
    }

    private boolean hasScanPermission() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_SCAN
                : Manifest.permission.ACCESS_FINE_LOCATION;
        return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static String describe(BluetoothGatt g) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (BluetoothGattService s : g.getServices()) {
            sb.append(shortUuid(s.getUuid())).append('[');
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                sb.append(shortUuid(c.getUuid())).append(' ');
            }
            sb.append("] ");
        }
        return sb.toString().trim();
    }

    private static String shortUuid(UUID u) {
        String s = u.toString();
        if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            return "0x" + s.substring(4, 8).toUpperCase(Locale.ROOT);
        }
        return s;
    }
}
