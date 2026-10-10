package com.nz.xdsdisplay;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WiFi 直连接收端（从机侧）。
 *
 * 用法：主机开热点 → 本机连上该热点 → 主机把遥测用 UDP 发过来。
 * 延迟比 BLE 广播低一个量级、带宽也大得多（可以跑到 20Hz+）。
 * 出门没热点时收不到，会自动回落到 BLE 广播，不用手动切。
 *
 * 两个坑：
 *  1. Android 的 WiFi 省电会把广播包丢掉，必须持有 MulticastLock，否则一个包都收不到；
 *  2. 热点下广播不可靠，所以本机每 2 秒主动向主机"报到"，主机记下本机地址后走单播。
 */
public class UdpLink {

    private static final String TAG = "WIFI";
    public static final int PORT = 47820;

    private static final byte[] MAGIC_W = {'X', 'D', 'S', 'W'};   // 遥测帧
    private static final byte[] MAGIC_H = {'X', 'D', 'S', 'H'};   // 报到帧
    private static final byte[] MAGIC_S = {'X', 'D', 'S', 'S'};   // 回环自检帧
    private static final byte[] MAGIC_C = {'X', 'D', 'S', 'C'};   // 控制帧（本机 → 主机）
    private static final int VERSION = 1;
    private static final int PAYLOAD_LEN = 20;

    // 控制命令（要和 XdsRelay 的 UdpSender 里那套对上）
    public static final int CMD_PAUSE = 1;
    public static final int CMD_RESUME = 2;
    public static final int CMD_TOGGLE = 3;
    public static final int CMD_END = 4;
    public static final int CMD_START = 5;

    /** 主机（中转机）地址：从收到的遥测包上记下来的 */
    private volatile InetAddress relayAddr;

    public boolean hasRelay() {
        return relayAddr != null;
    }

    public String relayAddrText() {
        InetAddress a = relayAddr;
        return a == null ? "未找到主机" : a.getHostAddress();
    }

    public static String cmdName(int cmd) {
        switch (cmd) {
            case CMD_PAUSE: return "暂停记录";
            case CMD_RESUME: return "继续记录";
            case CMD_TOGGLE: return "暂停/继续";
            case CMD_END: return "结束记录并导出";
            case CMD_START: return "开始新记录";
            default: return "未知(" + cmd + ")";
        }
    }

    /**
     * 给中转机下发一条记录控制命令。
     * 知道主机地址就单播；不知道（还没收到过遥测）就广播 + 网关兜底，
     * 反正主机在同一个端口上听着。
     */
    public boolean sendControl(int cmd) {
        DatagramSocket s = socket;
        if (s == null) return false;
        byte[] f = {MAGIC_C[0], MAGIC_C[1], MAGIC_C[2], MAGIC_C[3],
                (byte) VERSION, (byte) cmd};
        InetAddress ra = relayAddr;
        boolean ok = false;
        if (ra != null) {
            try {
                s.send(new DatagramPacket(f, f.length, ra, PORT));
                ok = true;
            } catch (Exception ignore) {
            }
        }
        if (!ok) {
            for (InetAddress a : helloTargets()) {
                try {
                    s.send(new DatagramPacket(f, f.length, a, PORT));
                    ok = true;
                } catch (Exception ignore) {
                }
            }
        }
        Logs.add(TAG, (ok ? "已下发控制命令：" : "⚠ 控制命令发送失败：") + cmdName(cmd)
                + "（" + (ra != null ? ra.getHostAddress() : "广播/网关") + "）");
        return ok;
    }

    public interface Listener {
        void onTelemetry(byte[] payload, long now);
    }

    private final Context ctx;
    private final Listener listener;

    private volatile DatagramSocket socket;      // 会被链路自愈线程关掉重建，必须 volatile
    private WifiManager.MulticastLock mcast;
    private WifiManager.WifiLock wifiLock;
    private Thread rxThread, helloThread;
    private volatile boolean running;

    private volatile long packets;
    private volatile long lastRxMs;
    private volatile boolean selfTestOk;

    public UdpLink(Context c, Listener l) {
        this.ctx = c.getApplicationContext();
        this.listener = l;
    }

    public long packets() {
        return packets;
    }

    /** 3 秒内有包就算 WiFi 链路活着 */
    public boolean isAlive() {
        return lastRxMs > 0 && System.currentTimeMillis() - lastRxMs < 3000L;
    }

    // ---- 链路自愈：收不到遥测时必须自己爬回来，不能卡死 ----

    /** 收到过第一个包之后，静默超过这么久就重建接收通路 */
    private static final long SILENT_REBUILD_MS = 6000L;
    /** 一次都没收到过时，给多长宽限（热点刚连上、主机刚起来都要时间） */
    private static final long FIRST_PACKET_GRACE_MS = 45000L;
    /** 两次重建之间的最小间隔，别把刚建好的通路又推翻 */
    private static final long REBUILD_MIN_GAP_MS = 8000L;
    /** 连续接收异常到这么多条就判定 socket 已废，直接重建 */
    private static final int ERR_STREAK_LIMIT = 5;

    /** 连着重建了几次仍收不到包：用来做退避（收到包就清零） */
    private volatile int silentRebuilds;

    private volatile long startedMs;
    private volatile long lastRebuildMs;
    private volatile long lastHealthLogMs;
    private volatile int rebuilds;
    private volatile int packetsAtHealth;
    private volatile long healthAtMs;
    private volatile int errStreak;
    /** 置位后 hello 线程立刻补发一次报到（重建后马上让主机重新认得本机） */
    private volatile boolean helloNow;

    /** 距离上次收到主机遥测有多久（毫秒）；从没收到过则返回 -1 */
    public long silenceMs() {
        long t = lastRxMs;
        return t > 0 ? System.currentTimeMillis() - t : -1L;
    }

    public int rebuildCount() {
        return rebuilds;
    }

    /** 收到过主机遥测没有 */
    public boolean everHeard() {
        return lastRxMs > 0;
    }

    /**
     * 外部（仪表端的中继断流监督）叫我们主动修一次：
     * 立刻补发报到 + 把接收 socket 重建一遍。便宜、无害，值得在断流时试。
     */
    public synchronized void nudge(String why) {
        if (!running) return;
        helloNow = true;
        rebuild("外部请求：" + (why == null ? "断流" : why));
    }

    /**
     * 链路健康监督线程：主机那边"显示正常转发"，不代表本机真的收到包了
     * （热点下广播会被过滤、本机可能被系统切到蜂窝数据、socket 也可能卡死）。
     * 所以这里只认一个事实：最近有没有真的收到遥测。没有就重建，并如实记进日志。
     */
    private void watchLoop() {
        while (running) {
            sleep(1000);
            if (!running) break;
            long now = System.currentTimeMillis();

            long silent = lastRxMs > 0 ? now - lastRxMs : now - startedMs;
            long limit = lastRxMs > 0 ? SILENT_REBUILD_MS : FIRST_PACKET_GRACE_MS;

            long gapBackoff = rebuildGapMs();
            if (socket == null) {
                if (rebuild("接收 socket 为空")) errStreak = 0;
            } else if (errStreak >= ERR_STREAK_LIMIT) {
                if (rebuild("连续接收异常 " + errStreak + " 次")) errStreak = 0;
            } else if (silent > limit && now - lastRebuildMs > gapBackoff) {
                // 退避：静默越久，重建间隔越长（10s → 20s → 40s … 最多 5 分钟一次）。
                // 不然"本来就没接主机"时每几秒重建一次，日志刷屏还白耗电。
                if (rebuild("已静默 " + (silent / 1000) + " 秒")) silentRebuilds++;
            }

            if (now - lastHealthLogMs > 60000L) {
                lastHealthLogMs = now;
                long dt = now - healthAtMs;
                int got = (int) (packets - packetsAtHealth);
                packetsAtHealth = (int) packets;
                healthAtMs = now;
                StringBuilder sb = new StringBuilder();
                sb.append("WiFi 链路自检：总收 ").append(packets).append(" 包");
                if (dt > 0) {
                    sb.append(String.format(Locale.CHINA, " · 近 %.0f 秒 %d 包", dt / 1000.0, got));
                }
                if (rebuilds > 0) sb.append(" · 已自愈 ").append(rebuilds).append(" 次");
                if (lastRxMs > 0) sb.append(" · 静默 ").append((now - lastRxMs) / 1000).append(" 秒");
                sb.append(" · ").append(netState());
                Logs.add(TAG, sb.toString());
            }
        }
    }

    /** 重建接收 socket：关旧的、开新的、重绑端口，并立刻补报到。返回 true = 这次真重建了 */
    private synchronized boolean rebuild(String why) {
        if (!running) return false;
        long now = System.currentTimeMillis();
        long sinceLast = now - lastRebuildMs;
        if (lastRebuildMs > 0 && sinceLast < REBUILD_MIN_GAP_MS) return false;   // 刚建过，别抖
        lastRebuildMs = now;
        rebuilds++;
        errStreak = 0;

        Logs.add(TAG, "⚠ UDP 收包通路重建（" + why + "）· 静默 "
                + (lastRxMs > 0 ? (now - lastRxMs) / 1000 + " 秒" : "未收到过")
                + " · " + netState());

        try {
            DatagramSocket old = socket;
            socket = null;
            if (old != null) old.close();
        } catch (Exception ignore) {}

        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(PORT));
            socket = s;
            helloNow = true;            // 立刻让主机重新认得本机
            return true;
        } catch (Exception e) {
            errStreak++;
            Logs.add(TAG, "⚠ UDP 重建失败（第 " + errStreak + " 次）：" + e);
            sleep(300);
            return false;
        }
    }

    /**
     * 静默重建的退避间隔：连着重建好几次都收不到包，就别每 8 秒折腾一次 ——
     * 10s → 20s → 40s → … 最多 5 分钟一次。本该没接主机时也照样安静。
     */
    private long rebuildGapMs() {
        int n = Math.min(silentRebuilds, 6);
        // 从没收到过遥测（本机就是单机用）→ 起步 60 秒，别打扰；
        // 收到过又断了 → 起步 20 秒，快点爬回来。
        long base = lastRxMs > 0 ? 20000L : 60000L;
        long g = base << Math.max(0, n - 1);
        return Math.min(g, 300000L);
    }

    /** 当前网络状态，专给日志用：一眼看出"是不是离开了主机的热点" */
    private String netState() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "网络未知";
            Network n = cm.getActiveNetwork();
            if (n == null) return "当前无网络";
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            boolean wifi = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
            boolean cell = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
            LinkProperties lp = cm.getLinkProperties(n);
            String addr = "";
            if (lp != null) {
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    java.net.InetAddress a = la.getAddress();
                    if (a instanceof java.net.Inet4Address) {
                        addr = a.getHostAddress();
                        break;
                    }
                }
            }
            if (wifi) return "在 WiFi(" + addr + ")" + (relayAddr != null ? "，主机 " + relayAddr.getHostAddress() : "");
            if (cell) return "⚠ 不在 WiFi，走蜂窝数据(" + addr + ") —— 收不到主机的 UDP";
            return "非 WiFi 网络(" + addr + ")";
        } catch (Throwable t) {
            return "网络状态读取失败";
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mcast = wm.createMulticastLock("xds-udp");
                mcast.setReferenceCounted(false);
                mcast.acquire();
                // 保持 WiFi 高性能，避免息屏后收包被电源管理掐掉
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "xds-udp");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }

            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(PORT));
        } catch (Exception e) {
            Logs.add(TAG, "WiFi 接收初始化失败：" + e);
            running = false;
            release();
            return;
        }

        rxThread = new Thread(this::rxLoop, "xds-udp-rx");
        rxThread.setDaemon(true);
        rxThread.start();
        helloThread = new Thread(this::helloLoop, "xds-udp-hello");
        helloThread.setDaemon(true);
        helloThread.start();

        startedMs = System.currentTimeMillis();
        lastHealthLogMs = startedMs;
        healthAtMs = startedMs;
        packetsAtHealth = (int) packets;
        Thread watcher = new Thread(this::watchLoop, "xds-udp-watch");
        watcher.setDaemon(true);
        watcher.start();

        Logs.add(TAG, "WiFi 直连已就绪（UDP " + PORT + "），连上主机的热点即可自动接管 · " + netState());
        startSelfTest();
    }

    /**
     * 本机回环自检：往自己的监听端口发一帧自检包。
     * 能收回来就说明 UDP 收包通路（绑定、省电锁、解析）是通的 —— 省得怀疑半天。
     */
    private void startSelfTest() {
        Thread t = new Thread(() -> {
            sleep(400);
            try {
                DatagramSocket s = socket;
                if (s == null) return;
                byte[] f = new byte[]{MAGIC_S[0], MAGIC_S[1], MAGIC_S[2], MAGIC_S[3], (byte) VERSION, 0};
                s.send(new DatagramPacket(f, f.length, InetAddress.getByName("127.0.0.1"), PORT));
            } catch (Exception e) {
                Logs.add(TAG, "回环自检发送失败：" + e);
            }
            sleep(2500);
            Logs.add(TAG, selfTestOk ? "本机回环自检通过：UDP 收包通路可用"
                    : "⚠ 回环自检未通过：UDP 收包可能被系统拦（端口占用？）");
        }, "xds-udp-selftest");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignore) {
        }
        socket = null;
        release();
    }

    private void release() {
        try {
            if (mcast != null && mcast.isHeld()) mcast.release();
        } catch (Exception ignore) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignore) {
        }
        mcast = null;
        wifiLock = null;
    }

    // ================================================================

    private void rxLoop() {
        byte[] buf = new byte[512];
        while (running) {
            DatagramSocket s = socket;
            if (s == null) break;
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                int len = p.getLength();
                if (len < 5) continue;
                if (buf[0] == MAGIC_S[0] && buf[1] == MAGIC_S[1]
                        && buf[2] == MAGIC_S[2] && buf[3] == MAGIC_S[3]) {
                    selfTestOk = true;      // 回环自检包，不当作遥测
                    continue;
                }
                if (len < 5 + PAYLOAD_LEN) continue;
                if (buf[0] != MAGIC_W[0] || buf[1] != MAGIC_W[1]
                        || buf[2] != MAGIC_W[2] || buf[3] != MAGIC_W[3]) continue;
                if ((buf[4] & 0xFF) != VERSION) continue;

                byte[] payload = new byte[PAYLOAD_LEN];
                System.arraycopy(buf, 5, payload, 0, PAYLOAD_LEN);

                long now = System.currentTimeMillis();
                packets++;
                long gap = lastRxMs > 0 ? now - lastRxMs : 0;
                lastRxMs = now;
                errStreak = 0;
                silentRebuilds = 0;             // 收到包 → 重建退避清零
                if (gap > 5000L) {
                    Logs.add(TAG, "WiFi 遥测恢复接收（中断 " + (gap / 1000) + " 秒）· " + netState());
                }
                relayAddr = p.getAddress();     // 记下主机地址，方便回下发控制命令
                if (packets <= 3) {
                    Logs.add(TAG, "WiFi 收到主机遥测 #" + packets
                            + "（来自 " + p.getAddress().getHostAddress() + "）");
                }
                Listener l = listener;
                if (l != null) l.onTelemetry(payload, now);
            } catch (Exception e) {
                if (!running) break;
                errStreak++;
                // 我们自己关掉 socket 重建时必然抛 "Socket closed"，那是预期内的，别当异常记
                boolean selfInflicted = String.valueOf(e).contains("Socket closed");
                if (!selfInflicted && (errStreak <= 3 || errStreak % 20 == 0)) {
                    Logs.add(TAG, "WiFi 接收异常（第 " + errStreak + " 次）：" + e);
                }
                sleep(500);
            }
        }
    }

    private void helloLoop() {
        byte[] hello = new byte[]{MAGIC_H[0], MAGIC_H[1], MAGIC_H[2], MAGIC_H[3], (byte) VERSION};
        int waited = 0;
        while (running) {
            try {
                boolean force = helloNow;
                if (force) helloNow = false;
                if (force || waited >= 2000) {
                    waited = 0;
                    DatagramSocket s = socket;
                    if (s != null) {
                        for (InetAddress a : helloTargets()) {
                            try {
                                s.send(new DatagramPacket(hello, hello.length, a, PORT));
                            } catch (Exception ignore) {
                            }
                        }
                        if (force) Logs.add(TAG, "已向主机补发报到（单播 " + relayAddrText() + "）");
                    }
                } else {
                    sleep(200);
                    waited += 200;
                }
            } catch (Exception ignore) {
                sleep(200);
                waited += 200;
            }
        }
    }

    /** 报到目标：主机地址（收到过就优先单播）+ 全局广播 + 本机网关 + 安卓热点常见网关 */
    private List<InetAddress> helloTargets() {
        List<InetAddress> out = new ArrayList<>();
        InetAddress ra = relayAddr;
        if (ra != null) out.add(ra);        // 知道主机在哪就直发，热点把广播滤了也不怕
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignore) {
        }
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network n = cm.getActiveNetwork();
                LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
                if (lp != null) {
                    for (RouteInfo r : lp.getRoutes()) {
                        if (r.isDefaultRoute() && r.getGateway() != null) out.add(r.getGateway());
                    }
                }
            }
        } catch (Exception ignore) {
        }
        for (String s : new String[]{"192.168.43.1", "192.168.42.1"}) {
            try {
                out.add(InetAddress.getByName(s));
            } catch (Exception ignore) {
            }
        }
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignore) {
        }
    }
}
