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

    private DatagramSocket socket;
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

        Logs.add(TAG, "WiFi 直连已就绪（UDP " + PORT + "），连上主机的热点即可自动接管");
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
                lastRxMs = now;
                relayAddr = p.getAddress();     // 记下主机地址，方便回下发控制命令
                if (packets <= 3) {
                    Logs.add(TAG, "WiFi 收到主机遥测 #" + packets
                            + "（来自 " + p.getAddress().getHostAddress() + "）");
                }
                Listener l = listener;
                if (l != null) l.onTelemetry(payload, now);
            } catch (Exception e) {
                if (!running) break;
                Logs.add(TAG, "WiFi 接收异常：" + e);
                sleep(500);
            }
        }
    }

    private void helloLoop() {
        byte[] hello = new byte[]{MAGIC_H[0], MAGIC_H[1], MAGIC_H[2], MAGIC_H[3], (byte) VERSION};
        while (running) {
            try {
                DatagramSocket s = socket;
                if (s != null) {
                    for (InetAddress a : helloTargets()) {
                        try {
                            s.send(new DatagramPacket(hello, hello.length, a, PORT));
                        } catch (Exception ignore) {
                        }
                    }
                }
            } catch (Exception ignore) {
            }
            sleep(2000);
        }
    }

    /** 报到目标：全局广播 + 本机网关（热点下主机就是这个地址）+ 安卓热点常见网关 */
    private List<InetAddress> helloTargets() {
        List<InetAddress> out = new ArrayList<>();
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
