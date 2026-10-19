package com.nz.xdsdisplay;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * BLE 直连服务端（显示端）。
 *
 * 为什么角色是反的：实测空口证明中转机（小米 HyperOS）的 GATT 服务端不回 CCCD 写请求，
 * 客户端永远订阅不上 —— 让中转机当服务端这条路是死的（这也是当初放弃 GATT 改广播的原因）。
 * 反过来就绕开了：本机当服务端，中转机当客户端把 20 字节遥测「写」过来，
 * 写特征值是另一条代码路径，HyperOS 的客户端侧正常。
 *
 * 延迟：连接间隔可以请求到 15ms 级，比广播模式（每改一次数据都要停/启一次广播，200ms+）低一个量级。
 */
public class BleServer {

    private static final String TAG = "BLESRV";

    /** 自定义服务/特征值 UUID（短位基于 SIG 基址，方便一眼看出是自家协议） */
    public static final UUID SVC = UUID.fromString("0000ad01-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR = UUID.fromString("0000ad02-0000-1000-8000-00805f9b34fb");
    /**
     * 控制特征值（显示端 → 中转机方向）。
     * 中转机当客户端订阅这个 NOTIFY，显示端的暂停/继续/结束就能从 BLE 这条路发过去，
     * 不必依赖 WiFi/UDP —— 这正是"蓝牙模式点暂停没反应"缺的那条回程通道。
     */
    public static final UUID CHR_CTRL = UUID.fromString("0000ad03-0000-1000-8000-00805f9b34fb");
    /** 标准 CCCD（客户端写它才算订阅上 NOTIFY） */
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** 控制帧第 0 字节：魔数（用来和遥测区分） */
    public static final byte CTRL_MAGIC = (byte) 0xA5;

    public static final UUID CHR_GPX = BleUuids.CHR_GPX;

    // ---- GPX 文件传输协议常量 ----
    public static final byte PKT_TYPE_START = 0x01;
    public static final byte PKT_TYPE_DATA  = 0x02;
    public static final byte PKT_TYPE_END   = 0x03;
    public static final byte PKT_TYPE_ACK   = 0x04;

    public static final byte ACK_READY    = 0x00;
    public static final byte ACK_PROGRESS = 0x01;
    public static final byte ACK_SUCCESS  = 0x02;
    public static final byte ACK_ERROR    = (byte) 0xFF;

    private static final int PAYLOAD_LEN = 20;

    public interface Listener {
        void onTelemetry(byte[] payload, long now);
    }

    public interface GpxListener {
        void onGpxReceived(String fileName, byte[] data);
        void onGpxProgress(int receivedBytes, int totalBytes);
    }

    private static class GpxSession {
        int expectedSize;
        long expectedCrc;
        int totalChunks;
        String fileName = "route.gpx";
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long lastPacketMs;
    }

    private final Context ctx;
    private final Listener listener;
    private volatile GpxListener gpxListener;
    private final Set<String> connected = new HashSet<>();

    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic ctrlChr;
    private BluetoothGattCharacteristic gpxChr;
    private GpxSession currentGpxSession;
    private volatile boolean running;
    private volatile long packets;
    private volatile long ctrlSent;

    public BleServer(Context c, Listener l) {
        this.ctx = c.getApplicationContext();
        this.listener = l;
    }

    public void setGpxListener(GpxListener l) {
        this.gpxListener = l;
    }

    public long packets() {
        return packets;
    }

    public int clientCount() {
        synchronized (connected) {
            return connected.size();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * 给连入的中转机发一条控制命令（暂停/继续/开始/结束）。
     * 返回 true = 至少通知出去一次；没有客户端订阅时返回 false（调用方会退回 UDP）。
     */
    @SuppressLint("MissingPermission")
    public boolean sendControl(int cmd) {
        BluetoothGattServer s = server;
        BluetoothGattCharacteristic c = ctrlChr;
        if (s == null || c == null) return false;
        byte[] v = new byte[]{CTRL_MAGIC, (byte) cmd, 0, 0};
        boolean any = false;
        synchronized (connected) {
            for (String addr : new HashSet<>(connected)) {
                try {
                    BluetoothDevice dev = findConnected(s, addr);
                    if (dev == null) continue;
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        // API 33+ 的四参重载返回状态码(int)，不是 boolean
                        if (s.notifyCharacteristicChanged(dev, c, false, v)
                                == android.bluetooth.BluetoothStatusCodes.SUCCESS) any = true;
                    } else {
                        c.setValue(v);
                        if (s.notifyCharacteristicChanged(dev, c, false)) any = true;
                    }
                } catch (Exception e) {
                    Logs.add(TAG, "控制帧发送失败：" + e);
                }
            }
        }
        if (any) {
            ctrlSent++;
            if (ctrlSent <= 3) Logs.add(TAG, "已下发控制命令 cmd=" + cmd);
        }
        return any;
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice findConnected(BluetoothGattServer s, String addr) {
        try {
            for (BluetoothDevice d : s.getConnectedDevices()) {
                if (d != null && addr.equals(d.getAddress())) return d;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    public synchronized boolean start() {
        if (running) return true;

        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            Logs.add(TAG, "蓝牙没开，BLE 直连服务端未启动");
            return false;
        }

        server = bm.openGattServer(ctx, callback);
        if (server == null) {
            Logs.add(TAG, "打开 GATT 服务端失败");
            return false;
        }

        BluetoothGattCharacteristic chr = new BluetoothGattCharacteristic(
                CHR,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        // 回程控制特征：中转机订阅它收暂停/继续/结束
        ctrlChr = new BluetoothGattCharacteristic(
                CHR_CTRL,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ
                        | BluetoothGattDescriptor.PERMISSION_WRITE);
        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        ctrlChr.addDescriptor(cccd);

        // GPX 路线传输特征：中转机向此特征写入 START, DATA, END 包，本机 NOTIFY 回传 ACK
        gpxChr = new BluetoothGattCharacteristic(
                CHR_GPX,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE
                        | BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattDescriptor gpxCccd = new BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ
                        | BluetoothGattDescriptor.PERMISSION_WRITE);
        gpxCccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        gpxChr.addDescriptor(gpxCccd);

        BluetoothGattService svc = new BluetoothGattService(
                SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        svc.addCharacteristic(chr);
        svc.addCharacteristic(ctrlChr);
        svc.addCharacteristic(gpxChr);
        if (!server.addService(svc)) {
            Logs.add(TAG, "添加 GATT 服务失败");
        }

        advertiser = ad.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Logs.add(TAG, "该机不支持 BLE 广播，BLE 直连不可用");
        } else {
            // 只带服务 UUID，不带设备名 —— 31 字节广播上限很紧，带上名字容易超
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(SVC))
                    .build();
            try {
                advertiser.startAdvertising(settings, data, advCallback);
            } catch (Exception e) {
                Logs.add(TAG, "启动 BLE 直连广播失败：" + e);
            }
        }

        running = true;
        Logs.add(TAG, "BLE 直连服务端已启动，等中转机连入");
        return true;
    }

    @SuppressLint("MissingPermission")
    public synchronized void stop() {
        running = false;
        try {
            if (advertiser != null) advertiser.stopAdvertising(advCallback);
        } catch (Exception ignore) {
        }
        try {
            if (server != null) server.close();
        } catch (Exception ignore) {
        }
        advertiser = null;
        server = null;
        gpxChr = null;
        currentGpxSession = null;
        synchronized (connected) {
            connected.clear();
        }
        Logs.add(TAG, "BLE 直连服务端已停止");
    }

    // ================================================================

    private final AdvertiseCallback advCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Logs.add(TAG, "BLE 直连广播中（可连接），等服务端角色被中转机发现");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Logs.add(TAG, "BLE 直连广播失败 code=" + errorCode);
        }
    };

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (device == null) return;
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                synchronized (connected) {
                    connected.add(device.getAddress());
                }
                Logs.add(TAG, "中转机已连入 " + device.getAddress());
            } else {
                synchronized (connected) {
                    connected.remove(device.getAddress());
                }
                Logs.add(TAG, "中转机断开 " + device.getAddress());
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            // 先回响应（如果是需要响应的那种写），再处理数据，别打乱 ATT 时序
            if (responseNeeded) {
                try {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                } catch (Exception ignore) {
                }
            }
            if (characteristic == null || value == null || value.length == 0) return;

            // 1. 遥测特征值
            if (CHR.equals(characteristic.getUuid())) {
                if (value.length < PAYLOAD_LEN) return;
                byte[] payload = new byte[PAYLOAD_LEN];
                System.arraycopy(value, 0, payload, 0, PAYLOAD_LEN);

                packets++;
                long now = System.currentTimeMillis();
                if (packets <= 3) {
                    Logs.add(TAG, "收到遥测 #" + packets + "（来自 " + device.getAddress() + "）");
                }
                Listener l = listener;
                if (l != null) l.onTelemetry(payload, now);
                return;
            }

            // 2. GPX 路线文件传输特征值
            if (CHR_GPX.equals(characteristic.getUuid())) {
                byte type = value[0];
                if (type == PKT_TYPE_START && value.length >= 12) {
                    int expectedSize = ((value[1] & 0xFF) << 24)
                            | ((value[2] & 0xFF) << 16)
                            | ((value[3] & 0xFF) << 8)
                            | (value[4] & 0xFF);
                    long expectedCrc = (((long) value[5] & 0xFF) << 24)
                            | (((long) value[6] & 0xFF) << 16)
                            | (((long) value[7] & 0xFF) << 8)
                            | ((long) value[8] & 0xFF);
                    int totalChunks = ((value[9] & 0xFF) << 8) | (value[10] & 0xFF);
                    int nameLen = value[11] & 0xFF;
                    String fileName = "route.gpx";
                    if (nameLen > 0 && value.length >= 12 + nameLen) {
                        fileName = new String(value, 12, nameLen, StandardCharsets.UTF_8);
                    }
                    GpxSession session = new GpxSession();
                    session.expectedSize = expectedSize;
                    session.expectedCrc = expectedCrc;
                    session.totalChunks = totalChunks;
                    session.fileName = fileName;
                    session.lastPacketMs = System.currentTimeMillis();
                    currentGpxSession = session;
                    Logs.add(TAG, "开始接收 GPX: " + fileName + " (" + expectedSize + "B, " + totalChunks + "包)");
                    sendGpxAck(device, ACK_READY, 0);
                } else if (type == PKT_TYPE_DATA && value.length >= 3) {
                    GpxSession session = currentGpxSession;
                    if (session != null) {
                        int chunkIdx = ((value[1] & 0xFF) << 8) | (value[2] & 0xFF);
                        session.buffer.write(value, 3, value.length - 3);
                        session.lastPacketMs = System.currentTimeMillis();
                        GpxListener gl = gpxListener;
                        if (gl != null) {
                            gl.onGpxProgress(session.buffer.size(), session.expectedSize);
                        }
                    }
                } else if (type == PKT_TYPE_END) {
                    GpxSession session = currentGpxSession;
                    if (session != null) {
                        byte[] completeData = session.buffer.toByteArray();
                        CRC32 crc = new CRC32();
                        crc.update(completeData);
                        long actualCrc = crc.getValue();
                        if (actualCrc == session.expectedCrc && completeData.length == session.expectedSize) {
                            Logs.add(TAG, "GPX 完整接收并校验通过! 大小=" + completeData.length);
                            sendGpxAck(device, ACK_SUCCESS, completeData.length);
                            GpxListener gl = gpxListener;
                            if (gl != null) {
                                gl.onGpxReceived(session.fileName, completeData);
                            }
                        } else {
                            Logs.add(TAG, "GPX 校验失败: CRC exp=" + session.expectedCrc + " act=" + actualCrc
                                    + " size exp=" + session.expectedSize + " act=" + completeData.length);
                            sendGpxAck(device, ACK_ERROR, 0);
                        }
                        currentGpxSession = null;
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        private void sendGpxAck(BluetoothDevice dev, byte status, int progress) {
            BluetoothGattServer s = server;
            BluetoothGattCharacteristic c = gpxChr;
            if (s == null || c == null || dev == null) return;
            byte[] ack = new byte[]{
                    PKT_TYPE_ACK,
                    status,
                    (byte) ((progress >> 8) & 0xFF),
                    (byte) (progress & 0xFF)
            };
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    s.notifyCharacteristicChanged(dev, c, false, ack);
                } else {
                    c.setValue(ack);
                    s.notifyCharacteristicChanged(dev, c, false);
                }
            } catch (Exception e) {
                Logs.add(TAG, "GPX ACK 发送失败: " + e);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (responseNeeded) {
                try {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                } catch (Exception ignore) {
                }
            }
        }
    };
}
