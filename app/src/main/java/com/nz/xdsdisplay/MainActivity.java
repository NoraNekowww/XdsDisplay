package com.nz.xdsdisplay;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import androidx.core.app.ActivityCompat;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 0x701;
    private static final String TAG = "UI";
    /** 旧版「长按圆盘校正轴向」留下的偏好键：功能已删，启动时清掉（见 setupUi） */
    private static final String[] KEY_G_AXIS_LEGACY =
            {"g_axis_mode", "g_axis_mode_land", "g_axis_mode_land_v2"};
    private static final String KEY_VIEW_CHART = "main_view_chart";
    /** 音量下键：页面模式（true=精简页：去掉 WIND/BUMP 卡与 G 力大圆盘，放大 速度/功率/踏频） */
    private static final String KEY_FOCUS_PAGE = "main_focus_page";
    /** 上一版「G 力球 ⇄ WIND-BUMP 二选一」留下的偏好键：语义已废，启动时清掉（见 setupUi） */
    private static final String KEY_SHOW_BALL_LEGACY = "main_show_ball";
    /** 精简页把「功率/速度」与「里程/踏频」两行按这个倍数放大（省下的高度全给重点数字） */
    private static final float FOCUS_ROW_SCALE = 1.6f;
    /** 精简页把底部那条（重要信息双卡）压到多薄：省下的高度给上面的重点大数字 */
    private static final float FOCUS_BOTTOM_SCALE = 0.55f;
    /** 挡位自校准的存档（GearRatio.exportCalibration 的字符串，跨骑行累积） */
    private static final String KEY_GEAR_CAL = "gear_cal_v1";
    private static final String KEY_ORIENT = "orient";
    /** 顶栏 GPS 状态的新鲜度门槛（ms）：超过就不显示星数 */
    private static final long GPS_FRESH_MS = 5000;
    /** 地图一次最多画这么多轨迹点（整条轨迹仍在 RideStats 里，只是抽稀后再画） */
    private static final int TRACK_DRAW_MAX = 8000;

    private MapView map;
    private TextView txtClock, chipStatus, chipSub;
    private TextView txtPower, txtCadence, txtSpeed, txtAvgSpeed, txtDistance;
    private TextView txtMaxSpeed, txtRideTime, txtWindSub;
    private WindBumpBars windBars;
    private BalanceStrip balStrip;      // Power 卡下边缘的左右平衡细条
    private SpeedTape speedTape;        // 速度卡右侧的 HUD 带状刻度
    private GForceView gforce;
    /** 经典布局里三行的原始权重（setupUi 每次都重新膨胀布局，所以每次重新量；横竖屏值不同，不写死） */
    private float wPowerSpeed, wDistCad, wBottom;
    /** 道路颠簸：z 轴加速度局部方差的近期平均（纯算法、不碰 Context，见 BumpMeter） */
    private final BumpMeter bump = new BumpMeter();
    private Button btnFollow, btnZoomIn, btnZoomOut, btnFullscreen, btnView;
    private Button btnPause;
    private Button btnRotate;
    private Button btnMenu;
    /** 已导入的 GPX 路书（null = 没导入） */
    private GpxRoute route;
    /** 下载离线瓦片时菜单键上显示进度 */
    private String menuDefaultText = "MENU";
    private int lastTrackSize = -1;

    // ---- 本机风噪计（麦克风 RMS 当风阻指示）----
    private WindNoise wind;

    // ---- 本机 G 力（线性加速度，圆盘显示）----
    private android.hardware.SensorManager sensors;
    /** 姿态融合：加速度计 + 陀螺仪 → 对地水平的坐标系（纯算法类，自己再算线性加速度）。
     *  旧做法直接用系统 LINEAR_ACCELERATION，测的是**手机平面**的加速度 —— 手机装在把立上
     *  仰着 20~40°，静止时也会读出一个假的 0.5G，刹车/颠簸全被倾角污染。 */
    private final GravityFusion fusion = new GravityFusion();
    /** 设备系 → 屏幕系的换算暂存（省得每帧 new float[]） */
    private final float[] scr = new float[3];
    private double lastLat, lastLon;
    /** 最近一次非零的卫星数（顶栏 GPS 状态用，见 gpsSuffix） */
    private int lastGoodSats;
    private long localBatAt;
    private int cachedLocalBat = -2;
    private boolean hasLastPos;

    private AdvLink ble;
    private UdpLink udp;
    private BleServer srv;

    // ── 中继断流监督（2026-10-10 真骑 7km 的 bug：主机说"转发正常"，仪表这边数据全冻住）──
    private static final String TAG_LINK = "链路";
    private static final long RELAY_STALL_MS = 5000L;      // 中继静默超过这么久 → 判为断流
    private static final long RELAY_RETRY_MS = 20000L;     // 自救重试间隔
    private static final long LINK_HEARTBEAT_MS = 30000L;  // 通道心跳日志周期（骑行回来拿它对账）
    private long relayStallStartMs;    // 0 = 正常；非 0 = 本次断流起始时刻（按最后一包起算）
    private long relayTryMs;           // 上次自救时刻
    private int relayTryCount;         // 本轮自救次数
    private long relayStallTotal;      // 累计断流次数
    private long linkHeartbeatMs;      // 上次通道心跳日志时刻

    /** 中继断流中？给 render() 上屏告警用 */
    private boolean relayStalled() {
        return relayStallStartMs != 0;
    }
    private ChartView chart;
    private long lastChartPushMs;
    private final RideStats stats = new RideStats();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ── 【刷新链看门狗】2026-10-11 真骑冻结（20:17:03 起）的元凶就是下面那条 tick：
    //    它最后一句 ui.postDelayed(this,200) 一旦没执行到，整条刷新链就永远断了 ——
    //    屏上数字僵在上一帧、连断流告警也一起哑掉，而 BLE/风噪这些自己的线程照常跑，
    //    表现就是"看着像正常连接，但数据不动"，只能重开 App。
    //    现在：tick 自身兜异常 + 独立线程盯着补排，App 自己就能救回来。
    private volatile long lastTickAtMs;      // 刷新链最后一次开跑的时刻
    private volatile boolean uiAlive;        // Activity 还活着（onDestroy 置 false）
    private boolean watchdogArmed;
    private long uiBeatMs;                   // 屏上心跳日志的上次时刻
    /** 齿比卡自检线程（只有 adb 带 --ez simgear true 启动时才起） */
    private Thread simThread;
    /** G 力自检（adb 带 --ez simaccel true）：不注册真实传感器，改喂合成加速度，见 simGForceLoop() */
    private boolean simAccel;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.CHINA);

    private volatile CpsParser.Data lastCps;
    private volatile LnParser.Data lastLn;

    private double cadence;
    /** 齿比估算：轮转数 / 曲柄转数，吸附 AD350 齿比表 */
    private final GearRatio gear = new GearRatio();
    private int lastCrankRevs = -1;
    private int lastCrankEvent = -1;
    private long lastCrankChangeMs;
    private int relayBattery = -1;

    private String stateText;   // 状态栏唯一常驻文字：通道文本 / 硬错误 / 已停止
    private int stateColorRes = R.color.muted;
    private long lastTrackRefresh;

    private boolean immersive = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 骑行终端：常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 右上角那个胶囊只显示电量。以前它还挂着"最后一条日志"（风噪 / 接收速率 / 断开重连…），
        // 结果那些东西一直在右上角闪 —— 纯噪音，已摘掉。要看日志走 MENU → LOG。
        UiTheme.load(this);   // 必须在 setupUi（inflate）之前：自定义 View 在构造函数里读颜色
        setupUi();

        // 方向：manifest 里锁竖屏（自动旋转关掉），这里恢复上次手动选的方向
        int wantOrient = prefs().getInt(KEY_ORIENT,
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (wantOrient != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            setRequestedOrientation(wantOrient);
        }

        wind = new WindNoise(this);
        // 风噪过滤档位：上次 MENU 里选的（默认 ON = 只统计风噪频段）
        wind.setWindMode(prefs().getInt(KEY_WIND_MODE, WindDsp.MODE_BAND));
        loadGearCalibration();      // 挡位自校准：接上次学到的地方继续（没存档=出厂齿比）
        sensors = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);

        Logs.install(getApplicationContext());     // 日志落盘：骑行回来还能复盘（Logs.java）
        ble = new AdvLink(this);
        ble.setListener(bleListener);
        Logs.add(TAG_LINK, "日志文件：" + Logs.dirPath() + "（按天切，超 512KB 滚成 -1）");
        // WiFi 直连（主机开热点、本机连上）：收 UDP 遥测，喂给同一个解码器
        udp = new UdpLink(this, (payload, now) -> {
            AdvLink a = ble;
            if (a != null) a.feedExternal(payload, now, AdvLink.CH_WIFI_NAME);
        });
        udp.start();
        // BLE 直连（本机当 GATT 服务端，中转机写过来）：延迟最低的一条
        // 齿比卡的无人值守自检：灌一段 85rpm / 48-22 的合成 CPS+GPC 数据，
        // 在真机上把「档位号 + 齿比」显示出来看：
        //   adb shell am start -n com.nz.xdsdisplay/.MainActivity --ez simgear true
        if (getIntent() != null && getIntent().getBooleanExtra("simgear", false) && simThread == null) {
            simThread = new Thread(this::simGearLoop, "sim-gear");
            simThread.start();
        }
        // G 力自检：把合成加速度灌进**真实**融合 + 绘制链路，linF 逐点打 logcat：
        //   adb shell am start -n com.nz.xdsdisplay/.MainActivity --ez simaccel true
        simAccel = getIntent() != null && getIntent().getBooleanExtra("simaccel", false);
        if (simAccel && simThread == null) {
            simThread = new Thread(this::simGForceLoop, "sim-gforce");
            simThread.start();
        }
        // 刷新链自检（看门狗验证）：启动 8 秒后人为掐掉 tick，模拟 2026-10-11 真骑那次
        // 「刷新链自己死了、只能重开 App」。正常情况下看门狗应在约 4 秒内发现并自动重启刷新。
        //   adb shell am start -n com.nz.xdsdisplay/.MainActivity --ez simstall true
        if (getIntent() != null && getIntent().getBooleanExtra("simstall", false)) {
            ui.postDelayed(() -> {
                Logs.add(TAG_LINK, "【自检】模拟刷新链死亡：取消 tick 排程");
                ui.removeCallbacks(tick);
            }, 8000L);
        }
        srv = new BleServer(this, (payload, now) -> {
            AdvLink a = ble;
            if (a != null) a.feedExternal(payload, now, AdvLink.CH_CONN_NAME);
        });
        // adb 直接触发离线下载（方便无人值守验证）：
        //   adb shell am start -n com.nz.xdsdisplay/.MainActivity --ef lat 39.9042 --ef lon 116.4074 --ei offline 5
        if (getIntent() != null && getIntent().hasExtra("offline")) {
            int km = getIntent().getIntExtra("offline", 5);
            float la = getIntent().getFloatExtra("lat", Float.NaN);
            float lo = getIntent().getFloatExtra("lon", Float.NaN);
            if (!Float.isNaN(la) && !Float.isNaN(lo)) {
                lastLat = la;
                lastLon = lo;
                hasLastPos = true;
            }
            final int radius = km;
            ui.postDelayed(() -> {
                if (hasLastPos) {
                    int zMin = radius >= 40 ? 9 : (radius >= 15 ? 11 : 13);
                    int zMax = radius >= 40 ? 13 : (radius >= 15 ? 14 : 15);
                    startOffline(radius, zMin, zMax);
                }
            }, 2500L);
        }
        ensurePermissions();
        loadSavedRoute();
    }

    /** 加载当前方向的布局并接好所有控件（旋转屏幕时会被再调用一次） */
    private void setupUi() {
        setContentView(R.layout.activity_main);

        map = findViewById(R.id.map);
        // 国内瓦片是 GCJ-02，GPS 是 WGS-84，不转的话轨迹会整体偏一百多米。
        // 但只有在"高德真的可达"时才需要偏移，所以先探测一次再定。
        map.setGcj02(TileCache.tileUsesGcj02());
        TileCache.probeSourceAsync(() -> runOnUiThread(() -> {
            map.setGcj02(TileCache.tileUsesGcj02());
            map.setTrack(stats.trackSnapshot());
        }));
        txtClock = findViewById(R.id.txtClock);
        chipStatus = findViewById(R.id.chipStatus);
        // 首次进入先显示"搜索中"，之后只由通道文本 / 硬错误覆盖
        if (stateText == null) stateText = getString(R.string.sta_scanning);
        chipSub = findViewById(R.id.chipSub);
        txtPower = findViewById(R.id.txtPower);
        txtCadence = findViewById(R.id.txtCadence);
        txtSpeed = findViewById(R.id.txtSpeed);
        txtAvgSpeed = findViewById(R.id.txtAvgSpeed);
        txtDistance = findViewById(R.id.txtDistance);
        txtMaxSpeed = findViewById(R.id.txtMaxSpeed);
        txtRideTime = findViewById(R.id.txtRideTime);
        txtWindSub = findViewById(R.id.txtWindSub);
        windBars = findViewById(R.id.windBars);
        balStrip = findViewById(R.id.balStrip);
        speedTape = findViewById(R.id.speedTape);
        // 数字定宽自检（`--ez simfw true`）：等布局完再量，打印各字段 autosize 实际选中的字号
        if (getIntent() != null && getIntent().getBooleanExtra("simfw", false)) {
            txtPower.postDelayed(this::fixedWidthSelfCheck, 3000);
        }
        applyDigitFont();                       // 数字字体：设置里可选内置方形数字（Orbitron）
        setupNumberFitting();                   // 数字读数不吃 autosize，字号由 fitNum 按 Span 真实宽度挑
        gforce = findViewById(R.id.gforce);
        // 【任务】长按圆盘切轴模式的功能删掉了：太容易误触，而且装法在 pushG() 里已经按
        // 屏幕旋转算好了，不需要人工校正。顺手把旧版留下的轴模式偏好清掉 ——
        // 免得哪天又冒出一个「看不见的镜像覆盖」。
        android.content.SharedPreferences.Editor ce = prefs().edit();
        for (String k : KEY_G_AXIS_LEGACY) ce.remove(k);
        ce.remove(KEY_SHOW_BALL_LEGACY);   // 旧的「球 / 风卡二选一」已废，别留着复发
        ce.apply();
        btnFollow = findViewById(R.id.btnFollow);
        btnZoomIn = findViewById(R.id.btnZoomIn);
        btnZoomOut = findViewById(R.id.btnZoomOut);
        chart = findViewById(R.id.chart);
        btnView = findViewById(R.id.btnView);
        btnView.setOnClickListener(v -> toggleViewMode());

        chipStatus.setOnClickListener(v -> showLogDialog());

        // 暂停键：点按 = 暂停/继续/开始；长按 = 结束记录并导出
        btnPause = findViewById(R.id.btnPause);
        btnPause.setOnClickListener(v -> onPauseButton());
        btnPause.setOnLongClickListener(v -> {
            endRecordingRemote();
            return true;
        });

        // 二级菜单：重连 / 清零 / 通道 / 离线地图 / GPX 路书
        btnMenu = findViewById(R.id.btnMenu);
        menuDefaultText = getString(R.string.btn_menu);
        btnMenu.setOnClickListener(v -> showMenu());

        btnFollow.setOnClickListener(v -> {
            map.setFollow(!map.isFollow());
            updateFollowButton();
        });

        btnZoomIn.setOnClickListener(v -> map.zoomIn());
        btnZoomOut.setOnClickListener(v -> map.zoomOut());

        // FULL / ROTATE 已移入二级菜单（按键条更清爽）
        applyFullscreenMode(immersive);
        updateFollowButton();
        applyColors();
        applyViewMode();
        captureRowWeights();     // 先量下经典布局两行的权重（精简页按倍数放大、切回来要原样还）
        applyFocusPage();        // 页面模式：经典全量 ⇄ 精简页（音量下键切）
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    /** 手动旋转：切横/竖屏并记住（下次打开还是这个方向） */
    private void toggleRotation() {
        boolean land = isLandscape();
        int next = land ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        prefs().edit().putInt(KEY_ORIENT, next).apply();
        setRequestedOrientation(next);
        Logs.add(TAG, "手动旋转 → " + (land ? "竖屏" : "横屏"));
    }

    // ================================================================
    //  配色（Hex 自定义）/ 主视图（地图 ↔ 折线图）
    // ================================================================

    /** 把 Hex 自定义配色铺到整棵视图树上（卡片 / 描边 / 底色 / 文字） */
    private void applyColors() {
        android.view.View rootV = getWindow().getDecorView();
        rootV.setBackgroundColor(UiTheme.c(UiTheme.BG));
        // 布局根（FrameLayout，XML 里写死 @color/bg）也要换，不然底色被它盖住看不见
        android.view.View lay = findViewById(R.id.root);
        if (lay != null) lay.setBackgroundColor(UiTheme.c(UiTheme.BG));
        applyColorsRec(rootV);
    }

    private void applyColorsRec(android.view.View v) {
        if (v instanceof com.google.android.material.card.MaterialCardView) {
            com.google.android.material.card.MaterialCardView card =
                    (com.google.android.material.card.MaterialCardView) v;
            card.setCardBackgroundColor(UiTheme.c(UiTheme.CARD));
            card.setStrokeColor(UiTheme.c(UiTheme.STROKE));
        } else if (v instanceof Button) {
            // 按钮底色跟着卡片色走（Material 的 tonal 底用的是主题里的颜色，改了背景不改按钮会很怪）
            ((Button) v).setBackgroundTintList(ColorStateList.valueOf(UiTheme.buttonFill()));
        } else if (v instanceof TextView) {
            TextView t = (TextView) v;
            int c = t.getCurrentTextColor();
            if (c == getColor(R.color.accent)) t.setTextColor(UiTheme.c(UiTheme.ACCENT));
            else if (c == getColor(R.color.accent2)) t.setTextColor(UiTheme.c(UiTheme.ACCENT2));
            else if (c == getColor(R.color.accent3)) t.setTextColor(UiTheme.c(UiTheme.ACCENT3));
            else if (c == getColor(R.color.muted) || c == getColor(R.color.label)) {
                t.setTextColor(UiTheme.muted());
            } else if (c == getColor(R.color.text)) {
                t.setTextColor(UiTheme.text());
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyColorsRec(g.getChildAt(i));
        }
    }

    /** 地图 / 折线图二选一（点 VIEW 键切，选择记住） */
    private void applyViewMode() {
        boolean chartOn = prefs().getBoolean(KEY_VIEW_CHART, false);
        if (map != null) map.setVisibility(chartOn ? android.view.View.GONE : android.view.View.VISIBLE);
        if (chart != null) chart.setVisibility(chartOn ? android.view.View.VISIBLE : android.view.View.GONE);
        if (btnView != null) btnView.setText(chartOn ? R.string.btn_view_chart : R.string.btn_view_map);
    }

    private void toggleViewMode() {
        setViewMode(!prefs().getBoolean(KEY_VIEW_CHART, false));
    }

    /** 直接切到指定视图（屏幕上 VIEW 按钮用；音量上键走 toggleViewMode） */
    private void setViewMode(boolean chartOn) {
        boolean same = prefs().getBoolean(KEY_VIEW_CHART, false) == chartOn;
        prefs().edit().putBoolean(KEY_VIEW_CHART, chartOn).apply();
        applyViewMode();
        Logs.add(TAG, "主视图 → " + (chartOn ? "折线图" : "地图") + (same ? "（本来就是）" : ""));
    }

    /** 量下经典布局里「功率/速度」行与「里程/踏频」行的权重，作为精简页放大 / 切回的基准 */
    private void captureRowWeights() {
        wPowerSpeed = rowWeight(R.id.rowPowerSpeed);
        wDistCad = rowWeight(R.id.rowDistCad);
        wBottom = rowWeight(R.id.rowBottom);       // 底部那条：左边卡片列 + 右边正方形加速度计
    }

    private float rowWeight(int id) {
        android.view.View v = findViewById(id);
        if (v == null || !(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return 0f;
        return ((LinearLayout.LayoutParams) v.getLayoutParams()).weight;
    }

    /**
     * 页面模式（音量下键切，选择记住）：
     *  · 经典全量（默认）—— 底部左边是 WIND/BUMP 卡 + 重要信息双卡，右边是正方形加速度计。
     *  · 精简页 —— 把 WIND/BUMP 卡和加速度计整块收掉（没有 WIND、没有 BUMP、没有加速度计），
     *              省下的高度放大「功率/速度」与「里程/踏频+档位」两行，底部信息带压薄
     *              —— 速度、功率、踏频(RPM) 因此明显变大，这正是重点信息页。
     */
    private void applyFocusPage() {
        boolean focus = prefs().getBoolean(KEY_FOCUS_PAGE, false);
        hideView(R.id.cardWind, focus);      // WIND/BUMP 卡（两条都在这一张卡里）
        hideView(R.id.gforce, focus);        // 正方形加速度计区
        scaleRow(R.id.rowPowerSpeed, wPowerSpeed, focus ? FOCUS_ROW_SCALE : 1f);
        scaleRow(R.id.rowDistCad, wDistCad, focus ? FOCUS_ROW_SCALE : 1f);
        scaleRow(R.id.rowBottom, wBottom, focus ? FOCUS_BOTTOM_SCALE : 1f);
    }

    private void hideView(int id, boolean hide) {
        android.view.View v = findViewById(id);
        if (v != null) v.setVisibility(hide ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    /** 行高按 weight 放大/还原：base 是经典布局量下来的原始权重，k=1 就是原样还回去 */
    private void scaleRow(int id, float base, float k) {
        android.view.View v = findViewById(id);
        if (v == null || base <= 0f) return;         // 万一是没有这一行的老布局：跳过，不崩
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.weight = base * k;
        v.setLayoutParams(lp);
    }

    private void toggleFocusPage() {
        boolean focus = !prefs().getBoolean(KEY_FOCUS_PAGE, false);
        prefs().edit().putBoolean(KEY_FOCUS_PAGE, focus).apply();
        applyFocusPage();
        Logs.add(TAG, "页面模式 → " + (focus
                ? "精简页（无 WIND/BUMP/G力球，放大 速度/功率/踏频）" : "经典全量"));
    }

    /** Hex 配色对话框：背景 / 卡片 / 描边 / 三个主色 */
    private void showColorDialog() {
        final int[] roles = {UiTheme.BG, UiTheme.CARD, UiTheme.STROKE,
                UiTheme.ACCENT, UiTheme.ACCENT2, UiTheme.ACCENT3};
        final int[] labels = {R.string.theme_bg, R.string.theme_card, R.string.theme_stroke,
                R.string.theme_accent, R.string.theme_accent2, R.string.theme_accent3};
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        final EditText[] edits = new EditText[roles.length];
        for (int i = 0; i < roles.length; i++) {
            TextView lb = new TextView(this);
            lb.setText(getString(labels[i]));
            lb.setTextSize(11f);
            lb.setTextColor(UiTheme.muted());
            box.addView(lb);
            EditText et = new EditText(this);
            et.setText(UiTheme.hex(UiTheme.c(roles[i])));
            et.setSingleLine(true);
            et.setTextSize(17f);
            et.setTypeface(android.graphics.Typeface.MONOSPACE);
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            box.addView(et);
            edits[i] = et;
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.theme_title)
                .setView(sv)
                .setPositiveButton(R.string.theme_apply, (dlg, w) -> {
                    int[] vals = new int[roles.length];
                    for (int i = 0; i < roles.length; i++) {
                        Integer v = UiTheme.parse(edits[i].getText().toString());
                        if (v == null) {
                            Toast.makeText(this, getString(R.string.theme_bad_hex,
                                    edits[i].getText().toString().trim()), Toast.LENGTH_LONG).show();
                            return;
                        }
                        vals[i] = v;
                    }
                    for (int i = 0; i < roles.length; i++) UiTheme.set(this, roles[i], vals[i]);
                    UiTheme.load(this);
                    Logs.add(TAG, "配色已更新 " + UiTheme.hex(vals[0]) + " / " + UiTheme.hex(vals[3]));
                    recreate();     // 自定义 View 都在构造函数里读颜色，重建最省事
                })
                .setNeutralButton(R.string.theme_reset, (dlg, w) -> {
                    UiTheme.reset(this);
                    UiTheme.load(this);
                    Logs.add(TAG, "配色已恢复默认");
                    recreate();
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    // ================================================================
    //  二级菜单（重连 / 清零 / 通道 / 离线地图 / GPX 路书）
    // ================================================================

    private void showMenu() {
        final List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        items.add(getString(R.string.menu_reconnect));
        actions.add(() -> {
            if (ble != null) ble.reconnect();
            Toast.makeText(this, R.string.toast_searching, Toast.LENGTH_SHORT).show();
        });

        items.add(getString(R.string.menu_reset));
        actions.add(this::confirmReset);

        // 原先在按键条上的 FULL / ROTATE 移到这里，放最前面：
        // 横屏下菜单对话框只放得下约 7 行，放末尾会被顶到滚动区外。
        items.add(getString(R.string.menu_screen_fmt, getString(
                immersive ? R.string.scr_full : R.string.scr_window)));
        actions.add(() -> applyFullscreenMode(!immersive));

        items.add(getString(R.string.menu_rotate));
        actions.add(this::toggleRotation);

        items.add(getString(R.string.menu_channel_fmt,
                getString(channelRes(ble == null ? AdvLink.CH_AUTO : ble.channelPref()))));
        actions.add(this::cycleChannel);

        items.add(getString(R.string.menu_offline));
        actions.add(this::showOfflineDialog);

        items.add(getString(route == null
                ? R.string.menu_import_gpx : R.string.menu_import_gpx_replace));
        actions.add(this::pickGpx);

        if (route != null) {
            items.add(getString(R.string.menu_clear_gpx_fmt, route.label()));
            actions.add(this::clearGpx);
        }

        items.add(getString(R.string.menu_wind_fmt,
                WindNoise.MODE_NAMES[wind == null ? 0 : wind.windMode()]));
        actions.add(this::cycleWindMode);

        items.add(getString(R.string.menu_colors));
        actions.add(this::showColorDialog);

        items.add(getString(R.string.menu_digit_fmt,
                getString(digitFont() == DIGIT_SQUARE ? R.string.digit_square : R.string.digit_mono)));
        actions.add(this::cycleDigitFont);

        // 挡位自校准：显示「已学几档 + 整体尺度」，点开能看逐档实测/重置
        items.add(getString(R.string.menu_gear_cal_fmt,
                gear.calibratedCount() > 0
                        ? getString(R.string.gear_cal_state_fmt, gear.calibratedCount(),
                                GearRatio.table().length,
                                String.format(Locale.CHINA, "%.3f", gear.scale()))
                        : getString(R.string.gear_cal_none)));
        actions.add(this::showGearCalDialog);

        items.add(getString(R.string.menu_language_fmt, getString(
                Lang.isChinese(this) ? R.string.lang_en : R.string.lang_zh)));
        actions.add(this::toggleLanguage);

        // 关于：版本 / 作者 / 联系方式。放末尾 —— 平时不点，重要的都在前面。
        items.add(getString(R.string.menu_about));
        actions.add(this::showAboutDialog);

        // 紧凑行布局：14 项也要尽量一屏显示（主题默认 48dp 行会把 SCREEN / ROTATE 顶到滚动区外）
        ArrayAdapter<String> rows = new ArrayAdapter<>(this, R.layout.dialog_menu_row, items);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_title)
                .setAdapter(rows, (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.dlg_close, null)
                .show();
    }

    private static final String KEY_WIND_MODE = "wind_filter_mode";

    /** 风噪过滤档位：OFF（全带，旧行为）→ ON（带通 + 门限）→ STRONG */
    private void cycleWindMode() {
        WindNoise w = wind;
        if (w == null) return;
        int next = (w.windMode() + 1) % WindNoise.MODE_NAMES.length;
        w.setWindMode(next);
        prefs().edit().putInt(KEY_WIND_MODE, next).apply();
        Logs.add(TAG, "风噪过滤 → " + WindNoise.MODE_NAMES[next]);
        Toast.makeText(this, "Wind filter: " + WindNoise.MODE_NAMES[next],
                Toast.LENGTH_SHORT).show();
    }

    // ---- 数字字体（设置里可选「方形数字字体」，内置开源字体 Orbitron Black / OFL 1.1）----
    private static final String KEY_DIGIT_FONT = "digit_font";
    private static final int DIGIT_MONO = 0, DIGIT_SQUARE = 1;

    private int digitFont() {
        return prefs().getInt(KEY_DIGIT_FONT, DIGIT_MONO);
    }

    /** 等宽 ↔ 内置方形数字 循环切换 */
    private void cycleDigitFont() {
        int next = digitFont() == DIGIT_MONO ? DIGIT_SQUARE : DIGIT_MONO;
        prefs().edit().putInt(KEY_DIGIT_FONT, next).apply();
        applyDigitFont();
        Logs.add(TAG, "数字字体 → " + (next == DIGIT_SQUARE ? "SQUARE" : "MONO"));
        Toast.makeText(this, getString(R.string.menu_digit_fmt, getString(
                        next == DIGIT_SQUARE ? R.string.digit_square : R.string.digit_mono)),
                Toast.LENGTH_SHORT).show();
    }

    /** 把选定的数字字体套到所有读数上；方形字体自带重量，不再套 BOLD（合成粗体会糊） */
    private void applyDigitFont() {
        Typeface tf = digitFont() == DIGIT_SQUARE
                ? ResourcesCompat.getFont(this, R.font.orbitron_black) : null;
        final int[] ids = {R.id.txtPower, R.id.txtCadence, R.id.txtSpeed, R.id.txtAvgSpeed,
                R.id.txtDistance, R.id.txtMaxSpeed, R.id.txtRideTime,
                R.id.txtGear, R.id.txtGearSub};
        for (int id : ids) {
            TextView v = findViewById(id);
            if (v == null) continue;            // 横屏 / 竖屏可能没有这张卡
            if (tf == null) v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            else v.setTypeface(tf);
            v.setText(v.getText());             // 让 autosize 用新字体重新量一遍
        }
    }

    /** 速度：整数位原字号，小数点后一位缩到 55%（同基线，像码表），腾出的宽度全给大字。
     *  字段定宽 4 字符（" 9.9" / "24.3"）：位数不变时字符串宽度不变，autosize 就不会忽大忽小；
     *  真到 100.0 km/h 以上超了 4 位，就由 autosize 把整串缩一档。 */
    private CharSequence speedText(double kmh) {
        String s = String.format(Locale.CHINA, "%4.1f", kmh);
        // 小数点后一位缩到 55%（同基线，像码表），而且是**固定格距**渲染：位数一样就不跳
        return num(s, s.indexOf('.'), true);
    }

    /**
     * 数字串一律用 {@link TabularNums} 渲染：每个数字占等宽格子，于是
     * 「201 → 202」这种同字符数的变化，字符串总宽一模一样 —— 屏上不再左右跳。
     * （等宽字体本来就等宽，走一遍没坏处；SQUARE/Orbitron 那种比例数字字体才是刚需。）
     */
    private static CharSequence num(CharSequence s) {
        return num(s, -1, true);
    }

    /** smallFrom：该下标起的字符按 0.55 倍画（< 0 = 整串同大小）；gridSpace：空格是否也占一格 */
    private static CharSequence num(CharSequence s, int smallFrom, boolean gridSpace) {
        if (s == null) return null;
        SpannableString sp = new SpannableString(s);
        sp.setSpan(new TabularNums(smallFrom, gridSpace), 0, s.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /** 数字读数左边补空格到固定字符数：位数变了字符串宽度也不变，autosize 才不会跳字号。
     *  位数真超了（比如 1000W）就原样返回 —— 让 autosize 把整串缩一档去适应。 */
    private static String padNum(String s, int n) {
        if (s.length() >= n) return s;
        StringBuilder b = new StringBuilder(n);
        for (int i = s.length(); i < n; i++) b.append(' ');
        return b.append(s).toString();
    }

    /**
     * 数字定宽自检（`--ez simfw true`）：给每个数字字段灌一遍可能出现的值，
     * 打印 autosize 最终选中的字号 + 该串的实际绘制宽度。
     * 判读：字符数相同的值必须同字号（屏幕上不跳）；只有比占位更长的值才允许字号变小。
     */
    // ---- 数字读数字号自己接管（不再用 autosize）----------------------------------
    // 两个原因要自己算字号：
    //  ① autosize 判定用的是**裸串**宽度：ReplacementSpan（TabularNums）的 getSize 不在它的量宽里，
    //     于是格距版「看着放得下」其实溢出卡片（里程卡实测 697px 的串塞进 503px 的框）；
    //  ② 卡片的行高是固定的，字号还得受高度约束（只管宽度会让数字顶出卡片、上下被裁）。
    // 所以这里关掉这些控件的 autosize，按「宽度 + 高度」双约束二分挑字号；
    // 串长/控件尺寸/字体没变就直接返回，不重复算。
    // minSdk 26，直接用框架的 autosize 接口把 XML 里配的上下限读回来当搜索区间。
    private final java.util.List<TextView> numViews = new java.util.ArrayList<>();
    private final java.util.Map<TextView, float[]> numRange = new java.util.HashMap<>();
    private final java.util.Map<TextView, String> numFitted = new java.util.HashMap<>();

    private void setupNumberFitting() {
        int[] ids = { R.id.txtPower, R.id.txtCadence, R.id.txtSpeed, R.id.txtDistance,
                R.id.txtAvgSpeed, R.id.txtMaxSpeed, R.id.txtRideTime, R.id.txtGear,
                R.id.txtGearSub, R.id.txtClock };
        for (int id : ids) {
            TextView tv = findViewById(id);
            if (tv == null) continue;
            float maxPx = tv.getAutoSizeMaxTextSize();
            float minPx = tv.getAutoSizeMinTextSize();
            if (maxPx <= 0f) maxPx = tv.getTextSize();
            if (minPx <= 0f) minPx = maxPx;
            numRange.put(tv, new float[]{minPx, maxPx});
            tv.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
            numViews.add(tv);
        }
        numFitted.clear();
        findViewById(android.R.id.content).getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        for (TextView tv : numViews) fitNum(tv);
                        return true;
                    }
                });
    }

    /** 挑最大的、既放得宽又放得高的字号（每帧绘制前调用；尺寸/串长没变就直接返回）。 */
    private void fitNum(TextView tv) {
        float[] rng = numRange.get(tv);
        CharSequence s = tv.getText();
        if (rng == null || s == null || s.length() == 0) return;
        int availW = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
        int availH = tv.getHeight() - tv.getPaddingTop() - tv.getPaddingBottom();
        if (availW <= 0 || availH <= 0) return;       // 尺寸还没量出来，下一帧再说
        // 留一点余量：数字贴到卡片边看着就像被裁了（右对齐时最后一位尤其明显）
        availW = (int) (availW * 0.97f);
        availH = (int) (availH * 0.96f);
        String key = availW + "/" + availH + "/" + s + "/"
                + System.identityHashCode(tv.getPaint().getTypeface());
        if (key.equals(numFitted.get(tv))) return;
        android.text.TextPaint p = tv.getPaint();
        float keep = p.getTextSize();
        float lo = rng[0], hi = rng[1], best = rng[0];
        for (int i = 0; i < 8; i++) {                 // 二分 8 次（约 1px 精度）
            float mid = (lo + hi) * 0.5f;
            p.setTextSize(mid);
            boolean fit = TabularNums.measure(p, s) <= availW && p.getFontSpacing() <= availH;
            if (fit) { best = mid; lo = mid; } else { hi = mid; }
        }
        p.setTextSize(keep);
        numFitted.put(tv, key);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, best);
    }

    private void fixedWidthSelfCheck() {
        // 用例里刻意放几组"同字符数、只差数值"的串（201/202、18.1/18.2、114.51/114.52）：
        // 这些正是用户说的"会突然向左跳"的场景，串宽必须一模一样。
        checkField("power", txtPower, new String[]{"  0", " 60", "201", "202", "999", "1000"});
        checkField("cadence", txtCadence, new String[]{"  0", " 85", "120"});
        checkField("speed", txtSpeed, new String[]{" 0.0", " 9.9", "23.5", "23.6", "99.9", "100.0"});
        checkField("distance", txtDistance, new String[]{"  0.00", "114.51", "114.52", "999.99", "1000.00"});
        checkField("gear", findViewById(R.id.txtGear), new String[]{" 1", " 6", "12"});
        checkField("avgSpeed", txtAvgSpeed, new String[]{" 0.0", "18.1", "18.2", "100.0"});
    }

    /** 单个字段逐值量一遍（measure 会触发 autosize 重选字号），打一行日志 */
    private void checkField(String tag, android.widget.TextView tv, String[] vals) {
        if (tv == null) return;
        CharSequence keep = tv.getText();
        int w = Math.max(1, tv.getWidth()), h = Math.max(1, tv.getHeight());
        StringBuilder sb = new StringBuilder("定宽 ").append(tag).append(" 字段宽").append(w).append("px:");
        for (String v : vals) {
            // 和真实渲染同一条路径：数字走 TabularNums（speed 的小数位同样缩放）
            tv.setText(num(v, tag.equals("speed") ? v.indexOf('.') : -1, true));
            numFitted.remove(tv);
            fitNum(tv);   // 走真实路径：先按 Span 宽度定字号，再量行宽
            tv.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.AT_MOST));
            // 量 autosize 排完版之后的**实际行宽**（含 Span 的格子），这才是屏上左右位不跳的判据
            android.text.Layout lay = tv.getLayout();
            float lineW = lay != null ? lay.getLineWidth(0) : tv.getPaint().measureText(v);
            sb.append("  [").append(v).append("] ")
                    .append(String.format(Locale.CHINA, "%.1f", tv.getTextSize())).append("px/串宽")
                    .append(String.format(Locale.CHINA, "%.1f", lineW)).append("px");
        }
        tv.setText(keep);
        Logs.add(TAG, sb.toString());
    }

    /** 中/英切换：用 AppCompat 的 per-app locale（Android 13 以下自动存盘，重启也记得） */
    private void toggleLanguage() {
        String next = Lang.isChinese(this) ? "en" : "zh";
        Lang.setLocale(this, next);
        // 语言变了要把界面（含状态栏文字）整个刷一遍，直接重建最省事
        recreate();
    }

    private static int channelRes(int p) {
        switch (p) {
            case AdvLink.CH_WIFI:
                return R.string.ch_wifi;
            case AdvLink.CH_BLE_CONN:
                return R.string.ch_ble_link;
            case AdvLink.CH_BLE_ADV:
                return R.string.ch_ble_adv;
            default:
                return R.string.ch_auto;
        }
    }

    private static int cmdRes(int cmd) {
        switch (cmd) {
            case UdpLink.CMD_PAUSE:
                return R.string.cmd_pause;
            case UdpLink.CMD_RESUME:
                return R.string.cmd_resume;
            case UdpLink.CMD_END:
                return R.string.cmd_end;
            case UdpLink.CMD_START:
                return R.string.cmd_start;
            default:
                return R.string.cmd_toggle;
        }
    }

    private void confirmReset() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_msg)
                .setPositiveButton(R.string.reset_ok, (d, w) -> {
                    stats.reset();
                    lastCrankRevs = -1;
                    lastCrankEvent = -1;
                    cadence = 0;
                    lastTrackSize = -1;
                    map.setTrack(new ArrayList<>());
                    if (chart != null) chart.clear();
                    Logs.add(TAG, "已清零骑行数据与轨迹");
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    private void cycleChannel() {
        if (ble == null) return;
        int next = (ble.channelPref() + 1) % 4;
        ble.setChannelPref(next);
        Toast.makeText(this, getString(R.string.toast_channel_fmt, getString(channelRes(next))),
                Toast.LENGTH_SHORT).show();
    }

    // ================================================================
    //  GPX 路书
    // ================================================================

    private static final int REQ_GPX = 0x711;

    private void pickGpx() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_GPX);
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_GPX || resultCode != RESULT_OK || data == null) return;
        final android.net.Uri uri = data.getData();
        if (uri == null) return;
        Toast.makeText(this, R.string.toast_loading_gpx, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            GpxRoute r = null;
            String err = null;
            try {
                String name = displayNameOf(uri);
                GpxRoute.copyToFiles(this, uri, name);
                r = GpxRoute.loadSaved(this);
            } catch (Exception e) {
                err = String.valueOf(e);
            }
            final GpxRoute loaded = r;
            final String error = err;
            runOnUiThread(() -> {
                if (loaded == null || loaded.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_gpx_failed_fmt,
                                    error == null ? getString(R.string.toast_gpx_no_points) : error),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                applyRoute(loaded, true);
                Toast.makeText(this, getString(R.string.toast_route_fmt,
                        loaded.label(), loaded.size()), Toast.LENGTH_LONG).show();
            });
        }, "xds-gpx").start();
    }

    private String displayNameOf(android.net.Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null) return n.replaceAll("\\.gpx$", "");
                }
            }
        } catch (Exception ignore) {
        }
        String s = uri.getLastPathSegment();
        return s == null ? "GPX" : s.replaceAll("\\.gpx$", "");
    }

    private void applyRoute(GpxRoute r, boolean fit) {
        route = r;
        if (map != null) {
            map.setRoute(r == null ? null : r.drawPoints(),
                    r == null ? "" : r.label());
            if (fit && r != null) {
                map.post(() -> {
                    map.fitRoute();
                    updateFollowButton();
                });
            }
        }
        Logs.add(TAG, r == null ? "路书已清除"
                : ("路书已载入：" + r.label() + " " + r.size() + " 点"));
    }

    private void clearGpx() {
        GpxRoute.clearSaved(this);
        applyRoute(null, false);
        Toast.makeText(this, R.string.toast_gpx_cleared, Toast.LENGTH_SHORT).show();
    }

    private void loadSavedRoute() {
        new Thread(() -> {
            final GpxRoute r = GpxRoute.loadSaved(this);
            if (r == null) return;
            runOnUiThread(() -> applyRoute(r, false));
        }, "xds-gpx-load").start();
    }

    /**
     * 因为 manifest 里声明了 configChanges（转屏不重建，避免断 BLE），
     * 所以这里必须自己把布局换掉，否则横屏会继续用竖屏布局被拉伸。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        List<double[]> savedTrack = stats.trackSnapshotMax(TRACK_DRAW_MAX);
        lastTrackSize = stats.trackSize();
        LnParser.Data ln = lastLn;
        boolean hadMarker = ln != null && ln.positionValid();
        double savedLat = hadMarker ? ln.lat : Double.NaN;
        double savedLon = hadMarker ? ln.lon : 0.0;

        setupUi();
        fusion.reset();   // 屏幕系跟着转了 90°，设备系→屏幕系的映射整体变了，姿态要重新收敛

        map.setTrack(savedTrack);
        if (hadMarker) map.setPosition(savedLat, savedLon, -1);

        Logs.add(TAG, "方向变化，已重载布局："
                + (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                ? "横屏" : "竖屏"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiAlive = true;
        Logs.add(TAG_LINK, "生命周期 onResume（刷新链保持运行）");
        ui.removeCallbacks(tick);
        ui.post(tick);
        startWatchdog();
        if (ble != null && ble.getState() == BleCentral.State.IDLE) startSources();
        startLocalSensors();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 【2026-10-11 真骑冻结】原来这里 removeCallbacks(tick)：骑行中一旦被系统弹窗/半透明窗口
        // 盖住（Activity 只 pause 不 destroy，屏幕照旧显示仪表），数字就永远冻在上一帧，
        // 连断流告警也一起哑掉，只能重开 App —— 码表不该是这样。
        // 暂停也照样刷：数据一直在来，屏上就必须一直更新。
        Logs.add(TAG_LINK, "生命周期 onPause（刷新链继续运行）");
        saveGearCalibration();      // 退到后台就把校准存档写下来
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 真正看不见了（切走/息屏）才停本地传感器省电；刷新链保留运行，
        // 这样回来时数据与断流监督都是最新的，不会留下一块"假活的旧屏"
        Logs.add(TAG_LINK, "生命周期 onStop（停本地传感器，刷新链保留）");
        stopLocalSensors();
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("xdsdisplay", MODE_PRIVATE);
    }

    // ================================================================
    //  挡位自校准的落盘（跨骑行累积）
    //
    //  统计学校准要靠骑出来的数据喂：一次骑行里很难把 12 个档都骑到
    //  CAL_MIN_N 帧，所以把「簇」原样存进 prefs，下次开 App 接着学。
    //  坏存档不用怕 —— GearRatio.importCalibration 会把不靠谱的项整条丢掉。
    // ================================================================

    /** 存档里最后一次写下去的字符串（一样就不重复写盘） */
    private String lastCalBlob = "";

    private boolean saveGearCalibration() {
        String s = gear.exportCalibration();
        if (s.equals(lastCalBlob)) return false;
        lastCalBlob = s;
        prefs().edit().putString(KEY_GEAR_CAL, s).apply();
        return true;
    }

    private void loadGearCalibration() {
        String s = prefs().getString(KEY_GEAR_CAL, "");
        int got = gear.importCalibration(s);
        if (got > 0) {
            Logs.add(TAG, String.format(Locale.CHINA,
                    "挡位自校准：导入 %d 个簇 → 已学 %d/%d 档，尺度 %.4f",
                    got, gear.calibratedCount(), GearRatio.table().length, gear.scale()));
        } else if (!s.isEmpty()) {
            Logs.add(TAG, "挡位自校准：存档读不出来，按出厂齿比重新学");
        }
    }

    /** MENU 里的「挡位校准」：看每档「理论齿比 → 实测齿比 / 偏差 / 帧数」，也能重置 */
    private void showGearCalDialog() {
        double[] tab = GearRatio.table();
        String[] lab = GearRatio.tableLabels();
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.gear_cal_hint)).append("\n\n");
        sb.append(String.format(Locale.CHINA, "s = %.4f\n", gear.scale()));
        if (gear.scaleAtLimit()) sb.append(getString(R.string.gear_cal_limit)).append("\n");
        sb.append(getString(R.string.gear_cal_cols)).append("\n");
        // 从最轻档往上列，跟车上的手感一致（大字档位号 1 = 最轻）
        for (int i = tab.length - 1; i >= 0; i--) {
            double c = gear.centerRatio(i);      // 判定中心 = 出厂值 × 尺度 × 有限微调
            double m = gear.measured(i);
            if (m <= 0) {
                sb.append(String.format(Locale.CHINA, "%-6s %5.3f→%5.3f  （出厂）    --\n",
                        lab[i], tab[i], c));
            } else {
                sb.append(String.format(Locale.CHINA, "%-6s %5.3f→%5.3f  %+5.1f%%%s %d\n",
                        lab[i], tab[i], c, (m / tab[i] - 1) * 100,
                        gear.trimCapped(i) ? "*" : " ", gear.samples(i)));
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.gear_cal_title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.dlg_close, null)
                .setNeutralButton(R.string.gear_cal_reset, (d, w) -> {
                    gear.resetCalibration();
                    prefs().edit().remove(KEY_GEAR_CAL).apply();
                    lastCalBlob = "";
                    Logs.add(TAG, "挡位校准已重置（回到出厂齿比表）");
                    Toast.makeText(this, R.string.gear_cal_toast_reset, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ================================================================
    //  关于（设置 → 关于）：版本 / 作者 / 联系方式
    // ================================================================

    /** 版本号（读 build.gradle 里的 versionName；读不到就退回 1.0） */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    /** 用系统浏览器打开链接；机器上没有浏览器就把链接拷进剪贴板 */
    private void openUrl(String url) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Exception e) {
            copyToClipboard(url);
            Toast.makeText(this, R.string.about_link_toast, Toast.LENGTH_LONG).show();
        }
    }

    private void copyToClipboard(String s) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("link", s));
    }

    /**
     * MENU 里的「关于」：作者 nz + 开源仓库的 Issues 地址（点了用浏览器打开，长按复制）。
     *
     * <p>刻意用自定义 View 而不是 setMessage()：AlertDialog 的默认消息 TextView 不一定挂了
     * LinkMovementMethod，ClickableSpan 点了没反应；自己塞 TextView 才保证能点。
     */
    private void showAboutDialog() {
        float d = getResources().getDisplayMetrics().density;
        final String issues = getString(R.string.about_issues_url);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (22 * d), (int) (14 * d), (int) (22 * d), 0);

        TextView tApp = new TextView(this);
        tApp.setText(R.string.about_app);
        tApp.setTextSize(17f);
        tApp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tApp.setTextColor(UiTheme.ACCENT);
        box.addView(tApp);

        TextView tVer = new TextView(this);
        tVer.setText(getString(R.string.about_ver_fmt, appVersion()));
        tVer.setTextSize(11.5f);
        tVer.setTextColor(UiTheme.muted());
        box.addView(tVer);

        addAboutRow(box, d, getString(R.string.about_author),
                getString(R.string.about_author_name), false);
        addAboutRow(box, d, getString(R.string.about_issues), issues, true);

        TextView tCar = new TextView(this);
        tCar.setText(R.string.about_car);
        tCar.setTextSize(11.5f);
        tCar.setTextColor(UiTheme.muted());
        tCar.setPadding(0, (int) (12 * d), 0, 0);
        box.addView(tCar);

        TextView tFont = new TextView(this);
        tFont.setText(R.string.about_font);
        tFont.setTextSize(11.5f);
        tFont.setTextColor(UiTheme.muted());
        box.addView(tFont);

        TextView tHint = new TextView(this);
        tHint.setText(R.string.about_hint);
        tHint.setTextSize(11f);
        tHint.setTextColor(UiTheme.muted());
        tHint.setPadding(0, (int) (10 * d), 0, 0);
        box.addView(tHint);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_about)
                .setView(box)
                .setPositiveButton(R.string.dlg_close, null)
                .setNeutralButton(R.string.about_open_issues, (dg, w) -> openUrl(issues))
                .show();
    }

    /** 「标签　值」一行；link=true 时值可点（打开链接）、长按（复制） */
    private void addAboutRow(LinearLayout box, float d, String label, String value, boolean link) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (10 * d), 0, 0);

        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(13f);
        lb.setTextColor(UiTheme.muted());
        lb.setMinWidth((int) (58 * d));
        row.addView(lb);

        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(14f);
        tv.setTextColor(link ? UiTheme.ACCENT : UiTheme.text());
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        if (link) {
            tv.setPaintFlags(tv.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            tv.setOnClickListener(v -> openUrl(value));
            tv.setOnLongClickListener(v -> {
                copyToClipboard(value);
                Toast.makeText(this, R.string.about_copied, Toast.LENGTH_SHORT).show();
                return true;
            });
        }
        row.addView(tv);
        box.addView(row);
    }

    @Override
    protected void onDestroy() {
        Logs.add(TAG_LINK, "生命周期 onDestroy");
        uiAlive = false;             // 刷新链停排（视图已经没了，别再碰）
        watchdogArmed = false;       // 看门狗线程退出
        ui.removeCallbacks(tick);
        if (udp != null) udp.stop();
        if (srv != null) srv.stop();
        super.onDestroy();
    }

    private void updateFollowButton() {
        btnFollow.setText(map.isFollow() ? R.string.btn_follow : R.string.btn_free);
    }

    // ================================================================
    //  全屏（像码表一样，隐藏状态栏与导航栏）
    // ================================================================

    @SuppressWarnings("deprecation")
    private void applyFullscreenMode(boolean on) {
        immersive = on;
        if (btnFullscreen != null) btnFullscreen.setText(on ? R.string.btn_full : R.string.btn_window);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (on) {
                    c.hide(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                }
            }
        } else {
            android.view.View decor = getWindow().getDecorView();
            if (on) {
                decor.setSystemUiVisibility(
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                decor.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        }
        Logs.add(TAG, on ? "进入全屏" : "退出全屏");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive) applyFullscreenMode(true);
    }

    // ================================================================
    //  权限
    // ================================================================

    private List<String> neededPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need.add(Manifest.permission.BLUETOOTH_SCAN);
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
            need.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> missing = new ArrayList<>();
        for (String p : need) {
            if (ActivityCompat.checkSelfPermission(this, p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        return missing;
    }

    private void ensurePermissions() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
        if (a == null) {
            Logs.add(TAG, "本机没有蓝牙");
        } else if (!a.isEnabled()) {
            Logs.add(TAG, "蓝牙未开启 —— 终端需要蓝牙才能接收中继数据");
        }

        List<String> missing = neededPermissions();
        // 麦克风（风噪计）单独一组：拒绝了也只是风噪卡片不可用，不影响收数据
        List<String> mic = micPermissions();
        if (missing.isEmpty() && mic.isEmpty()) {
            startSources();
            return;
        }
        List<String> ask = new ArrayList<>(missing);
        ask.addAll(mic);
        ActivityCompat.requestPermissions(this, ask.toArray(new String[0]), REQ_PERMS);
    }

    private List<String> micPermissions() {
        List<String> need = new ArrayList<>();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        return need;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        if (micPermissions().isEmpty()) {
            Logs.add(TAG, "麦克风权限已授权，风噪计可用");
        } else {
            Logs.add(TAG, "⚠ 没给麦克风权限：风噪（风阻）卡片会显示 --");
        }
        if (neededPermissions().isEmpty()) {
            Logs.add(TAG, "权限齐备");
            startSources();
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_title)
                    .setMessage(R.string.perm_msg)
                    .setPositiveButton(R.string.perm_settings, (d, w) -> {
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignore) {}
                    })
                    .setNegativeButton(R.string.perm_later, null)
                    .show();
        }
    }

    // ================================================================
    //  BLE 回调
    // ================================================================

    // ================================================================
    //  数据源
    // ================================================================

    private static String channelShort(int p) {
        switch (p) {
            case AdvLink.CH_WIFI:
                return "WiFi";
            case AdvLink.CH_BLE_CONN:
                return "BLE";
            case AdvLink.CH_BLE_ADV:
                return "ADV";
            default:
                return "AUTO";
        }
    }

    private void startSources() {
        if (ble != null) ble.start();
        if (udp != null) udp.start();
        // BLE 直连要等 BLUETOOTH_ADVERTISE 授权后才能起，否则广播会被 SecurityException 拒掉
        if (srv != null && !srv.isRunning()) srv.start();
    }

    // ================================================================
    //  本机传感器：风噪（麦克风）+ G 力（加速度）
    // ================================================================

    /** 想跑的采样周期（µs）：2500µs = 400Hz —— 5T 的 BMI160 在 sensorservice 里列的就是 maxRate=400Hz，
     *  但 HAL 把 minDelay 报成 5000µs（200Hz），所以不能只信 getMinDelay()。 */
    private static final int TARGET_US = 2500;

    private void startLocalSensors() {
        if (simAccel) return;      // G 力自检：不注册真实传感器（否则合成/真实两路数据打架）
        if (wind != null && !wind.isRunning()) wind.start();

        android.hardware.SensorManager sm = sensors;
        if (sm == null) return;
        android.hardware.Sensor acc = pickSensor(sm, android.hardware.Sensor.TYPE_ACCELEROMETER);
        android.hardware.Sensor gyr = pickSensor(sm, android.hardware.Sensor.TYPE_GYROSCOPE);
        if (acc == null) {
            Logs.add(TAG, "本机没有加速度传感器，G 力不可用");
            return;
        }
        fusion.reset();
        // 【任务】高采样率读取：SENSOR_DELAY_GAME 只有 50Hz，G 球跟随与颠簸都偏钝。
        // 真机实测（一加 5T / BMI160）：加速度计与陀螺仪都从 50Hz 提到 ≈394Hz（≈8 倍）；
        // 见下面 rateTick 每 2s 打的「SENSOR 实测」行 —— 速率只认实测，不认注册参数。
        int accUs = registerHighRate(sm, acc, TARGET_US);
        int gyrUs = gyr == null ? 0 : registerHighRate(sm, gyr, TARGET_US);
        Logs.add(TAG, gyr != null
                ? "G 力传感器：加速度计 请求" + hzOf(accUs) + " / 陀螺仪 请求" + hzOf(gyrUs)
                        + "（对地水平化，含 z 轴）"
                : "G 力传感器：只有加速度计（无陀螺仪，水平化会慢一些）");
        Log.i(TAG, "SENSOR 注册 accel=" + accUs + "us(" + hzOf(accUs)
                + ", " + acc.getName() + " 自报 " + hzOf(sensorDelayUs(acc)) + ") gyro=" + gyrUs
                + "us(" + hzOf(gyrUs)
                + (gyr == null ? "" : ", " + gyr.getName() + " 自报 " + hzOf(sensorDelayUs(gyr)))
                + ")");
        startRateMeter();
    }

    /** 注册到能给的最高速率：先要 wantUs；被拒（false）或抛异常 → 退回传感器自报的 minDelay。
     *  返回实际请求到的周期（µs）。try/catch 是保命的：高采样率若被系统拒（SecurityException），
     *  绝不能让它把 App 崩在 onResume 里。 */
    private int registerHighRate(android.hardware.SensorManager sm,
                                 android.hardware.Sensor s, int wantUs) {
        if (s == null) return 0;
        int[] tries = { wantUs, sensorDelayUs(s) };
        for (int us : tries) {
            if (us <= 0) continue;
            try {
                if (sm.registerListener(sensorListener, s, us)) return us;
            } catch (Exception e) {
                Log.i(TAG, "SENSOR 注册 " + us + "us 被拒：" + e.getClass().getSimpleName());
            }
        }
        Logs.add(TAG, "传感器注册失败：" + s.getName());
        return 0;
    }

    /** 同类型多个实例里挑最快的一个：优先非唤醒型（省电，不把 AP 钉醒），同类型再比自报 minDelay */
    private android.hardware.Sensor pickSensor(android.hardware.SensorManager sm, int type) {
        android.hardware.Sensor best = null;
        for (android.hardware.Sensor s : sm.getSensorList(type)) {
            Log.i(TAG, "SENSOR 候选 " + s.getName() + " minDelay=" + s.getMinDelay()
                    + "us wakeUp=" + s.isWakeUpSensor());
            if (best == null) { best = s; continue; }
            boolean bWake = best.isWakeUpSensor(), sWake = s.isWakeUpSensor();
            if (bWake && !sWake) { best = s; continue; }               // 非唤醒型优先
            if (bWake == sWake) {
                int bd = best.getMinDelay(), sd = s.getMinDelay();
                if (sd > 0 && (bd <= 0 || sd < bd)) best = s;          // 自报间隔更小 = 更快
            }
        }
        return best != null ? best : sm.getDefaultSensor(type);
    }

    /** 传感器自报的最小采样间隔（µs）；0/负 = 不自报 → 2000µs 兜底 */
    private static int sensorDelayUs(android.hardware.Sensor s) {
        int us = (s == null) ? 0 : s.getMinDelay();
        return us > 0 ? us : 2000;          // 2000µs = 500Hz
    }

    /** µs 采样间隔 → 人读的 Hz（只为日志） */
    private static String hzOf(int us) {
        return us <= 0 ? "--" : String.format(java.util.Locale.US, "%.0fHz", 1e6 / us);
    }

    // ---- 采样率自证：每 2s 用**实测**样本数算一次 Hz，落 logcat（+ 日志面板前几行）----
    // 「确保读到高速率」这句话只能靠实测 Hz 证明，不能靠注册参数推断。
    // 顺带把 BUMP（z 轴局部标准差）一起打出来：换采样率后它到底是不是噪声地板抬了，一眼能看出来。
    private long accN, gyrN, rateLastNs;
    private int rateRounds;
    /**
     * 加速度计**时间戳**间隔体检。融合的姿态修正增益按 dt 归一化（α = 1 − e^(−dt/τ)），
     * dt 必须是真值：有些 HAL 在 400Hz 下会把时间戳量化/复制成同一个值（dt=0）或抖得很大，
     * 那样增益就失真了。所以每 2s 连 dt 的最小/均值/最大一起打出来。
     */
    private long lastAccTs, accDtMin, accDtMax, accDtSum;
    private int accDtN;
    private final Runnable rateTick = new Runnable() {
        @Override
        public void run() {
            long now = System.nanoTime();
            if (rateLastNs != 0) {
                double dt = (now - rateLastNs) / 1e9;
                if (dt > 0.5) {
                    String line = String.format(java.util.Locale.US,
                            "SENSOR 实测 加速度 %.0fHz / 陀螺仪 %.0fHz / BUMP %.2f m/s²"
                                    + " / 时间戳间隔 %.2f/%.2f/%.2fms(最小/均值/最大)",
                            accN / dt, gyrN / dt, bump.level(),
                            accDtMin / 1e6,
                            (accDtN > 0 ? (double) accDtSum / accDtN : 0) / 1e6,
                            accDtMax / 1e6);
                    Log.i(TAG, line);
                    if (rateRounds < 3) Logs.add(TAG, line);   // 面板只写前几次，别刷屏
                    rateRounds++;
                }
            }
            rateLastNs = now;
            accN = 0;
            gyrN = 0;
            lastAccTs = 0;
            accDtMin = 0;
            accDtMax = 0;
            accDtSum = 0;
            accDtN = 0;
            if (rateRounds < 20) ui.postDelayed(this, 2000L);   // logcat 采 20 轮（≈40s）后静音
        }
    };

    private void startRateMeter() {
        ui.removeCallbacks(rateTick);
        rateRounds = 0;
        rateLastNs = 0;
        accN = 0;
        gyrN = 0;
        lastAccTs = 0;
        accDtMin = 0;
        accDtMax = 0;
        accDtSum = 0;
        accDtN = 0;
        ui.postDelayed(rateTick, 2000L);
    }

    private void stopLocalSensors() {
        if (wind != null) wind.stop();
        ui.removeCallbacks(rateTick);
        if (sensors != null) sensors.unregisterListener(sensorListener);
    }

    private final android.hardware.SensorEventListener sensorListener =
            new android.hardware.SensorEventListener() {
                @Override
                public void onSensorChanged(android.hardware.SensorEvent e) {
                    if (e.sensor.getType() == android.hardware.Sensor.TYPE_GYROSCOPE) {
                        // 角速度：同样先换到屏幕系。这里 4 种旋转映射都是绕屏幕 z 轴的纯旋转
                        // （行列式 +1），所以角速度矢量可以和加速度用同一套换算。
                        gyrN++;                    // 采样率自证用（rateTick 每 2s 折算一次 Hz）
                        toScreen(e.values);
                        fusion.setGyro(scr[0], scr[1], scr[2], e.timestamp);
                        return;
                    }
                    accN++;                        // 采样率自证用
                    // dt 体检：姿态修正按 dt 归一化，时间戳若被 HAL 量化/复制，增益就失真
                    if (lastAccTs != 0) {
                        long d2 = e.timestamp - lastAccTs;
                        if (accDtN == 0 || d2 < accDtMin) accDtMin = d2;
                        if (d2 > accDtMax) accDtMax = d2;
                        accDtSum += d2;
                        accDtN++;
                    }
                    lastAccTs = e.timestamp;
                    // 道路颠簸：喂**原始** z。窗口内先减均值，含不含重力结果一样（见 BumpMeter）
                    bump.add(e.values[2], e.timestamp);
                    toScreen(e.values);
                    // 喂**含重力**的原始加速度，让融合自己去估姿态、再扣重力算线性加速度
                    fusion.setAccel(scr[0], scr[1], scr[2], e.timestamp);
                    pushG();
                }

                @Override
                public void onAccuracyChanged(android.hardware.Sensor s, int a) {
                }
            };

    /** 屏幕旋转的 4 个方向，把设备坐标换成屏幕坐标（x 右 / y 上） */
    @SuppressWarnings("deprecation")
    private int screenRotation() {
        try {
            android.view.Display d = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? getDisplay() : getWindowManager().getDefaultDisplay();
            return d == null ? 0 : d.getRotation();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 设备坐标 → 屏幕坐标（x 右 / y 上 / z 出屏）。横竖屏映射与原 pushG 完全一致。 */
    private void toScreen(float[] v) {
        float x = v[0], y = v[1], z = v[2];
        switch (screenRotation()) {
            // AOSP Display.getRotation() javadoc：设备**逆时针**转 90° ⇒ ROTATION_90，
            // 此时设备 +x 指向世界上方（= 屏幕上方）、+y 指向世界左方（= 屏幕左方）
            //      → 屏幕右 = -y、屏幕上 = +x
            case android.view.Surface.ROTATION_90:
                scr[0] = -y;
                scr[1] = x;
                break;
            case android.view.Surface.ROTATION_180:
                scr[0] = -x;
                scr[1] = -y;
                break;
            // 270° 与 90° 正好差 180°：符号全反
            case android.view.Surface.ROTATION_270:
                scr[0] = y;
                scr[1] = -x;
                break;
            default:
                scr[0] = x;
                scr[1] = y;
                break;
        }
        scr[2] = z;   // 绕屏幕法线的转动，z 不变
    }

    /**
     * 推给 G 力圆盘：融合出来的**对地水平**线性加速度（单位 g）。
     * 圆盘的 x 轴 = 地面系里的"右"、y 轴 = 地面系里的"前"（= 屏幕上在水平面的投影）、
     * z = 竖直。这样静止时读数是 0，刹车/加速/过弯/颠簸才出读数 —— 跟手机装多仰无关。
     */
    private void pushG() {
        if (gforce == null) return;
        gforce.push((float) fusion.linR(), (float) fusion.linF(), (float) fusion.linU());
    }

    // ================================================================
    //  暂停控制（下发到中继；中继再把真实状态随遥测回显）
    // ================================================================

    private boolean relayStateFresh() {
        AdvLink a = ble;
        return a != null && a.relayStateFresh();
    }

    private boolean relayPausedNow() {
        AdvLink a = ble;
        return a != null && a.relayStateFresh() && a.relayPaused;
    }

    private boolean relayRecordingNow() {
        AdvLink a = ble;
        return a == null || !a.relayStateFresh() || a.relayRecording;
    }

    /** 点按：按中继当前状态决定发暂停 / 继续 / 开始（WiFi / BLE 都走 sendCtrl） */
    private void onPauseButton() {
        int cmd;
        if (!relayRecordingNow()) cmd = UdpLink.CMD_START;
        else if (relayPausedNow()) cmd = UdpLink.CMD_RESUME;
        else cmd = UdpLink.CMD_PAUSE;

        boolean ok = sendCtrl(cmd);
        if (!ok) {
            Toast.makeText(this, (udp == null && srv == null)
                            ? R.string.toast_wifi_not_ready : R.string.toast_send_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(relayStateFresh()
                        ? R.string.toast_sent_fmt : R.string.toast_sent_broadcast,
                        getString(cmdRes(cmd))), Toast.LENGTH_SHORT).show();
        render();
    }

    /** 长按：结束中继那边的记录并导出 FIT（会弹确认） */
    private void endRecordingRemote() {
        if (udp == null && srv == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.end_title)
                .setMessage(R.string.end_msg)
                .setPositiveButton(R.string.end_ok, (d, w) -> {
                    boolean ok = sendCtrl(UdpLink.CMD_END);
                    Toast.makeText(this, ok
                                    ? getString(R.string.toast_sent_fmt, getString(R.string.cmd_end))
                                    : getString(R.string.toast_send_end_fail),
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    private final BleCentral.Listener bleListener = new BleCentral.Listener() {

        @Override
        public void onState(BleCentral.State state, String detail) {
            // 状态栏单一写入点：只有"通道文本 / 硬错误 / 已停止"能上屏。
            // 扫描、连接、发现服务、订阅、重连这些过渡态一律只改颜色不改文字 ——
            // 否则 "断开，重连中…" / "搜索中继设备…" 会一直在状态栏里闪。
            switch (state) {
                case READY:  stateText = detail; stateColorRes = R.color.ok;   break;
                case ERROR:  stateText = detail; stateColorRes = R.color.err;  break;
                case IDLE:   stateText = detail; stateColorRes = R.color.muted; break;
                default:     stateColorRes = R.color.warn; break;
            }
            ui.post(MainActivity.this::render);
        }

        @Override
        public void onCps(CpsParser.Data data) {
            lastCps = data;
            updateCadence(data);
            ui.post(MainActivity.this::render);
        }

        @Override
        public void onLn(LnParser.Data data) {
            lastLn = data;
            long now = System.currentTimeMillis();

            // 中继没在记录（暂停 / 已结束）时，本机的里程/均速/运动时间也跟着冻结，
            // 免得仪表和最终 FIT 对不上。地图上的实时位置照旧更新（只是轨迹不长新点）。
            boolean paused = !relayRecordingNow();
            if (data.hasSpeed && !paused) {
                stats.onSpeed(data.speedMps, now);
            }
            if (data.positionValid()) {
                if (!paused) stats.onPosition(data.lat, data.lon);
                map.setPosition(data.lat, data.lon, -1);
                lastLat = data.lat;
                lastLon = data.lon;
                hasLastPos = true;
            }
            ui.post(MainActivity.this::render);
        }

        @Override
        public void onBattery(int percent) {
            relayBattery = percent;
        }
    };

    /** 踏频（功率计直给优先，其次用曲柄事件推算）→ 顺带喂齿比估算。 */
    private void updateCadence(CpsParser.Data d) {
        long now = System.currentTimeMillis();

        // 中转机已经把功率计的真实踏频直接带过来了，优先用它 —— 曲柄推算要等凑满一圈，
        // 会滞后 0.7~1.3 秒，看起来就是"功率准、踏频不同步"。
        if (d.hasCadence) {
            cadence = d.cadenceRpm;
            lastCrankChangeMs = now;
        } else if (d.hasCrank && lastCrankRevs >= 0) {
            int dRev = (d.crankRevs - lastCrankRevs) & 0xFFFF;
            int dTime = (d.crankEvent - lastCrankEvent) & 0xFFFF;   // 1/1024 s
            if (dRev > 0 && dRev <= 4 && dTime > 0) {
                cadence = dRev * 60.0 * 1024.0 / dTime;
                lastCrankChangeMs = now;
            }
        }
        if (d.hasCrank) {
            lastCrankRevs = d.crankRevs;
            lastCrankEvent = d.crankEvent;
        }

        // 齿比 = 轮转速 / 曲柄转速 = (车速 / 轮周长 × 60) / 踏频。
        // 轮转速只能从 GPS 车速 + 车轮周长来（2025 款 700c，中继没有轮速计）；
        // 用近几帧「车速/踏频」比值的中位数去吸附 AD350 齿比表 —— 换挡几帧内就跟上，
        // 踏频 = 0（滑行）时保持上一稳定档，不会瞎跳到 12 档。都在 GearRatio 里做。
        double v = 0;
        LnParser.Data gps = lastLn;
        if (gps != null && gps.hasSpeed && now - gps.timestampMs < 5000) v = gps.speedMps;
        // 功率 < 20W（空踩 / 溜腿）时，踏频不作为齿比依据 —— 由 GearRatio 里的 PWR_MIN 把关
        gear.update(v, cadence, d.powerW);
    }

    /**
     * 顶栏 Link 卡右侧那段 GPS 信号状态：" · GPS 9" / " · GPS RTK" / " · GPS --"。
     *
     * 星数偶尔会掉到 0（中继那边 GNSS 回调的抖动，见 AdvLink 的注释），所以留一份最近一次
     * 非零的星数；只有定位真的断档（超过 GPS_FRESH_MS 没有定位包）才显示 --。
     */
    private String gpsSuffix(long now) {
        LnParser.Data ln = lastLn;
        if (ln == null || now - ln.timestampMs >= GPS_FRESH_MS) {
            lastGoodSats = 0;
            return "  ·  " + getString(R.string.chip_gps_fmt, "--");
        }
        if (ln.sats > 0) lastGoodSats = ln.sats;
        if (ln.fixQuality >= 4) return "  ·  " + getString(R.string.chip_gps_fmt, "RTK");
        return "  ·  " + getString(R.string.chip_gps_fmt,
                lastGoodSats > 0 ? String.valueOf(lastGoodSats) : "--");
    }

    // ================================================================
    //  渲染
    // ================================================================

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long nowMs = System.currentTimeMillis();
            lastTickAtMs = nowMs;
            try {
                superviseRelay(nowMs);      // 中继断流：先判定 + 自救，再刷屏
                render();
                // 挡位校准存档：每分钟写一次（内容没变就不写盘），App 被系统杀掉也不丢
                if (nowMs - lastCalSaveMs > 60000L) {
                    lastCalSaveMs = nowMs;
                    saveGearCalibration();
                }
                // 【屏上心跳】每 5 秒记一行"界面真正刷出来的值"：
                // 下次骑行回来一看就知道是"刷新链停了"还是"刷新在跑、但数据是旧的"
                if (nowMs - uiBeatMs > 5000L) {
                    uiBeatMs = nowMs;
                    CpsParser.Data c = lastCps;
                    LnParser.Data l = lastLn;
                    Logs.add(TAG_LINK, "刷新心跳 · 屏上 功率="
                            + (c == null ? "-" : c.powerW + "W")
                            + " 踏频=" + String.format(Locale.CHINA, "%.0f", cadence)
                            + " 速度=" + (l == null || !l.hasSpeed ? "-"
                                    : String.format(Locale.CHINA, "%.1f", l.speedMps * 3.6))
                            + " 星=" + (l == null ? "-" : l.sats)
                            + " 中继" + String.format(Locale.CHINA, "%.1f",
                                    ago(nowMs, ble.lastAnyMs())) + "s 前");
                }
            } catch (Throwable t) {
                // 兜住任何一帧的异常：原来是裸调用，抛一次这条链就永远断了（20:17 真骑冻结的根因）
                Logs.add(TAG_LINK, "⚠ 刷新异常（已兜住，继续刷新）："
                        + android.util.Log.getStackTraceString(t));
            } finally {
                if (uiAlive) ui.postDelayed(this, 200L);   // 无论出什么事，下一拍一定排上
            }
        }
    };

    /**
     * 刷新链看门狗：一个独立线程，每秒看一眼。刷新链停摆超过 3 秒就立刻补排。
     * 「界面突然不刷新、只能重开 App」这毛病从此自愈（2026-10-11 真骑 20:17 冻结实测）。
     */
    private void startWatchdog() {
        if (watchdogArmed) return;
        watchdogArmed = true;
        Thread t = new Thread(() -> {
            boolean stallLogged = false;
            while (watchdogArmed) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (!watchdogArmed) return;
                long gap = System.currentTimeMillis() - lastTickAtMs;
                if (gap > 3000L) {
                    if (!stallLogged) {
                        stallLogged = true;
                        // 停摆时把主线程栈抓进日志 —— 下次再冻，直接看这段就知道是"卡在某个锁里"
                        // 还是"根本没被排上"，不用再靠猜（2026-10-11 那次就是只能猜）
                        StackTraceElement[] st = Looper.getMainLooper().getThread().getStackTrace();
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < Math.min(st.length, 16); i++) {
                            sb.append("\n    at ").append(st[i]);
                        }
                        Logs.add(TAG_LINK, "⚠ 刷新链停摆 " + (gap / 1000) + " 秒 → 自动重启刷新；主线程栈："
                                + sb);
                    }
                    ui.removeCallbacks(tick);
                    ui.post(tick);
                } else if (stallLogged) {
                    stallLogged = false;
                    Logs.add(TAG_LINK, "刷新链已恢复（停摆结束）");
                }
            }
        }, "xds-ui-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** 上次写挡位校准存档的时刻（tick 是 5Hz，所以按墙钟计时） */
    private long lastCalSaveMs;

    /**
     * G 力自检（无人值守）：把一段**确定**的加速度序列灌进真实融合 + 绘制链路，逐点打 logcat。
     *   adb shell am start -n com.nz.xdsdisplay/.MainActivity --ez simaccel true
     *
     * 场景（装车姿态：屏幕后仰 30°，"前" = 屏幕 y 的地面投影）：
     *   0.0~2.0s   静止
     *   2.0~6.0s   持续前向 0.25G
     *   6.0~6.4s   前向脉冲 0.50G
     *   6.4~12.0s  1.5Hz ±0.10G 摆动（骑车时一直有的加减速/摆动）
     * 每轮 12s、重复 6 轮；时间戳按 400Hz 合成（dt 由时间戳给，本线程 sleep 抖不影响数学）。
     *
     * 判据（对照 verify/gforce/GravityFusionRateTest.java 的 PC 结论）：2~6s 的 linF 应稳在
     * ≈+0.22G；若它几秒内掉到 +0.10G 以下，就是姿态又把真实运动加速度吸走了（老 bug 复现）。
     */
    private void simGForceLoop() {
        try {
            Thread.sleep(1500);                     // 等界面绑完再灌
        } catch (InterruptedException e) {
            return;
        }
        // 姿态：屏幕后仰 30° → 屏幕系"上" u=(0,sin30,cos30)；"前" = ŷ 去掉 u 方向的分量
        double tilt = Math.toRadians(30);
        double ux = 0, uy = Math.sin(tilt), uz = Math.cos(tilt);
        double d = uy;
        double f0 = -d * ux, f1 = 1 - d * uy, f2 = -d * uz;
        double fn = Math.sqrt(f0 * f0 + f1 * f1 + f2 * f2);
        f0 /= fn;
        f1 /= fn;
        f2 /= fn;
        final int rate = 400, period = 12, reps = 6;
        java.util.Random rnd = new java.util.Random(20261010L);
        long base = System.nanoTime();
        for (int i = 0; i < reps * period * rate && !isFinishing(); i++) {
            double t = (i % (period * rate)) / (double) rate;
            double af;
            if (t < 2.0) af = 0;
            else if (t < 6.0) af = 0.25;
            else if (t < 6.4) af = 0.50;
            else af = 0.10 * Math.sin(2 * Math.PI * 1.5 * (t - 6.4));
            long ts = base + (long) ((double) i / rate * 1e9);
            // 含重力的原始加速度 = u·1G + 前·af·1G + 噪声（0.03 m/s²，和真机同量级）
            double ax = ux * GravityFusion.G0 + f0 * af * GravityFusion.G0 + rnd.nextGaussian() * 0.03;
            double ay = uy * GravityFusion.G0 + f1 * af * GravityFusion.G0 + rnd.nextGaussian() * 0.03;
            double az = uz * GravityFusion.G0 + f2 * af * GravityFusion.G0 + rnd.nextGaussian() * 0.03;
            fusion.setGyro(0f, 0f, 0f, ts);         // 不转：走"有陀螺仪"那档增益
            fusion.setAccel((float) ax, (float) ay, (float) az, ts);
            pushG();
            if (i % 100 == 0) {                      // 每 0.25s 一行
                Log.i(TAG, String.format(java.util.Locale.US,
                        "SIMACC t=%.2f af=%.2f linF=%+.3f linR=%+.3f linU=%+.3f |lin|=%.3f",
                        t, af, fusion.linF(), fusion.linR(), fusion.linU(),
                        Math.hypot(fusion.linR(), fusion.linF())));
            }
            if (i % 20 == 19) {                      // 每 50ms 让出 CPU
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
        Log.i(TAG, "SIMACC 结束（" + reps + " 轮）");
    }

    /**
     * 合成 CPS+GPC 数据喂给 GearRatio —— 只为在真机上把齿比卡显示出来看（85rpm / 48-22 → 第 7 档）。
     * 车速按 700c 周长反推，并叠 ±4% 噪声模拟 GPS 抖动；走的是和真实数据完全相同的
     * gear.update(...) → render() 路径。
     */
    private void simGearLoop() {
        double rpm = 85, ratio = 48.0 / 22, simPower = 200;
        boolean simSpeed = false;
        double rpm2 = -1, shiftMs = 0, coastMs = 0;
        android.content.Intent it = getIntent();
        if (it != null) {
            rpm = it.getIntExtra("simrpm", 85);
            ratio = 48.0 / it.getIntExtra("simcog", 22);
            // 换挡 / 滑行自检钩子：
            //   --ei simcog2 36 --ei simshift 8000  → 8s 后"换挡"到 36T（车速按新齿比走）
            //   --ei simcoast 12000                 → 12s 后踏频归 0（滑行），齿比应冻结不掉档
            if (it.hasExtra("simcog2")) rpm2 = 48.0 / it.getIntExtra("simcog2", 22);
            shiftMs = it.getIntExtra("simshift", 0);
            coastMs = it.getIntExtra("simcoast", 0);
            // 空踩自检钩子：--ei simpower 8 → 功率 8W（< 20W），齿比应冻结在上一稳定档
            simPower = it.getIntExtra("simpower", 200);
            // 顺带合成一条 GPS 速度/星数：--ez simspeed true → 速度卡 + 顶栏 GPS 状态也有数看
            simSpeed = it.getBooleanExtra("simspeed", false);
        }
        // 左右平衡条自检钩子：--ei simbal 56 → 合成 CPS 帧（功率 + 左脚 56%），
        // 平衡值 ±6% 慢摆，方便看那条细条跟着走（真机上要功率计报 Pedal Power Balance 才有）
        double simBal = it != null && it.hasExtra("simbal") ? it.getIntExtra("simbal", 50) : -1;
        if (rpm < 20) rpm = 85;                 // 防 --ei simrpm 0 把车速算成 NaN
        long t0 = System.currentTimeMillis();
        double tc = 60.0 / rpm;                                   // 秒/曲柄转（仅用于算车速）
        double speed = GearRatio.wheelCircM() * ratio / tc;       // m/s
        java.util.Random rnd = new java.util.Random(7);
        try {
            Thread.sleep(1500);            // 等 onCreate 把界面都绑完再开始 render
        } catch (InterruptedException e) {
            return;
        }
        while (!isFinishing()) {
            long el = System.currentTimeMillis() - t0;
            // 新算法只吃「车速 + 踏频」两个量：踏频固定，车速按目标齿比反推 + ±4% 噪声（模拟 GPS 抖动）
            double sp = speed, cad = rpm;
            if (rpm2 > 0 && shiftMs > 0 && el >= shiftMs) sp = GearRatio.wheelCircM() * rpm2 / tc;
            if (coastMs > 0 && el >= coastMs) cad = 0;            // 滑行：踏频 0
            gear.update(sp * (1 + (rnd.nextDouble() * 2 - 1) * 0.04), cad, simPower);
            if (simSpeed) {
                // 合成一条 GPS 帧（速度 + 星数），让速度卡和顶栏 GPS 状态也有东西看
                LnParser.Data gl = new LnParser.Data();
                gl.timestampMs = System.currentTimeMillis();
                gl.hasSpeed = true;
                gl.speedMps = (float) (sp * (1 + (rnd.nextDouble() * 2 - 1) * 0.04));
                gl.sats = 9;
                gl.fixQuality = 1;
                lastLn = gl;
            }
            if (simBal >= 0) {
                // 合成一条 CPS 帧：功率 + 左右平衡（左脚 simBal% 上下摆 ±6%）
                CpsParser.Data gc = new CpsParser.Data();
                gc.timestampMs = System.currentTimeMillis();
                gc.powerW = (int) Math.round(simPower);
                gc.hasBalance = true;
                gc.balanceLeftPct = Math.max(20, Math.min(80,
                        simBal + 6.0 * Math.sin(el / 12000.0 * 2 * Math.PI)));
                lastCps = gc;
            }
            ui.post(this::render);
            try {
                Thread.sleep(250L);        // 真机上 CPS/GPS 帧大约 4Hz
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * 中继断流监督（2026-10-10 真骑 7km 的 bug：中继机说"转发正常"，仪表这边数据全冻住）。
     *
     * 只看中继三通道（WiFi / 中继广播 / 中继连过来）——仪表自己的麦克风、加速度计不算数。
     * 判定断流后：① 状态栏闪烁告警，免得骑完才发现数没动 ② 分级自救（补报到 + 重扫广播、
     * 重建 UDP 接收通路，之后每 20 秒再来一轮）③ 全过程写日志文件，骑行回来能对账。
     */
    private void superviseRelay(long now) {
        long relayMs = ble.lastAnyMs();
        if (relayMs == 0) return;                       // 从没收到过中继数据 → 是没配对，不是断流
        long silent = now - relayMs;

        if (silent < RELAY_STALL_MS) {                  // 正常
            if (relayStallStartMs != 0) {
                Logs.add(TAG_LINK, "中继恢复：断流 " + ((now - relayStallStartMs) / 1000)
                        + " 秒 · " + linkDiag(now));
                relayStallStartMs = 0;
                relayTryCount = 0;
            }
            heartbeat(now);
            return;
        }

        if (relayStallStartMs == 0) {                   // 刚断
            relayStallStartMs = relayMs;                // 按最后一包起算，静默期不算漏
            relayStallTotal++;
            relayTryCount = 0;
            relayTryMs = 0;
            Logs.add(TAG_LINK, "⚠ 中继断流（已静默 " + (silent / 1000) + " 秒）· " + linkDiag(now));
        }

        if (relayTryCount > 0 && now - relayTryMs < RELAY_RETRY_MS) return;
        relayTryMs = now;
        relayTryCount++;
        String why = "中继断流自救第 " + relayTryCount + " 次（已断 "
                + ((now - relayStallStartMs) / 1000) + " 秒）";
        udp.nudge(why);                                 // 补报到 + 重建 UDP 接收通路
        ble.reconnect();                                // 重开广播扫描
        Logs.add(TAG_LINK, why + " · " + linkDiag(now));
    }

    /** 通道心跳：每 30 秒一条，写进日志文件 —— 下次再卡住，看这一行就知道断的是哪条路 */
    private void heartbeat(long now) {
        if (now - linkHeartbeatMs < LINK_HEARTBEAT_MS) return;
        linkHeartbeatMs = now;
        Logs.add(TAG_LINK, "通道心跳 · " + linkDiag(now)
                + " · 累计断流 " + relayStallTotal + " 次");
    }

    /** 通道诊断串：一眼看出哪条路断了 */
    private String linkDiag(long now) {
        return String.format(Locale.CHINA,
                "中继广播 %.1fs 前 / WiFi %.1fs 前 / BLE连入 %.1fs 前（WiFi 包 %d，自愈 %d 次）",
                ago(now, ble.advLastMs()), ago(now, ble.wifiLastMs()), ago(now, ble.connLastMs()),
                udp.packets(), udp.rebuildCount());
    }

    /** 距某时刻多少秒（-1 = 从没收到过），日志用 */
    private static double ago(long now, long t) {
        return t <= 0 ? -1 : (now - t) / 1000.0;
    }

    private void render() {
        txtClock.setText(num(clock.format(new Date())));

        chipStatus.setTextColor(getColor(stateColorRes));

        long now = System.currentTimeMillis();
        // 【任务】Link 卡右侧的空闲位置挂一段 GPS 信号状态（星数 / RTK / --）
        chipStatus.setText(stateText + gpsSuffix(now));

        // 【中继断流监督】数据不再刷新时必须看得见：状态栏换成闪烁告警
        // （2026-10-10 真骑 7km：中继机显示"正常转发"，仪表这边数字冻了整趟没人察觉）
        if (relayStalled()) {
            long sec = Math.max(0, now - relayStallStartMs) / 1000;
            boolean blinkOn = (now / 500) % 2 == 0;
            chipStatus.setTextColor(getColor(sec >= 20 ? R.color.err : R.color.warn));
            chipStatus.setText((blinkOn ? "⚠ " : "　") + "中继断流 " + sec + "s" + gpsSuffix(now));
        }
        CpsParser.Data cps = lastCps;
        LnParser.Data ln = lastLn;

        boolean powerFresh = cps != null && now - cps.timestampMs < 5000;
        boolean lnFresh = ln != null && now - ln.timestampMs < 5000;

        // 数字读数一律「固定字符数」：字号由 autosize 按字符串宽度定，位数一变字号就跳；
        // 位数真超了（1000W）才让它缩 —— 所以下面每个字段都按预期位数补空格（%3d / %6.2f …）
        txtPower.setText(powerFresh ? num(String.format(Locale.CHINA, "%3d", cps.powerW)) : num("---"));
        txtPower.setTextColor(powerFresh ? UiTheme.c(UiTheme.ACCENT) : UiTheme.muted());

        // 【任务】Power 卡下边缘的左右平衡条：只认功率计报的 Pedal Power Balance 字段，
        // 单边功率计没有这个字段 —— 那就只剩一条灰轨道，卡片高度也不变
        if (balStrip != null) {
            balStrip.setBalance(powerFresh && cps != null && cps.hasBalance
                    ? (float) cps.balanceLeftPct : -1f);
        }

        if (now - lastCrankChangeMs > 3000) cadence = 0;
        txtCadence.setText(powerFresh || lnFresh
                ? num(String.format(Locale.CHINA, "%3d", Math.round(cadence))) : num("---"));
        txtCadence.setTextColor(cadence > 0 ? UiTheme.c(UiTheme.ACCENT2) : UiTheme.muted());

        // 齿比卡片：大字=档位号（十进制 **正向** 1..12：1 = 最轻 48/48，12 = 最重最快 48/11），
        // 小字=齿数 + 齿比。（滑行时保持上一档，久不踩才回到 --）
        // land 布局可能没这张卡，取不到就跳过 —— 别让 render 崩在 null 上
        TextView gearVal = findViewById(R.id.txtGear);
        TextView gearSub = findViewById(R.id.txtGearSub);
        if (gearVal != null) {
            boolean gearOk = gear.known();
            String pos = gearOk ? gear.positionText() : "";
            // 0 RPM 滑行（cadence 上面已置 0）时档位变灰，还在踩就是高亮色
            boolean rolling = !pos.isEmpty() && cadence > 0;
            // 定宽 2 字符（" 1" / "12"）：位数变化不跳字号（autosize 按字符串宽度定字号）
            gearVal.setText(pos.isEmpty() ? num("--") : num(padNum(pos, 2)));
            gearVal.setTextColor(rolling ? UiTheme.c(UiTheme.ACCENT) : UiTheme.muted());
            if (gearSub != null) {
                gearSub.setText(!pos.isEmpty()
                        ? num(String.format(Locale.CHINA, "%d/%d · %.2f",
                                GearRatio.chainring(), gear.cogTeeth(), gear.ratio()), -1, false)
                        : num("--", -1, false));
                gearSub.setTextColor(rolling ? UiTheme.c(UiTheme.ACCENT3) : UiTheme.muted());
            }
        }

        double speedMps = lnFresh && ln != null && ln.hasSpeed ? ln.speedMps : 0;
        // 数值贴右边缘，位数变化不左右跳；小数点后一位用小一号字，省下的宽度全给整数位
        // 占位符（无数据时）走同一套格子 + 同样的小数位缩放，字号与正常读数一致
        txtSpeed.setText(lnFresh ? speedText(speedMps * 3.6) : num(new String(new char[]{' ', '-', '.', '-'}), 2, true));
        txtSpeed.setTextColor(lnFresh ? UiTheme.c(UiTheme.ACCENT3) : UiTheme.muted());
        // 【任务】速度右侧的 HUD 带状刻度：跟着同一个读数滚（没数据传 -1，整条压暗）
        if (speedTape != null) speedTape.setSpeed(lnFresh ? (float) (speedMps * 3.6) : -1f);

        txtAvgSpeed.setText(num(String.format(Locale.CHINA, "%4.1f", stats.avgSpeedMps() * 3.6)));
        txtDistance.setText(num(String.format(Locale.CHINA, "%6.2f", stats.distanceM() / 1000.0)));
        // 临时钉住里程显示，用来验证里程卡容不容得下五位数（如 114.51 km）：
        //   adb shell am start -n com.nz.xdsdisplay/.MainActivity --ei simdist 114510
        // 6 位（" 11.45" / "114.51"）是预期满宽，7 位（"1000.00"）就该整体缩一档
        android.content.Intent simIt = getIntent();
        if (simIt != null && simIt.hasExtra("simdist")) {
            txtDistance.setText(num(String.format(Locale.CHINA, "%6.2f", simIt.getIntExtra("simdist", 0) / 1000.0)));
        }
        // 混着文字（MAX 23.5 km/h）—— gridSpace=false：只把数字等宽，字母/空格按各自宽度
        txtMaxSpeed.setText(num(getString(R.string.max_fmt,
                String.format(Locale.US, "%4.1f", stats.maxSpeedMps() * 3.6)), -1, false));

        // 折线图：每秒采一个点
        if (chart != null && now - lastChartPushMs > 1000) {
            lastChartPushMs = now;
            float pw = (powerFresh && cps != null) ? cps.powerW : 0f;
            float sp = lnFresh ? (float) (speedMps * 3.6) : 0f;
            float cd = (float) cadence;
            chart.push(pw, sp, cd, powerFresh || lnFresh);
        }

        txtRideTime.setText(num(fmtDuration(stats.movingTimeMs())));

        // ---- 风噪（本机麦克风，当风阻的相对指示）：读数全部画在两根竖条上，这张卡不再放大字 ----
        WindNoise w = wind;
        boolean micLive = w != null && w.isRunning();
        if (txtWindSub != null) {
            txtWindSub.setText(micLive
                    ? getString(R.string.wind_sub_fmt, w.peakDb(), w.index())
                    : (w != null && !w.error().isEmpty()
                        ? w.error() : getString(R.string.wind_mic_off)));
        }

        // ---- 两根竖条：左 WIND（风噪指数）/ 右 BUMP（颠簸指数 = z 轴加速度局部方差的近期均值）----
        if (windBars != null) {
            windBars.setWind(micLive ? w.index() : 0f, micLive ? w.peakIndex() : 0f,
                    micLive ? String.format(Locale.US, "%.0f", w.db()) : "--");
            boolean hasBump = bump.hasData();
            windBars.setBump(hasBump ? BumpMeter.norm100(bump.level()) : 0f,
                    hasBump ? String.format(Locale.US, "%.2f", bump.level()) : "--");
        }

        // ---- 暂停键：回显中继的真实记录状态 ----
        if (btnPause != null) {
            boolean fresh = relayStateFresh();
            boolean paused = fresh && relayPausedNow();
            boolean recording = !fresh || relayRecordingNow();
            if (!recording) {
                btnPause.setText(R.string.btn_start);
                btnPause.setBackgroundTintList(ColorStateList.valueOf(UiTheme.muted()));
            } else if (paused) {
                btnPause.setText(R.string.btn_resume);
                btnPause.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.warn)));
            } else {
                btnPause.setText(R.string.btn_pause);
                btnPause.setBackgroundTintList(ColorStateList.valueOf(UiTheme.c(UiTheme.ACCENT2)));
            }
        }

        if (now - localBatAt > 15000) {
            localBatAt = now;
            cachedLocalBat = localBatteryPct();
        }
        chipSub.setText("B" + (cachedLocalBat >= 0 ? cachedLocalBat + "%" : "--")
                + "  R" + (relayBattery >= 0 ? relayBattery + "%" : "--"));

        if (now - lastTrackRefresh > 1500) {
            lastTrackRefresh = now;
            // 只在轨迹真的长出新点时才重投一遍（整条轨迹留在 RideStats 里，
            // 地图那边最多画 TRACK_DRAW_MAX 个点，长骑也不会越来越卡）
            int n = stats.trackSize();
            if (n != lastTrackSize) {
                lastTrackSize = n;
                map.setTrack(stats.trackSnapshotMax(TRACK_DRAW_MAX));
            }
        }
    }

    private static String gpsText(LnParser.Data ln) {
        String dim;
        switch (ln.posStatus) {
            case 1: dim = "3D"; break;
            case 2: dim = "估算"; break;
            case 3: dim = "最后已知"; break;
            default: dim = "无定位"; break;
        }
        return String.format(Locale.CHINA, "%s %.5f,%.5f",
                dim, ln.lat, ln.lon);
    }

    private static String fmtDuration(double ms) {
        long total = (long) (ms / 1000);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return String.format(Locale.CHINA, "%02d:%02d:%02d", h, m, s);
    }

    /** 本机（显示终端）电量；读不到返回 -1 */
    private int localBatteryPct() {
        try {
            Intent i = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return -1;
            int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (lvl < 0 || scale <= 0) return -1;
            return (int) (100f * lvl / scale);
        } catch (Exception e) {
            return -1;
        }
    }

    private void showOfflineDialog() {
        if (OfflineTiles.isRunning()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.menu_offline)
                    .setMessage(R.string.offline_running)
                    .setPositiveButton(R.string.offline_cancel_dl, (d, w) -> OfflineTiles.cancel())
                    .setNegativeButton(R.string.offline_keep, null)
                    .show();
            return;
        }
        final int have = OfflineTiles.count(this);
        String[] items = {
                getString(R.string.offline_item_5),
                getString(R.string.offline_item_15),
                getString(R.string.offline_item_40),
                getString(R.string.offline_item_clear_fmt, have)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.offline_title_fmt, TileCache.sourceName()))
                .setItems(items, (d, which) -> {
                    if (which == 3) {
                        new MaterialAlertDialogBuilder(this)
                                .setMessage(R.string.offline_clear_msg)
                                .setPositiveButton(R.string.offline_clear_ok, (dd, ww) -> {
                                    OfflineTiles.clear(this);
                                    Toast.makeText(this, R.string.toast_offline_cleared,
                                            Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton(R.string.dlg_cancel, null)
                                .show();
                        return;
                    }
                    if (!hasLastPos) {
                        Toast.makeText(this, R.string.toast_no_position, Toast.LENGTH_LONG).show();
                        return;
                    }
                    double radius;
                    int zMin, zMax;
                    if (which == 0) {
                        radius = 5;
                        zMin = 13;
                        zMax = 15;
                    } else if (which == 1) {
                        radius = 15;
                        zMin = 11;
                        zMax = 14;
                    } else {
                        radius = 40;
                        zMin = 9;
                        zMax = 13;
                    }
                    startOffline(radius, zMin, zMax);
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void startOffline(double radiusKm, int zMin, int zMax) {
        Toast.makeText(this, R.string.toast_offline_start, Toast.LENGTH_SHORT).show();
        OfflineTiles.start(this, lastLat, lastLon, radiusKm, zMin, zMax, new OfflineTiles.Listener() {
            @Override
            public void onProgress(int done, int total, int failed) {
                runOnUiThread(() -> {
                    if (btnMenu != null) {
                        btnMenu.setText(total > 0 ? (done * 100 / total) + "%" : menuDefaultText);
                    }
                });
            }

            @Override
            public void onDone(int done, int total, int failed, boolean cancelled) {
                runOnUiThread(() -> {
                    if (btnMenu != null) btnMenu.setText(menuDefaultText);
                    Toast.makeText(MainActivity.this, getString(cancelled
                                    ? R.string.toast_offline_cancelled_fmt
                                    : R.string.toast_offline_done_fmt, done, failed),
                            Toast.LENGTH_LONG).show();
                    if (map != null) map.invalidate();
                });
            }
        });
    }

    private void showLogDialog() {
        List<String> lines = Logs.snapshot();
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.size() - 40);
        for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_title)
                .setMessage(sb.length() == 0 ? getString(R.string.log_empty) : sb.toString())
                .setPositiveButton(R.string.dlg_close, null)
                .setNeutralButton(R.string.log_clear, (d, w) -> Logs.clear())
                .show();
    }

    // ================================================================
    //  音量键：两键分工（2026-10 用户要求，替代此前「两键等价」的做法）。
    //   · 上键 —— 「折线图 ⇄ 地图」对切（与屏幕上 btnView 同效），不用先记住当前在哪个视图。
    //   · 下键 —— 「经典全量布局 ⇄ 精简页」对切：精简页把 WIND/BUMP 卡与正方形加速度计区收掉，
    //            省下的高度放大 速度 / 功率 / 踏频(RPM)。选择记进 main_focus_page。
    //  更早的版本 VOL− 还兼着暂停/结束骑行，早废了 —— 暂停 / 继续 / 结束骑行一律走 btnPause。
    // ================================================================

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent e) {
        boolean up = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP;
        boolean down = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
        if (up || down) {
            if (e.getRepeatCount() == 0) {
                if (up) {
                    toggleViewMode();        // 上键：折线图 ⇄ 地图
                } else {
                    toggleFocusPage();       // 下键：经典全量 ⇄ 精简页（放大 速度/功率/踏频）
                }
            }
            return true;                     // 不外放给系统调音量
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent e) {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) return true;
        return super.onKeyUp(keyCode, e);
    }

    /** 下发一条控制命令：WiFi(UDP) 优先；BLE 模式走本机 GATT 控制特征通知给中继 */
    private boolean sendCtrl(int cmd) {
        UdpLink u = udp;
        if (u != null && u.hasRelay() && u.sendControl(cmd)) return true;
        BleServer b = srv;
        if (b != null && b.sendControl(cmd)) return true;
        return u != null && u.sendControl(cmd);
    }

    // ================================================================
    //  双击卡片最大化 / 再双击或返回键恢复
    // ================================================================

    private android.view.View maxView;
    private final List<android.view.View> maxHidden = new ArrayList<>();
    private final List<android.view.View> maxResized = new ArrayList<>();
    private final List<android.view.ViewGroup.LayoutParams> maxLpOld = new ArrayList<>();

    /**
     * 全局双击检测：在 dispatchTouchEvent 里量，卡片内部子 View 抢不走。
     * 注意：**必须等 Activity 挂上 Context 之后再 new** —— 直接写成字段初始化
     * 会在 Activity 实例化阶段（attachBaseContext 之前）就构造 GestureDetector，
     * 而它的构造函数要读 ViewConfiguration → Context.getResources() → NPE 直接崩。
     */
    /**
     * 双击放大自己数（不再用 GestureDetector）：框架的双击窗口固定 300ms，
     * 戴手套+车把颠簸经常来不及；放宽到 550ms，且两次必须落在同一个点附近。
     * （顺带解决另一个坑：GestureDetector 写在字段初始化里会在 Activity 还没挂上
     * Context 时就构造 → ViewConfiguration 读 Context.getResources() 直接 NPE。）
     */
    private static final long DBL_TAP_MS = 550;
    private static final float DBL_TAP_SLOP = 90f;
    private long lastTapMs;
    private float lastTapX, lastTapY;
    /** 上一次单击还"活"着（在双击窗口内） */
    private boolean tapSeen;
    /** 第二次按下已经把这串事件吃掉了，等抬指时再切放大 */
    private boolean dblTapHolding;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        int act = e.getActionMasked();
        long now = android.os.SystemClock.uptimeMillis();

        // 双击必须在**第二次按下**时就截住，不能等到抬指才发现是双击。
        // 踩过的坑：原来在 ACTION_UP 里判定双击后直接 return true —— 等于只给子 View 发了
        // DOWN 而没发 UP/CANCEL，子 View（G 力圆盘）的按下状态/longPress 定时器还挂着，
        // 500ms 后照样触发 onLongClick → 轴模式被切走 → 表现出来就是"双击之后方向
        // 镜像/旋转了"。现在第二次 DOWN 就不往下发：子 View 完全不知道有这一下。
        if (dblTapHolding) {
            if (act == android.view.MotionEvent.ACTION_UP) {
                dblTapHolding = false;
                tapSeen = false;            // 这一轮结束；再点一下算新的第一次
                android.view.View c = cardAt(
                        getWindow().getDecorView(), e.getRawX(), e.getRawY());
                if (c != null) {
                    toggleMaximize(c);
                    return true;
                }
            } else if (act == android.view.MotionEvent.ACTION_CANCEL) {
                dblTapHolding = false;
                tapSeen = false;
            }
            return true;                    // MOVE / 多指 / 抬指都吃掉
        }

        if (act == android.view.MotionEvent.ACTION_DOWN) {
            boolean near = Math.abs(e.getRawX() - lastTapX) < DBL_TAP_SLOP
                    && Math.abs(e.getRawY() - lastTapY) < DBL_TAP_SLOP;
            if (tapSeen && now - lastTapMs <= DBL_TAP_MS && near) {
                dblTapHolding = true;
                lastTapMs = now;
                lastTapX = e.getRawX();
                lastTapY = e.getRawY();
                return true;
            }
            tapSeen = true;
            lastTapMs = now;
            lastTapX = e.getRawX();
            lastTapY = e.getRawY();
        }
        return super.dispatchTouchEvent(e);
    }

    /**
     * 找出屏幕坐标下最里层的「可放大块」：MaterialCardView 卡片（含齿比卡），
     * 或者 G 力圆盘 —— 圆盘故意没有卡片背景（layout 里直接画圆），
     * 只认 MaterialCardView 的话在圆盘上双击会什么都不发生。
     */
    private android.view.View cardAt(android.view.View v, float rx, float ry) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                android.view.View hit = cardAt(g.getChildAt(i), rx, ry);
                if (hit != null) return hit;
            }
        }
        if ((v instanceof com.google.android.material.card.MaterialCardView
                || v instanceof com.nz.xdsdisplay.GForceView)
                && v.getVisibility() == android.view.View.VISIBLE) {
            android.graphics.Rect r = new android.graphics.Rect();
            if (v.getGlobalVisibleRect(r) && r.contains((int) rx, (int) ry)) return v;
        }
        return null;
    }

    /** 双击：把卡片放大到整屏（把它自己那条父链上的兄弟全藏掉）；再双击恢复 */
    private void toggleMaximize(android.view.View card) {
        if (card == maxView) { restoreMaximize(); return; }
        restoreMaximize();
        android.view.View v = card;
        android.view.ViewParent p = v.getParent();
        while (p instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) p;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View ch = g.getChildAt(i);
                if (ch != v && ch.getVisibility() == android.view.View.VISIBLE) {
                    maxHidden.add(ch);
                    ch.setVisibility(android.view.View.GONE);
                }
            }
            maxResized.add(v);
            maxLpOld.add(v.getLayoutParams());
            v.setLayoutParams(fillLp(g));
            v = g;
            p = g.getParent();
        }
        maxView = card;
        Logs.add(TAG, "卡片最大化");
    }

    /** 在某个父容器里"占满"的 LayoutParams */
    private android.view.ViewGroup.LayoutParams fillLp(android.view.ViewGroup parent) {
        if (parent instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) parent;
            return ll.getOrientation() == LinearLayout.VERTICAL
                    ? new LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    : new LinearLayout.LayoutParams(
                            0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        }
        // 注意：**不能返回裸的 ViewGroup.LayoutParams** —— FrameLayout 这类父容器
        // measure 时会走 measureChildWithMargins，把 LP 强转 MarginLayoutParams，
        // 裸 LP 会直接 ClassCastException 崩掉（真机上双击放大踩过这个坑）。
        if (parent instanceof android.widget.FrameLayout) {
            return new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        }
        android.view.ViewGroup.MarginLayoutParams mlp =
                new android.view.ViewGroup.MarginLayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        mlp.setMargins(0, 0, 0, 0);
        return mlp;
    }

    private void restoreMaximize() {
        for (int i = 0; i < maxResized.size(); i++) {
            android.view.ViewGroup.LayoutParams lp = maxLpOld.get(i);
            if (lp != null) maxResized.get(i).setLayoutParams(lp);
        }
        for (android.view.View h : maxHidden) h.setVisibility(android.view.View.VISIBLE);
        maxResized.clear();
        maxLpOld.clear();
        maxHidden.clear();
        maxView = null;
    }

    @Override
    public void onBackPressed() {
        if (maxView != null) { restoreMaximize(); return; }   // 返回键先退出最大化
        super.onBackPressed();
    }

}
