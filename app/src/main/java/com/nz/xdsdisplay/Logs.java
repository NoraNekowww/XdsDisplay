package com.nz.xdsdisplay;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 环形日志，同时写 logcat（tag=XdsDisplay）。 */
public final class Logs {

    public interface Listener {
        void onLogs(List<String> lines);
    }

    private static final String LOG_TAG = "XdsDisplay";
    private static final int MAX = 300;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private static Runnable pending;

    private Logs() {}

    public static void add(String tag, String msg) {
        String line = FMT.format(new Date()) + "  [" + tag + "] " + msg;
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX) LINES.removeFirst();
        }
        try {
            android.util.Log.i(LOG_TAG, tag + " " + msg);
        } catch (Throwable ignore) {}
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
