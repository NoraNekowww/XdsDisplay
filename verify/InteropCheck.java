import com.nz.xdsrelay.BleCodec;
import com.nz.xdsdisplay.CpsParser;
import com.nz.xdsdisplay.LnParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 两个 App 的互通验证：
 *   用「中转软件」的标准蓝牙编码器 BleCodec 打包
 *   → 用「显示终端」的解码器 CpsParser / LnParser 解包
 *   → 逐字段比对。
 * 这一步保证两台手机之间真正能对上话（而不只是各自自洽）。
 */
public class InteropCheck {

    private static int failures = 0;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        cpsRoundTrip();
        lnRoundTrip();

        if (failures == 0) {
            System.out.println("INTEROP CHECKS PASSED  (relay encoder -> display decoder)");
        } else {
            System.out.println("FAILURES: " + failures);
            for (String f : failed) System.out.println("  - " + f);
            System.exit(1);
        }
    }

    private static void cpsRoundTrip() {
        int[] powers = {-32000, -30, 0, 1, 200, 999, 1200, 32767};
        double[] balances = {-1.0, 0.0, 47.5, 50.0, 100.0};
        int[] revs = {0, 1, 1234, 65535, 7};
        int[] events = {0, 1, 5000, 65535, 1023};

        for (int p : powers) {
            for (double b : balances) {
                for (int i = 0; i < revs.length; i++) {
                    byte[] payload = BleCodec.cpsMeasurement(p, b, revs[i], events[i]);

                    CpsParser.Data d = new CpsParser.Data();
                    if (!CpsParser.parse(payload, 0, payload.length, d)) {
                        fail("CPS decode returned false for power=" + p);
                        continue;
                    }

                    eq("cps power p=" + p, p, d.powerW);
                    eq("cps length p=" + p + " b=" + b, payload.length, d.length);

                    boolean expectBalance = BleCodec.INCLUDE_PEDAL_BALANCE && b >= 0;
                    eq("cps hasBalance p=" + p + " b=" + b, expectBalance ? 1 : 0, d.hasBalance ? 1 : 0);
                    if (expectBalance) {
                        near("cps balance p=" + p + " b=" + b, b, d.balanceLeftPct, 0.25);
                    }

                    eq("cps hasCrank p=" + p, 1, d.hasCrank ? 1 : 0);
                    eq("cps crankRevs p=" + p + " rev=" + revs[i], revs[i], d.crankRevs);
                    eq("cps crankEvent p=" + p + " ev=" + events[i], events[i], d.crankEvent);
                }
            }
        }

        // 边界长度：负载短于声明
        CpsParser.Data d = new CpsParser.Data();
        ok("cps rejects short payload", !CpsParser.parse(new byte[]{0x20, 0x00}, 0, 2, d));
        ok("cps rejects null", !CpsParser.parse(null, 0, 0, d));
    }

    private static void lnRoundTrip() {
        float[] speeds = {0f, 0.5f, 5.5f, 12.34f, 25f};
        double[] lats = {0.0, 39.9042, -33.8688, 85.05, -85.05};
        double[] lons = {0.0, 116.4074, -151.2093, 179.9999, -179.9999};
        double[] elevs = {-7.5, 0.0, 43.21, 1234.56, -430.99};
        float[] headings = {-1f, 0f, 90.25f, 180.5f, 359.99f};
        int[] statuses = {0, 1, 2, 3};

        for (float sp : speeds) {
            for (double la : lats) {
                for (double lo : lons) {
                    for (double el : elevs) {
                        for (float hd : headings) {
                            for (int st : statuses) {
                                byte[] payload = BleCodec.lnLocationAndSpeed(
                                        sp, la, lo, el, hd, st, false, true);

                                LnParser.Data d = new LnParser.Data();
                                if (!LnParser.parse(payload, 0, payload.length, d)) {
                                    fail("LN decode returned false");
                                    continue;
                                }

                                near("ln speed", sp, d.speedMps, 1.0 / 256 + 1e-4);
                                near("ln lat", la, d.lat, 1e-7 + 1e-9);
                                near("ln lon", lo, d.lon, 1e-7 + 1e-9);
                                near("ln elev", el, d.elevationM, 0.01 + 1e-6);
                                eq("ln posStatus", st, d.posStatus);
                                ok("ln hasLocation", d.hasLocation);
                                ok("ln positionValid matches status",
                                        d.positionValid() == (st == 1 || st == 2));

                                if (hd >= 0f) {
                                    ok("ln header present", d.hasHeading);
                                    double diff = Math.abs(d.heading - hd);
                                    near("ln heading", 0.0, Math.min(diff, 360.0 - diff), 0.01 + 1e-4);
                                } else {
                                    ok("ln heading absent", !d.hasHeading);
                                }
                            }
                        }
                    }
                }
            }
        }

        // 定位不可用时不该带经纬度
        LnParser.Data d = new LnParser.Data();
        byte[] noFix = BleCodec.lnLocationAndSpeed(0f, 0, 0, 100, -1f,
                BleCodec.LN_POS_STATUS_NO_FIX, false, false);
        LnParser.parse(noFix, 0, noFix.length, d);
        ok("ln no-fix has no location", !d.hasLocation);
        ok("ln no-fix not valid", !d.positionValid());
        near("ln no-fix elevation", 100, d.elevationM, 0.01 + 1e-6);
    }

    // ---------------- 断言工具 ----------------

    private static void eq(String what, int expect, int actual) {
        if (expect != actual) fail(what + ": expect " + expect + " got " + actual);
    }

    private static void near(String what, double expect, double actual, double tol) {
        if (Double.isNaN(actual) || Math.abs(expect - actual) > tol) {
            fail(what + ": expect " + expect + " got " + actual + " (tol " + tol + ")");
        }
    }

    private static void ok(String what, boolean cond) {
        if (!cond) fail(what + ": false");
    }

    private static void fail(String msg) {
        failures++;
        if (failed.size() < 25) failed.add(msg);
    }
}
