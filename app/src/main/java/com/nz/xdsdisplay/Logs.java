package com.nz.xdsdisplay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 环形日志：内存 300 行给界面看，同时写 logcat（tag=XdsDisplay），并落盘一份。
 *
 * 落盘是刚需：骑行路上的"中继断流"这种问题，等骑完再连电脑 logcat 早就滚没了，
 * 事后没法复盘。日志按天一个文件（xds-YYYYMMDD.log），超过 512KB 就滚成 -1，
 * 一天最多两片，不怕塞满手机。
 *
 * 写盘走单独的线程 + 有界队列：调用方（可能在 UI 线程/蓝牙回调线程）永不阻塞，
 * 队列满了宁可丢日志也不拖慢主流程。
 */
public final class Logs {

    public interface Listener {
        void onLogs(List<String> lines);
    }

    private static final String LOG_TAG = "XdsDisplay";
    private static final int MAX = 300;
    private static final long FILE_MAX = 512L * 1024L;      // 单文件上限
    private static final int QUEUE_MAX = 2000;              // 待写队列上限（满了丢，不阻塞）

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd", Locale.CHINA);

    private static final LinkedBlockingQueue<String> PENDING = new LinkedBlockingQueue<>(QUEUE_MAX);
    private static volatile File dir;                       // 落盘目录：<files>/logs
    private static volatile File file;                      // 今天这个文件
    private static volatile String day;                     // 今天（yyyyMMdd），跨天换文件
    private static volatile boolean writerStarted;
    private static Runnable pending;

    private Logs() {}

    /**
     * 给落盘一个可写目录。在 Application.onCreate / Activity.onCreate 里调一次即可，重复调无害。
     */
    public static void install(Context ctx) {
        if (ctx == null) return;
        if (dir == null) {
            try {
                dir = new File(ctx.getApplicationContext().getFilesDir(), "logs");
                if (!dir.exists() && !dir.mkdirs()) dir = null;
            } catch (Throwable t) {
                dir = null;
            }
        }
        if (dir != null && !writerStarted) startWriter();
    }

    /** 日志目录绝对路径（没装盘就返回 null），方便界面上显示/导出 */
    public static String dirPath() {
        File d = dir;
        return d == null ? null : d.getAbsolutePath();
    }

    public static void add(String tag, String msg) {
        String line = FMT.format(new Date()) + "  [" + tag + "] " + msg;
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX) LINES.removeFirst();
        }
        try {
            android.util.Log.i(LOG_TAG, tag + " " + msg);
        } catch (Throwable ignore) {}
        toFile(line);
        notifySoon();
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
        notifySoon();
    }

    public static synchronized void register(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static synchronized void unregister(Listener l) {
        LISTENERS.remove(l);
    }

    public static List<String> snapshot() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    // ================================================================
    //  落盘
    // ================================================================

    private static void toFile(String line) {
        if (dir == null) return;
        PENDING.offer(line);        // 队列满就丢这一条，绝不阻塞调用方
    }

    private static void startWriter() {
        writerStarted = true;
        Thread t = new Thread(Logs::writerLoop, "xds-logfile");
        t.setDaemon(true);
        t.start();
    }

    private static void writerLoop() {
        BufferedWriter w = null;
        FileOutputStream fos = null;
        while (true) {
            String line;
            try {
                line = PENDING.poll(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                continue;
            }
            try {
                if (line != null) {
                    if (w == null || !isToday()) {          // 首次 / 跨天 → 换文件
                        closeQuietly(fos);
                        w = null;
                        File f = rollTo();
                        if (f == null) continue;
                        fos = new FileOutputStream(f, true);
                        w = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
                        w.write("=== " + f.getName() + " 会话开始 "
                                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())
                                + " ===\n");
                    }
                    w.write(line);
                    w.write('\n');
                }
                if (w != null) w.flush();
            } catch (Throwable e) {
                // 写盘失败就丢掉句柄，下一轮重建；绝不让日志把 App 拖垮
                closeQuietly(fos);
                w = null;
                fos = null;
                sleep(1000);
            }
        }
    }

    private static boolean isToday() {
        return day != null && day.equals(DAY.format(new Date())) && file != null && file.exists();
    }

    /** 换到今天的文件；顺手做一次大小滚动 */
    private static File rollTo() {
        try {
            String today = DAY.format(new Date());
            File f = new File(dir, "xds-" + today + ".log");
            if (f.exists() && f.length() > FILE_MAX) {
                File old = new File(dir, "xds-" + today + "-1.log");
                if (old.exists() && !old.delete()) {
                    // 删不掉就直接续写，别丢日志
                } else if (!f.renameTo(old)) {
                    // 改名失败也续写
                } else {
                    f = new File(dir, "xds-" + today + ".log");
                }
            }
            day = today;
            file = f;
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void closeQuietly(FileOutputStream fos) {
        try {
            if (fos != null) fos.close();
        } catch (Throwable ignore) {}
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignore) {}
    }

    // ================================================================

    private static void notifySoon() {
        if (pending != null) return;
        pending = new Runnable() {
            @Override
            public void run() {
                pending = null;
                List<String> snapshot = snapshot();
                List<Listener> copy;
                synchronized (Logs.class) {
                    copy = new ArrayList<>(LISTENERS);
                }
                for (Listener l : copy) l.onLogs(snapshot);
            }
        };
        MAIN.postDelayed(pending, 300);
    }
}
