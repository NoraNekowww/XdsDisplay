package com.nz.xdsdisplay;

import java.util.ArrayList;
import java.util.List;

/** 骑行统计：里程、均速、最高速、骑行时间、轨迹点。 */
public class RideStats {

    private static final double MOVING_THRESHOLD_MPS = 0.8;   // 2.9 km/h 以上算"在骑"
    private static final double TRACK_MIN_GAP_M = 4.0;        // 轨迹点最小间距
    /**
     * 轨迹点上限。4 m 一个点 → 40000 点约 160 km，够一整趟长骑用了。
     * （原来是 6000 点 ≈ 24 km，超过之后会自动丢掉最早的点，看起来就是"轨迹没全程记录"。）
     */
    private static final int TRACK_MAX = 40000;

    private double distanceM;
    private double movingTimeMs;
    private double elapsedTimeMs;
    private double maxSpeedMps;
    private long lastUpdateMs;

    private final List<double[]> track = new ArrayList<>();   // {lat, lon}

    public synchronized void onSpeed(float speedMps, long nowMs) {
        if (lastUpdateMs != 0L) {
            double dt = (nowMs - lastUpdateMs) / 1000.0;
            if (dt > 0 && dt < 5.0) {
                elapsedTimeMs += dt * 1000.0;
                if (speedMps > MOVING_THRESHOLD_MPS) {
                    distanceM += speedMps * dt;
                    movingTimeMs += dt * 1000.0;
                }
            }
        }
        lastUpdateMs = nowMs;
        if (speedMps > maxSpeedMps && speedMps < 60f) maxSpeedMps = speedMps;
    }

    public synchronized void onPosition(double lat, double lon) {
        if (lat == 0.0 && lon == 0.0) return;
        if (!track.isEmpty()) {
            double[] last = track.get(track.size() - 1);
            if (haversine(last[0], last[1], lat, lon) < TRACK_MIN_GAP_M) return;
        }
        track.add(new double[]{lat, lon});
        if (track.size() > TRACK_MAX) {
            track.subList(0, track.size() - TRACK_MAX).clear();
        }
    }

    public synchronized double distanceM() { return distanceM; }
    public synchronized double maxSpeedMps() { return maxSpeedMps; }
    public synchronized double movingTimeMs() { return movingTimeMs; }
    public synchronized double elapsedTimeMs() { return elapsedTimeMs; }

    public synchronized double avgSpeedMps() {
        return movingTimeMs > 500 ? distanceM / (movingTimeMs / 1000.0) : 0;
    }

    public synchronized List<double[]> trackSnapshot() {
        return new ArrayList<>(track);
    }

    /**
     * 给地图用的抽稀快照：整条轨迹都留着（`track` 里是全程），只在点数太多时按步长取点，
     * 保证画轨迹不会越来越卡。首尾点一定保留。
     */
    public synchronized List<double[]> trackSnapshotMax(int maxPoints) {
        int n = track.size();
        if (n == 0) return new ArrayList<>();
        if (maxPoints < 2 || n <= maxPoints) return new ArrayList<>(track);
        int stride = (n + maxPoints - 1) / maxPoints;
        List<double[]> out = new ArrayList<>(maxPoints + 2);
        for (int i = 0; i < n; i += stride) out.add(track.get(i));
        double[] lastPt = track.get(n - 1);
        if (out.get(out.size() - 1) != lastPt) out.add(lastPt);
        return out;
    }

    public synchronized int trackSize() { return track.size(); }

    public synchronized void reset() {
        distanceM = 0;
        movingTimeMs = 0;
        elapsedTimeMs = 0;
        maxSpeedMps = 0;
        lastUpdateMs = 0L;
        track.clear();
    }

    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
