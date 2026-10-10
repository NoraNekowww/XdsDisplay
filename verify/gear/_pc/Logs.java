package com.nz.xdsdisplay;

/** PC 自检用的 Logs 桩：把日志打到 stdout（真机上由 Logs.java 写日志页）。 */
public class Logs {
    public static void add(String tag, String msg) {
        System.out.println("      log [" + tag + "] " + msg);
    }
}
