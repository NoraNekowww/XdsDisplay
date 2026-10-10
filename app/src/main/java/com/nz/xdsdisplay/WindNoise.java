package com.nz.xdsdisplay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * 本机风噪计。
 *
 * 显示终端装在车把上迎风，麦克风收到的噪声里绝大部分是风噪 —— 风速越大噪声越大，
 * 而风阻 ∝ 风速²，所以这个值可以当作"风阻"的相对指示（不是绝对功率，只用于对比）。
 *
 * 实现：AudioRecord 16kHz 单声道 PCM16，按 32ms 一帧算 RMS → dBFS。
 * 关键：必须用 UNPROCESSED / VOICE_RECOGNITION 音源，普通 MIC 会开 AGC+降噪，
 * 把电平压平，测出来的值就废了。
 */
public class WindNoise {

    private static final String TAG = "WIND";
    private static final int SR = 16000;
    /** dBFS 下限 / 上限，用于映射成 0..100 的"风噪指数" */
    public static final double FLOOR_DB = -70.0;
    public static final double CEIL_DB = -10.0;

    private AudioRecord rec;
    private Thread thread;
    private volatile boolean running;

    /** 平滑后的电平（dBFS，负数，越大越吵） */
    private volatile double db = FLOOR_DB;
    private volatile double dbRaw = FLOOR_DB;
    private volatile double peakDb = FLOOR_DB;
    private volatile long peakMs;
    private volatile String error = "";

    /** 频段过滤 DSP：带通(200-1500Hz) + 5 帧中值 + 过冲抑制 + 150ms 平滑 */
    private final WindDsp dsp = new WindDsp(SR);
    /** 上一帧是否判为风噪 / 是否被抑制（低频车噪、花鼓窄带冲击、过车尖峰） */
    private volatile boolean windFlag;
    private volatile boolean rejectedFrame;

    /** MENU 里显示的档位名（与 WindDsp 对齐：OFF / ON / STRONG） */
    public static final String[] MODE_NAMES = WindDsp.MODE_NAMES;

    public WindNoise(Context ctx) {
        // 只需要 ApplicationContext 取权限状态，这里不做别的
    }

    public double db() { return db; }
    public double peakDb() { return peakDb; }
    public String error() { return error; }
    public boolean isRunning() { return running; }

    /** 当前过滤档位（WindDsp.MODE_LEGACY / MODE_BAND / MODE_BAND_STRONG） */
    public int windMode() { return dsp.mode(); }

    /** 切换档位；顺带清一次滤波器/平滑状态，避免换档瞬间读数跳变 */
    public void setWindMode(int m) {
        dsp.setMode(m);
        dsp.reset();
    }

    /** 上一帧是否判为风噪 */
    public boolean windFlag() { return windFlag; }

    /** 上一帧是否被抑制（低频车噪 / 窄带冲击 / 过车尖峰） */
    public boolean rejectedFrame() { return rejectedFrame; }

    /** 0..100 的相对风阻指数 */
    public int index() {
        return (int) Math.round(toIndex(db));
    }

    public int peakIndex() {
        return (int) Math.round(toIndex(peakDb));
    }

    public static double toIndex(double db) {
        double v = (db - FLOOR_DB) / (CEIL_DB - FLOOR_DB) * 100.0;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    @SuppressLint("MissingPermission")
    public synchronized void start() {
        if (running) return;
        int min = AudioRecord.getMinBufferSize(SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = 2048;
        int bufBytes = Math.max(min, 2048) * 2;

        int[] sources = {
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        for (int src : sources) {
            try {
                AudioRecord r = new AudioRecord(src, SR,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    rec = r;
                    Logs.add(TAG, "风噪计音源 = " + srcName(src));
                    break;
                }
                r.release();
            } catch (Exception e) {
                Logs.add(TAG, "音源 " + srcName(src) + " 不可用：" + e);
            }
        }
        if (rec == null) {
            error = "麦克风不可用";
            Logs.add(TAG, "风噪计启动失败：麦克风初始化不了（没授权 / 被别的 App 占用？）");
            return;
        }
        running = true;
        try {
            rec.startRecording();
        } catch (Exception e) {
            error = "录音启动失败";
            Logs.add(TAG, "风噪计录音启动失败：" + e);
            running = false;
            rec.release();
            rec = null;
            return;
        }
        thread = new Thread(this::loop, "xds-wind");
        thread.setDaemon(true);
        thread.start();
        Logs.add(TAG, "风噪计已启动（16kHz 单声道 RMS + 0.08 平滑 ≈ 0.4s）");
    }

    public synchronized void stop() {
        running = false;
        AudioRecord r = rec;
        rec = null;
        if (r != null) {
            try {
                r.stop();
            } catch (Exception ignore) {
            }
            try {
                r.release();
            } catch (Exception ignore) {
            }
        }
        db = FLOOR_DB;
        dbRaw = FLOOR_DB;
        peakDb = FLOOR_DB;
    }

    private void loop() {
        short[] buf = new short[512];
        long lastLog = 0;
        while (running) {
            AudioRecord r = rec;
            if (r == null) break;
            int n;
            try {
                n = r.read(buf, 0, buf.length);
            } catch (Exception e) {
                if (!running) break;
                Logs.add(TAG, "录音读取异常：" + e);
                break;
            }
            if (n <= 0) continue;

            // 频段过滤：ON/STRONG 档只统计风噪频段（200-1500Hz），
            // 车流低频（<150Hz）与花鼓/过车尖峰被中值 + 过冲抑制压掉
            WindDsp.Result dr = dsp.process(buf, 0, n);
            windFlag = dr.wind;
            rejectedFrame = dr.rejected;

            double d;
            if (dsp.mode() == WindDsp.MODE_LEGACY) {
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    double v = buf[i] / 32768.0;
                    sum += v * v;
                }
                double rms = Math.sqrt(sum / n);
                d = 20.0 * Math.log10(rms + 1e-7);          // dBFS，-90 以下当作静音
                if (d < -90) d = -90;
                dbRaw = d;
                // 平滑得更久一点（32ms 一帧、alpha 0.08 → 时间常数约 0.4s），
                // 骑行时读数不会一跳一跳
                db += (d - db) * 0.08;
            } else {
                // 带内电平：WindDsp 已做过中值 + 过冲抑制 + 150ms 平滑，
                // 这里只把 0..1 归一电平换回 dBFS，index()/峰值语义保持不变
                dbRaw = dr.bandDb;
                d = FLOOR_DB + (double) dr.level * (CEIL_DB - FLOOR_DB);
                db = d;
            }

            long now = System.currentTimeMillis();
            if (d > peakDb || now - peakMs > 3000) {
                peakDb = d;
                peakMs = now;
            } else {
                peakDb -= 0.02;                              // 缓慢回落
                if (peakDb < db) peakDb = db;
            }

            if (now - lastLog > 5000) {
                lastLog = now;
                Logs.add(TAG, String.format(java.util.Locale.CHINA,
                        "风噪 %.1f dB（原始 %.1f，峰值 %.1f，指数 %d，档位 %s%s）",
                        db, dbRaw, peakDb, index(), MODE_NAMES[dsp.mode()],
                        rejectedFrame ? "，本帧抑制" : ""));
            }
        }
    }

    private static String srcName(int src) {
        switch (src) {
            case MediaRecorder.AudioSource.UNPROCESSED: return "UNPROCESSED";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            default: return "MIC";
        }
    }
}
