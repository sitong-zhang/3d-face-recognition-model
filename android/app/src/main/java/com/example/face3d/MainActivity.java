package com.example.face3d;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面 —— 交互流程 1:1 照搬开源项目 jonnyoo/glance（MIT）的 OnboardingController。
 *
 * 步骤式状态机（glance OnboardingStep）：
 *   WELCOME(intro) → PRESETUP(preSetup) → ENROLL(enroll) → NAME(name) → COMPLETE(complete) → HOME
 *
 * 录入九姿态参数、圆环反馈、对勾动画全部与 glance 对齐；
 * 唯一的算法替换点：glance 用 2D ArcFace(512D)，这里换成 3D 人脸资产（512D 身份 + 478 点 3D 几何 + mesh）。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;
    private static final int SCAN_MATCH_FRAMES = 2;

    // ---- glance 九方向录入参数（逐行对齐 OnboardingController.swift）----
    private static final int SAMPLES_PER_POSE = 2;        // glance samplesPerPose = 2
    private static final int MODEL_POINTS = 512;         // 3D 建模光点总数（与身份向量维度一致）
    private static final int REQUIRED_MATCH_STREAK = 3;   // glance requiredMatchStreak = 3
    private static final long POSE_HOLD_MS = 500L;        // glance poseHoldDuration = 500ms
    private static final long INITIAL_CAPTURE_DELAY_MS = 1200L; // glance initialCaptureDelay = 1.5s
    private static final long STALL_TIMEOUT_MS = 7000L;   // 卡住 7s 起放宽窗口（手机 MediaPipe 姿态比 Vision 噪声大）
    private static final long FORCE_CAPTURE_MS = 13000L;  // 单姿态卡满 13s 直接采集当前有效帧，保证录入一定走得完
    private static final float STALL_WIDEN = 1.6f;        // 放宽倍数（窗口上限也随之放宽）

    // 姿态阈值按【真机 MediaPipe 实测】重新标定，不再硬照搬 glance 的 Apple Vision 数值：
    // 实测正脸 matrix yaw≈0.00~0.25、pitch≈0.04~0.18（弧度）。旧值 CENTER 要求 |pitch|<0.15，
    // 连正视都可能采不到；LEFT 窗口仅 0.24~0.51 且过头阈值同为 0.51，用户一转就被判"幅度过大"。
    // 新值：目标窗口更宽、CENTER 容差更大、且与"过头阈值"彻底分离。
    private static final float YAW_INNER = 0.12f, YAW_CENTER = 0.30f, YAW_OUTER = 0.90f;
    private static final float PITCH_INNER = 0.10f, PITCH_CENTER = 0.26f, PITCH_OUTER = 0.70f;

    // 九宫格扫描分区阈值：略大于真机正视波动（yaw≈0~0.25,pitch≈0.04~0.18），
    // 保证正视稳定落在中心格；转头/俯仰幅度明显够了才归入左右列 / 上下行。
    private static final float SCAN_YAW_INNER = 0.28f, SCAN_PITCH_INNER = 0.22f;

    // 逐点探针映射：正视中值基准 + 灵敏度。幅度调高，额头/侧脸更易扫到。
    private static final float YAW_BIAS = 0.12f, PITCH_BIAS = 0.11f;  // 真机正视时的 yaw/pitch 中值
    private static final float PROBE_KX = 0.55f, PROBE_KY = 0.95f;    // 朝向→探针位移灵敏度（抬头更灵敏）
    private static final int POINTS_PER_SAMPLE = 24;                  // 每点亮约 24 点采一帧 3D 资产
    // 真正"转过头"的门限，明显大于目标窗口上限——只有接近侧脸/仰头到极限才提示
    private static final float EXCESSIVE_YAW = 1.15f, EXCESSIVE_PITCH = 0.95f;

    /** 9 个引导姿态：center + 8 罗盘方向，顺序与 glance EnrollmentPose 完全一致。 */
    private enum Pose {
        CENTER(0, "正视前方，保持不动", Band.NONE, Band.NONE, -1),
        LEFT(1, "缓慢向左转头", Band.LEFT, Band.NONE, 270),
        TOP_LEFT(2, "转向左上方", Band.LEFT, Band.UP, 315),
        TOP(3, "缓慢抬头向上", Band.NONE, Band.UP, 0),
        TOP_RIGHT(4, "转向右上方", Band.RIGHT, Band.UP, 45),
        RIGHT(5, "缓慢向右转头", Band.RIGHT, Band.NONE, 90),
        BOTTOM_RIGHT(6, "转向右下方", Band.RIGHT, Band.DOWN, 135),
        BOTTOM(7, "缓慢低头向下", Band.NONE, Band.DOWN, 180),
        BOTTOM_LEFT(8, "转向左下方", Band.LEFT, Band.DOWN, 225);

        enum Band { LEFT, NONE, RIGHT, UP, DOWN }
        final int index; final String instruction; final Band yaw; final Band pitch; final int compass;
        Pose(int i, String ins, Band y, Band p, int compass) {
            this.index = i; this.instruction = ins; this.yaw = y; this.pitch = p; this.compass = compass;
        }
        static Pose at(int i) { return values()[i]; }
        float leniency() { // glance EnrollmentPose.matchLeniency
            switch (this) {
                case BOTTOM_LEFT: case BOTTOM_RIGHT: return 1.5f;
                case BOTTOM: return 1.2f;
                default: return 1f;
            }
        }
    }
    private static final Pose[] POSES = Pose.values();

    /** glance OnboardingStep 的 Android 映射。 */
    private enum Step { WELCOME, PRESETUP, ENROLL, NAME, COMPLETE, HOME }
    private Step step = Step.WELCOME;

    // ---- UI：glance 面板步骤 ----
    private View stepWelcome, stepPresetup, stepEnroll, stepName, stepComplete, stepHome;
    private View scrimView;
    private View panelContainer, dimOverlay;
    private CircleHoleOverlay holeOverlay;
    private FrameAnimView logoAnim, idleAnim;
    private EnrollmentRingView enrollRing;
    private CheckmarkView checkmark, completeCheck;
    private TextView enrollInstruction, nameError, homeCount, homeStatus, homeTip;
    private EditText nameInput;
    private FaceIdAnimationView faceAnim;
    private DynamicIslandView islandView;
    private Button btnGetStarted, btnPresetupBack, btnPresetupNext, btnNameBack, btnNameSave;
    private Button btnScan, btnEnroll, btnManage;
    private ImageButton btnEnrollClose;

    // ---- 端侧高斯 3D 重建过程可视化覆盖层 ----
    private View rebuildOverlay;
    private ReconstructionView reconstructionView;
    private TextView rebuildStage, rebuildPercent;
    private ProgressBar rebuildBar;
    private Button btnRebuildDone;

    // ---- 引擎 / 数据 ----
    private FaceEngine engine;
    private FaceStore store;
    private ExecutorService cameraExecutor;
    private IncrementalFaceReconstructor reconstructor; // 端侧高斯 3D 增量重建器（录入采完后融合成型）
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 流程代次：每次开始/取消一个流程就 +1。流程收尾用的延时 UI 回调都带上发起时的代次，
     * 执行前校验，代次变了就丢弃。这样旧流程遗留的"收回药丸/切换步骤"等回调，
     * 不会在新流程（例如扫描刚成功就立刻点录入）里抢夺灵动岛，避免建模卡片弹不出来。
     */
    private int flowEpoch = 0;
    private void beginFlowTransition() {
        flowEpoch++;
        mainHandler.removeCallbacksAndMessages(null); // 立刻清掉上一流程尚未执行的收尾回调
    }
    /** 延时执行一个 UI 收尾动作，但仅当发起它的流程仍是当前流程时生效。 */
    private void postFlowAction(long delayMs, Runnable action) {
        final int epoch = flowEpoch;
        mainHandler.postDelayed(() -> { if (epoch == flowEpoch) action.run(); }, delayMs);
    }
    private volatile boolean capturing = false;

    private enum Flow { NONE, ENROLL, SCAN }
    private Flow flow = Flow.NONE;

    // ---- 录入状态（glance 九方向）----
    private String pendingName;
    private final List<float[]> enrollSamples = new ArrayList<>();     // 512D 身份
    private final List<float[]> enrollGeoAssets = new ArrayList<>();   // 478*3 3D 几何
    private final List<String> enrollPoseNames = new ArrayList<>();
    private float[] lastMesh3d;

    // ---- 后台重建状态（录入扫描完成后重建在后台跑，不弹全屏界面）----
    private volatile boolean rebuildDone = false;       // 后台重建是否完成
    private boolean pendingConfirm = false;             // 用户已点提交、等重建完成后再入库

    // ---- 逐点探针扫描（扫到哪颗亮哪颗）----
    private int capturedCount = 0;                     // 累计采集的 3D 资产帧数
    private int nextSampleAtPoints = POINTS_PER_SAMPLE; // 点亮数达到此值时采下一帧资产
    private long lastCaptureAtMs = 0;                  // 采集节流时间戳
    private int matchStreak = 0;
    private long enrollStartedAt = 0;
    private long frameReadyAt = 0;      // 首帧采集延迟（避免采集到眨眼帧）
    private long poseStartedAt = 0;
    private Long poseHoldStartedAt = null;

    // ---- 扫描状态 ----
    private final java.util.Map<String, Integer> scanVotes = new java.util.HashMap<>();
    private String scanCandidate;
    private int scanMatchCount = 0;
    private LivenessDetector liveness;   // 静默活体检测：真人眨眼/微动即通过，拦静止照片

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupEngine();
        wireButtons();

        // glance 初始进入 .intro；已有库则直接回 HOME
        showStep(store.count() > 0 ? Step.HOME : Step.WELCOME);
        faceAnim.setState(FaceIdAnimationView.State.IDLE);
        islandView.showPill();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void bindViews() {
        previewView = findViewById(R.id.previewView);
        scrimView = findViewById(R.id.scrimView);
        panelContainer = findViewById(R.id.panelContainer);
        dimOverlay = findViewById(R.id.dimOverlay);
        holeOverlay = findViewById(R.id.holeOverlay);
        stepWelcome = findViewById(R.id.stepWelcome);
        stepPresetup = findViewById(R.id.stepPresetup);
        stepEnroll = findViewById(R.id.stepEnroll);
        stepName = findViewById(R.id.stepName);
        stepComplete = findViewById(R.id.stepComplete);
        stepHome = findViewById(R.id.stepHome);
        logoAnim = findViewById(R.id.logoAnim);
        idleAnim = findViewById(R.id.idleAnim);
        enrollRing = findViewById(R.id.enrollRing);
        checkmark = findViewById(R.id.checkmark);
        completeCheck = findViewById(R.id.completeCheck);
        enrollInstruction = findViewById(R.id.enrollInstruction);
        nameInput = findViewById(R.id.nameInput);
        nameError = findViewById(R.id.nameError);
        homeCount = findViewById(R.id.homeCount);
        homeStatus = findViewById(R.id.homeStatus);
        homeTip = findViewById(R.id.homeTip);
        faceAnim = findViewById(R.id.faceAnim);
        islandView = findViewById(R.id.islandView);
        btnGetStarted = findViewById(R.id.btnGetStarted);
        btnPresetupBack = findViewById(R.id.btnPresetupBack);
        btnPresetupNext = findViewById(R.id.btnPresetupNext);
        btnNameBack = findViewById(R.id.btnNameBack);
        btnNameSave = findViewById(R.id.btnNameSave);
        btnScan = findViewById(R.id.btnScan);
        btnEnroll = findViewById(R.id.btnEnroll);
        btnManage = findViewById(R.id.btnManage);
        btnEnrollClose = findViewById(R.id.btnEnrollClose);

        // 端侧 3D 重建过程可视化覆盖层
        rebuildOverlay = findViewById(R.id.rebuildOverlay);
        reconstructionView = findViewById(R.id.reconstructionView);
        rebuildStage = findViewById(R.id.rebuildStage);
        rebuildPercent = findViewById(R.id.rebuildPercent);
        rebuildBar = findViewById(R.id.rebuildBar);
        btnRebuildDone = findViewById(R.id.btnRebuildDone);
        btnRebuildDone.setOnClickListener(v -> {
            rebuildOverlay.setVisibility(View.GONE);
            enterNameStep(); // 重建真正完成后才允许进入命名 = 才算录入成功
        });
    }

    private PreviewView previewView;

    private void setupEngine() {
        cameraExecutor = Executors.newSingleThreadExecutor();
        store = new FaceStore(this);
        liveness = new LivenessDetector();
        reconstructor = new IncrementalFaceReconstructor(cameraExecutor);
        try {
            engine = new FaceEngine(this);
        } catch (Exception e) {
            android.util.Log.e("Face3D", "引擎初始化失败", e);
            Toast.makeText(this, "人脸引擎初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        refreshCount();
        // glance 的 intro/preSetup 循环视频动画（此处用官方扫描帧动画代替）
        logoAnim.play("scan", true, 20);
        idleAnim.play("scan", true, 20);
    }

    @Override
    public void onBackPressed() {
        if (rebuildOverlay != null && rebuildOverlay.getVisibility() == View.VISIBLE) {
            cancelEnroll(); // 重建过程中按返回键 = 放弃本次录入
            return;
        }
        super.onBackPressed();
    }

    private void wireButtons() {
        btnGetStarted.setOnClickListener(v -> showStep(Step.PRESETUP));
        btnPresetupBack.setOnClickListener(v -> showStep(Step.WELCOME));
        btnPresetupNext.setOnClickListener(v -> beginEnroll());
        btnEnrollClose.setOnClickListener(v -> cancelEnroll());
        btnNameBack.setOnClickListener(v -> cancelEnroll());
        btnNameSave.setOnClickListener(v -> confirmName());
        btnScan.setOnClickListener(v -> startScan());
        btnEnroll.setOnClickListener(v -> beginEnroll());
        btnManage.setOnClickListener(v -> showManageDialog());
        // 震动自检：连点"已录入 N 人"7 次触发一次强震动，便于确认手机震动是否工作（不影响正常功能）
        homeCount.setOnClickListener(new View.OnClickListener() {
            int taps = 0; long lastTap = 0;
            @Override public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (now - lastTap > 900) taps = 0;   // 间隔太久重新开始计数
                lastTap = now;
                taps++;
                if (taps >= 7) {
                    taps = 0;
                    vibrateStrong();
                    showVibrationDiag();
                }
            }
        });
    }

    // ---------------- glance 步骤切换（OnboardingNotchView 的滚动过渡简化为淡入）----------------

    private void showStep(Step s) {
        step = s;
        boolean enrolling = (s == Step.ENROLL);
        // 录入：隐藏中央面板与暗化层，露出全屏原生相机 + 圆形挖孔
        panelContainer.setVisibility(enrolling ? View.GONE : View.VISIBLE);
        dimOverlay.setVisibility(enrolling ? View.GONE : View.VISIBLE);
        holeOverlay.setVisibility(enrolling ? View.VISIBLE : View.GONE);

        stepWelcome.setVisibility(s == Step.WELCOME ? View.VISIBLE : View.GONE);
        stepPresetup.setVisibility(s == Step.PRESETUP ? View.VISIBLE : View.GONE);
        stepEnroll.setVisibility(enrolling ? View.VISIBLE : View.GONE);
        stepName.setVisibility(s == Step.NAME ? View.VISIBLE : View.GONE);
        stepComplete.setVisibility(s == Step.COMPLETE ? View.VISIBLE : View.GONE);
        stepHome.setVisibility(s == Step.HOME ? View.VISIBLE : View.GONE);

        // 圆洞修复：holeOverlay 与 enrollCluster 均为全屏 match_parent 且内容居中，
        // 圆洞默认就画在 View 的 (w/2, h/2)，与圆环天然同心。
        // 旧版用 getLocationOnScreen()（屏幕坐标，含状态栏）去设 View 局部坐标的中心，
        // 坐标系不一致 → 圆洞整体偏移一个状态栏高度 → "两个圆圈明显歪了"。故删除该同步。
        View cur = currentStepView();
        if (cur != null) {
            cur.setAlpha(0f);
            cur.animate().alpha(1f).setDuration(280).start();
        }
        if (s == Step.HOME) islandView.showPill();
    }

    private View currentStepView() {
        switch (step) {
            case WELCOME: return stepWelcome;
            case PRESETUP: return stepPresetup;
            case ENROLL: return stepEnroll;
            case NAME: return stepName;
            case COMPLETE: return stepComplete;
            case HOME: return stepHome;
            default: return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_CAMERA && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            homeStatus.setText("需要相机权限");
            homeTip.setText("请在系统设置中授予相机权限");
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                // 取景缩放：FIT_CENTER 让相机完整视野缩放进屏幕（不裁边、不过度放大脸部），
                // 正常握持距离即可把整张脸放进圆洞，不再需要把手臂伸很远。
                previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new android.util.Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
                provider.unbindAll();
                androidx.camera.core.Camera cam = provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
                // 曝光自适应：记录相机控制权与曝光补偿范围，暗光时把进光量拉满（硬件级提亮，
                // 比软件 gamma 更干净），光线恢复后逐步回落到中性，避免白天过曝。
                boundCamera = cam;
                androidx.camera.core.ExposureState es = cam.getCameraInfo().getExposureState();
                if (es != null && es.isExposureCompensationSupported()) {
                    android.util.Range<Integer> er = es.getExposureCompensationRange();
                    exposureMin = (er != null && er.getLower() < 0) ? er.getLower() : 0;
                    exposureMax = (er != null && er.getUpper() > 0) ? er.getUpper() : 0;
                }
            } catch (Exception e) {
                homeStatus.setText("相机启动失败");
                homeTip.setText(String.valueOf(e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private volatile long lastDetectAt = 0;

    // ---- 暗光曝光自适应 ----
    private volatile androidx.camera.core.Camera boundCamera;
    private volatile int exposureMin = 0, exposureMax = 0;
    private volatile long lastExposureAt = 0;
    private volatile int currentExposure = Integer.MIN_VALUE;

    /**
     * 相机帧回调（运行在 cameraExecutor 后台线程）。
     * 圆内画面由底层 CameraX PreviewView 原生渲染（朝向正确、GPU 零卡顿），
     * 这里不再自绘任何 Bitmap —— 仅做后台检测，彻底消除倒像/卡顿/朝向错乱。
     */
    private void analyzeFrame(@NonNull ImageProxy image) {
        if (!capturing || flow == Flow.NONE || engine == null) { image.close(); return; }
        try {
            int rotation = image.getImageInfo().getRotationDegrees();
            Bitmap frame = yuvToBitmap(image, rotation);   // 转正 + 镜像（与原生预览一致）
            if (frame == null) return;

            // 暗光自适应曝光：约每 700ms 评估一次，越暗曝光补偿越高（拉满进光量）
            adaptExposure(frame);

            // 检测节流（后台线程）：约 11fps 足够驱动九姿态状态机
            long now = System.currentTimeMillis();
            if (now - lastDetectAt < 90) return;
            lastDetectAt = now;

            FaceEngine.Detection det = engine.detect(frame);
            final FaceEngine.Detection result = det;
            android.util.Log.d("Face3D", "帧检测: hasFace=" + det.hasFace
                    + " embedding=" + (det.embedding != null) + " yaw=" + det.yaw);
            mainHandler.post(() -> {
                if (flow == Flow.ENROLL) handleEnrollFrame(result);
                else if (flow == Flow.SCAN) handleScanFrame(result);
            });
        } catch (Exception e) {
            android.util.Log.e("Face3D", "帧处理异常", e);
        }
        finally { image.close(); }
    }

    /**
     * 暗光自适应曝光（硬件级提亮）。
     * 估算帧平均亮度，越暗把曝光补偿推得越高（进光量增加，画面更干净、噪点更少，
     * 从源头提升 MediaPipe 检出率）；亮度足够时回落到中性，避免白天过曝。
     * 带 700ms 节流 + 相邻帧迟滞，避免曝光频繁跳变导致画面闪烁。
     */
    private void adaptExposure(Bitmap frame) {
        androidx.camera.core.Camera cam = boundCamera;
        if (cam == null || exposureMax <= 0) return; // 设备不支持曝光补偿
        long now = System.currentTimeMillis();
        if (now - lastExposureAt < 700) return;
        lastExposureAt = now;

        // 稀疏采样亮度
        int w = frame.getWidth(), h = frame.getHeight();
        long sum = 0; int cnt = 0;
        for (int y = 0; y < h; y += 16) {
            for (int x = 0; x < w; x += 16) {
                int c = frame.getPixel(x, y);
                sum += (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
                cnt++;
            }
        }
        if (cnt == 0) return;
        float avg = (float) sum / cnt;

        // 亮度→曝光档位：avg>=110 中性(0)；越暗越高，最暗拉满 exposureMax。
        int target;
        if (avg >= 110f) target = 0;
        else {
            float dark = Math.max(0f, Math.min(1f, 1f - avg / 110f)); // 0..1
            target = Math.round(exposureMax * dark);
        }
        target = Math.max(exposureMin, Math.min(exposureMax, target));
        if (target == currentExposure) return;
        currentExposure = target;
        try {
            cam.getCameraControl().setExposureCompensationIndex(target);
        } catch (Exception ignored) { }
    }

    /**
     * YUV_420_888 -> 正立 RGB Bitmap（关键修复）。
     *
     * 处理顺序至关重要，必须与 CameraX 原生 PreviewView 的显示完全一致（所见即所检）：
     *   1) YUV -> NV21 -> Bitmap（用 Android 官方 YuvImage，转换绝对正确）
     *   2) 按相机 rotationDegrees 转正   ← 必须先转
     *   3) 在【正立图】上做水平镜像       ← 镜像必须在转正之后
     * 旧版把 2、3 顺序写反（先在横躺帧上镜像再转正），结果得到上下翻转的脸，
     * MediaPipe 对倒脸检测率极低 —— 这是"把脸凑过去仍提示未检测到人脸"的直接原因之一。
     */
    private Bitmap yuvToBitmap(ImageProxy image, int rotation) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        int w = image.getWidth(), h = image.getHeight();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        // 1) YUV_420_888 -> NV21（逐行按 stride 取，U/V 交错写入 V,U）
        byte[] nv21 = new byte[w * h * 3 / 2];
        int o = 0;
        for (int row = 0; row < h; row++) {
            int rowStart = row * yRowStride;
            for (int col = 0; col < w; col++) nv21[o++] = yBuf.get(rowStart + col);
        }
        int chromaH = h / 2, chromaW = w / 2;
        for (int row = 0; row < chromaH; row++) {
            for (int col = 0; col < chromaW; col++) {
                int idx = row * uvRowStride + col * uvPixelStride;
                nv21[o++] = vBuf.get(idx);
                nv21[o++] = uBuf.get(idx);
            }
        }
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        // 暗光下 JPEG 有损压缩会首先牺牲暗部细节，质量从 92 提到 100，尽量减少损失
        yuv.compressToJpeg(new Rect(0, 0, w, h), 100, jpeg);
        Bitmap raw = BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size());
        if (raw == null) return null;

        // 2) 先按 rotationDegrees 转正
        Bitmap upright = raw;
        if (rotation != 0) {
            Matrix rot = new Matrix();
            rot.postRotate(rotation);
            upright = Bitmap.createBitmap(raw, 0, 0, w, h, rot, true);
        }
        // 3) 再在正立图上水平镜像（前置摄像头 = 照镜子，与 PreviewView 一致）
        Matrix mirror = new Matrix();
        mirror.postScale(-1f, 1f, upright.getWidth() / 2f, upright.getHeight() / 2f);
        return Bitmap.createBitmap(upright, 0, 0, upright.getWidth(), upright.getHeight(), mirror, true);
    }

    // ---------------- 录入流程（glance EnrollStepView + OnboardingController 九姿态）----------------

    private void beginEnroll() {
        if (engine == null) { Toast.makeText(this, "人脸引擎不可用", Toast.LENGTH_SHORT).show(); return; }
        beginFlowTransition();   // 作废上一流程遗留的延时回调（如扫描成功后的收回药丸）
        stopFrameLoop();
        if (rebuildOverlay != null) rebuildOverlay.setVisibility(View.GONE); // 确保重建覆盖层关闭
        pendingName = null;
        enrollSamples.clear();
        enrollGeoAssets.clear();
        enrollPoseNames.clear();
        lastMesh3d = null;
        rebuildDone = false;
        pendingConfirm = false;
        if (reconstructor != null) reconstructor.reset(); // 清空上一次的重建帧
        capturedCount = 0;
        nextSampleAtPoints = POINTS_PER_SAMPLE;
        lastCaptureAtMs = 0;
        matchStreak = 0;
        enrollStartedAt = System.currentTimeMillis();
        frameReadyAt = enrollStartedAt + INITIAL_CAPTURE_DELAY_MS;
        flow = Flow.ENROLL;

        showStep(Step.ENROLL);
        enrollRing.reset();
        checkmark.hide();
        holeOverlay.openHole();
        enrollInstruction.setText("");   // 无任何指引文字：看模型哪里没亮，就转向哪里
        enrollInstruction.setVisibility(View.GONE);
        islandView.showEnrollModel();     // 灵动岛展开：512 点人脸轮廓，光点全部隐藏
        startFrameLoop();
    }

    private void cancelEnroll() {
        beginFlowTransition();
        stopFrameLoop();
        if (rebuildOverlay != null) rebuildOverlay.setVisibility(View.GONE);
        if (reconstructor != null) reconstructor.cancel();
        nameInput.setText("");
        nameError.setVisibility(View.GONE);
        showStep(store.count() > 0 ? Step.HOME : Step.WELCOME);
        islandView.showPill();
    }

    /**
     * 逐点探针扫描建模：头部朝向映射成模型上一根连续移动的探针，探到哪颗光点就点亮哪颗。
     *
     * 每帧把 yaw/pitch 连续映射到模型平面坐标 (probeX,probeY)，只点亮探针附近最近的少数几颗
     * 未亮光点（在点云视图内按距离错开时间依次冒出）——屏幕上就是"扫到哪、哪颗才亮"，
     * 一颗一颗累积，而不是整块区域一起亮。映射幅度调得灵敏：轻微抬头即可扫到额头，
     * 中等转头即到侧脸，避免额头两个上角因门槛过高而扫不进去。
     * 512 点全部点亮即建模完成。没有任何定时自增、没有转头引导文字。
     */
    private void handleEnrollFrame(FaceEngine.Detection det) {
        if (!det.hasFace || det.embedding == null) return;

        // 3D 人脸轮廓实时跟随真人的头：朝向 yaw/pitch + 鼻尖在取景框中的位置
        // （landmarks 来自转正+镜像帧，坐标与预览一致；放在首帧延迟之前，轮廓一出现就跟手）
        float faceX = 0.5f, faceY = 0.5f;
        if (det.landmarks != null && !det.landmarks.isEmpty()) {
            com.google.mediapipe.tasks.components.containers.NormalizedLandmark nose = det.landmarks.get(1);
            faceX = nose.x();
            faceY = nose.y();
        }
        islandView.updateHeadPose(det.yaw, det.pitch, faceX, faceY);

        // 首帧延迟：避开刚进入时的眨眼/未对准帧
        if (System.currentTimeMillis() < frameReadyAt) return;

        // 连续探针映射：以真机正视中值为基准，转头/抬头幅度灵敏地映射到模型平面。
        //   relYaw>0(向左转) → 探针往左(probeX 减小)；relPitch<0(抬头) → 探针往上(probeY 减小)。
        //   灵敏度（KX/KY）刻意调高：轻微抬头就够到额头、中等转头就到侧脸边缘。
        float relYaw = det.yaw - YAW_BIAS;
        float relPitch = det.pitch - PITCH_BIAS;
        float probeX = clamp(0.5f - relYaw * PROBE_KX, 0f, 1f);
        float probeY = clamp(0.5f + relPitch * PROBE_KY, 0f, 1f);

        int newlyLit = islandView.sweepModel(probeX, probeY);

        // 采集 3D 资产：按真实点亮数节流，每多积累 POINTS_PER_SAMPLE 个点采一帧，
        // 覆盖各个扫描角度即可，供识别库使用（与点亮完全同源，不会凭空多采）。
        if (newlyLit > 0 && islandPointsLit() >= nextSampleAtPoints) {
            captureEnrollSample(det);
            capturedCount++;
            nextSampleAtPoints += POINTS_PER_SAMPLE;
        }

        // 512 点全亮 → 建模完成
        if (islandView.isModelComplete()) {
            finishEnroll();
        }
    }

    /** 读取当前已点亮的真实光点数（用于资产采集节流）。 */
    private int islandPointsLit() {
        return islandView.getModelLitCount();
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** 采集一帧 3D 资产：512D 身份 + 完整 3D 几何 + 478 点 mesh。 */
    private void captureEnrollSample(FaceEngine.Detection det) {
        enrollSamples.add(det.embedding);
        if (det.feature != null) enrollGeoAssets.add(det.feature);
        enrollPoseNames.add("SCAN");
        if (det.landmarks != null && det.landmarks.size() >= 468) {
            float[] mesh = new float[det.landmarks.size() * 3];
            for (int i = 0; i < det.landmarks.size(); i++) {
                com.google.mediapipe.tasks.components.containers.NormalizedLandmark p = det.landmarks.get(i);
                mesh[i * 3] = p.x(); mesh[i * 3 + 1] = p.y(); mesh[i * 3 + 2] = p.z();
            }
            lastMesh3d = mesh;
            // 喂给端侧高斯重建器：每帧 478 点用 Horn 四元数对齐到规范坐标系后累积，
            // 采完后由深度融合管线（显著性加权/SDF/PDE黏性/对称补全）融合成完整 mesh。
            if (reconstructor != null) reconstructor.addFrame(mesh, det.yaw, det.pitch);
        }
    }

    private boolean poseMatches(float yaw, float pitch, Pose pose, boolean widened) {
        float factor = (widened ? STALL_WIDEN : 1f) * pose.leniency();
        return bandMatch(yaw, pose.yaw, YAW_INNER, YAW_CENTER, YAW_OUTER, factor)
                && bandMatch(pitch, pose.pitch, PITCH_INNER, PITCH_CENTER, PITCH_OUTER, factor);
    }

    private boolean bandMatch(float val, Pose.Band band, float inner, float center, float outer, float factor) {
        switch (band) {
            case NONE:  return Math.abs(val) < center * factor;
            // 修复：卡住放宽时上限 outer 也要乘以 factor，否则用户转过头仍采不到。
            case LEFT:  return val > inner / factor && val < outer * factor;
            case RIGHT: return val < -inner / factor && val > -outer * factor;
            case UP:    return val < -inner / factor && val > -outer * factor;
            case DOWN:  return val > inner / factor && val < outer * factor;
            default: return false;
        }
    }

    private static float normAngle(float deg) {
        deg %= 360f;
        return deg < 0 ? deg + 360f : deg;
    }

    /**
     * 完成序列：光点全亮=扫描完毕 → 震动 → 蓝光绕白圈转一圈 →
     * 【3D 重建放到后台静默运行】→ 直接进入命名页，不弹全屏重建界面。
     */
    private void finishEnroll() {
        stopFrameLoop();
        vibrate();                       // 扫描完毕震一下
        enrollInstruction.setVisibility(View.GONE);

        // 蓝光围着白色圆圈缓慢转一整圈，白色 tick 逐渐变蓝
        long sweepMs = 1200L;
        enrollRing.startBlueSweep(sweepMs, () -> {
            mainHandler.post(() -> {
                vibrate();
                startBackgroundRebuild();   // 后台全速重建，不展示界面
                enterNameStep();            // 直接进命名页
            });
        });
    }

    /**
     * 后台静默重建：用真实采集的多帧点云全速融合出完整 3D mesh（不放可视化节奏、不弹界面）。
     * 完成后把融合 mesh 写入 lastMesh3d 供入库使用；若用户已提交名字则补做落库。
     */
    private void startBackgroundRebuild() {
        final int epoch = flowEpoch;
        rebuildDone = false;
        if (reconstructor == null || reconstructor.frameCount() == 0) {
            // 无帧可重建：用最后单帧 / 多帧几何均值兜底
            if (lastMesh3d == null || lastMesh3d.length < 468 * 3) {
                if (!enrollGeoAssets.isEmpty()) lastMesh3d = averageGeometries(enrollGeoAssets);
            }
            rebuildDone = true;
            if (pendingConfirm) commitPerson();
            return;
        }
        reconstructor.startRebuild(
                p -> { /* 后台重建无需进度展示 */ },
                fusedMesh -> {
                    if (epoch != flowEpoch) return;
                    boolean valid = fusedMesh != null && fusedMesh.length >= 468 * 3;
                    if (valid) {
                        lastMesh3d = fusedMesh;
                    } else if (!enrollGeoAssets.isEmpty()) {
                        float[] geoFallback = averageGeometries(enrollGeoAssets);
                        if (geoFallback != null) lastMesh3d = geoFallback;
                    }
                    rebuildDone = true;
                    if (pendingConfirm) commitPerson();   // 用户在重建完成前点了提交，现在补做落库
                });
    }

    /**
     * 扫描=采集高斯重建所需数据；这里进入独立全屏界面，用真实采集的多帧点云，
     * 直观展示「散乱点云 → 对齐 → 逐点融合成完整 3D 脸」的全过程。
     * 只有融合真正计算完成，"完成录入"按钮才可用——才算录入成功。
     */
    private void startRebuildVisual() {
        final int epoch = flowEpoch;
        final List<float[]> rawFrames = (reconstructor != null) ? reconstructor.getAlignedFrames() : new ArrayList<>();

        // 进入全屏重建界面
        rebuildPercent.setText("0%");
        rebuildBar.setProgress(0);
        rebuildStage.setText("正在对齐扫描点云");
        rebuildTitleText("正在重建 3D 人脸模型");
        btnRebuildDone.setVisibility(View.GONE);
        btnRebuildDone.setEnabled(false);
        btnRebuildDone.setAlpha(0.4f);
        reconstructionView.reset();
        reconstructionView.setFrames(rawFrames);
        islandView.showPill();   // 收起录入态灵动岛，避免命名界面顶部还挂着"正在建立3D模型"
        rebuildOverlay.setVisibility(View.VISIBLE); // 覆盖全屏，进入重建界面

        if (reconstructor == null || reconstructor.frameCount() == 0) {
            // 无帧可重建（异常兜底）：仍展示一个基于最后单帧的重建，稍后自动完成
            if (lastMesh3d != null && lastMesh3d.length >= 468*3) {
                reconstructionView.setFinal(lastMesh3d);
            }
            rebuildStage.setText("重建完成");
            rebuildPercent.setText("100%");
            rebuildBar.setProgress(100);
            enableRebuildDone(epoch);
            return;
        }

        reconstructor.startRebuildVisual(
                (name, idx) -> {
                    if (epoch != flowEpoch) return;
                    rebuildStage.setText(name);
                },
                (mesh, p) -> {
                    if (epoch != flowEpoch) return;
                    reconstructionView.applySnapshot(mesh, p);
                    int pct = Math.round(p * 100f);
                    rebuildPercent.setText(pct + "%");
                    rebuildBar.setProgress(pct);
                },
                fusedMesh -> {
                    if (epoch != flowEpoch) return;
                    boolean valid = fusedMesh != null && fusedMesh.length >= 468*3;
                    if (valid) lastMesh3d = fusedMesh;   // 重建出的完整 478 点 3D mesh 入库
                    // 老数据兜底：重建结果无效时，用多帧真实几何平均出的 mesh
                    if (!valid && !enrollGeoAssets.isEmpty()) {
                        float[] geoFallback = averageGeometries(enrollGeoAssets);
                        if (geoFallback != null) { lastMesh3d = geoFallback; fusedMesh = geoFallback; }
                    }
                    if (fusedMesh != null) reconstructionView.setFinal(fusedMesh);
                    rebuildStage.setText("3D 模型重建完成");
                    rebuildPercent.setText("100%");
                    rebuildBar.setProgress(100);
                    vibrate();
                    enableRebuildDone(epoch);
                });
    }

    /** 多帧真实几何融合兜底（鼻尖为原点、眼距为尺度的协议坐标系）。 */
    private float[] averageGeometries(List<float[]> geos) {
        if (geos == null || geos.isEmpty()) return null;
        int n = geos.size();
        float[] out = new float[468*3];
        for (float[] g : geos) {
            if (g == null || g.length < 468*3) continue;
            for (int i = 0; i < 468*3; i++) out[i] += g[i];
        }
        for (int i = 0; i < 468*3; i++) out[i] /= n;
        return out;
    }

    private void enableRebuildDone(final int epoch) {
        mainHandler.post(() -> {
            if (epoch != flowEpoch) return;
            btnRebuildDone.setVisibility(View.VISIBLE);
            btnRebuildDone.setEnabled(true);
            btnRebuildDone.setAlpha(1f);
        });
    }

    private void rebuildTitleText(String s) {
        TextView t = findViewById(R.id.rebuildTitle);
        if (t != null) t.setText(s);
    }

    /** 重建完成、用户点"完成录入"后进入命名（原 showStep(Step.NAME) 逻辑抽出）。 */
    private void enterNameStep() {
        stopFrameLoop();
        holeOverlay.collapseHole();
        enrollInstruction.setText("");
        islandView.showPill();   // 兜底：确保进入命名时灵动岛已收回药丸
        showStep(Step.NAME);
        nameInput.setText("");
        nameError.setVisibility(View.GONE);
        nameInput.requestFocus();
    }

    /** glance confirmName：校验非空 + 重名，然后提交 3D 资产。 */
    private void confirmName() {
        String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            nameError.setText("请输入姓名");
            nameError.setVisibility(View.VISIBLE);
            return;
        }
        for (FaceStore.Person p : store.load()) {
            if (p.name.equals(name)) {
                nameError.setText("「" + name + "」已录入，请换一个名字");
                nameError.setVisibility(View.VISIBLE);
                return;
            }
        }
        nameError.setVisibility(View.GONE);
        pendingName = name;

        // 3D 重建在后台跑：若还没融合完成，先等它完成再用完整 mesh 入库
        if (!rebuildDone) {
            pendingConfirm = true;
            nameError.setText("正在生成 3D 模型…");
            nameError.setVisibility(View.VISIBLE);
            return;   // 重建完成回调里会调用 commitPerson() 继续
        }
        commitPerson();
    }

    /** 把当前录入的 3D 人脸资产落库并走完成动画（重建完成后调用）。 */
    private void commitPerson() {
        if (pendingName == null) return;
        final int epoch = flowEpoch;
        pendingConfirm = false;
        if (nameError != null) nameError.setVisibility(View.GONE);

        // 3D 人脸资产落库：512D 身份 + 多角度 3D 几何 + 后台重建出的完整 mesh
        store.upsert(pendingName, new ArrayList<>(enrollSamples),
                new ArrayList<>(enrollGeoAssets), lastMesh3d);
        refreshCount();
        vibrate();

        showStep(Step.COMPLETE); // glance CompleteStepView
        completeCheck.setColor(0xFF1C1C1E); // 白黑配色深色对勾（白底上白色看不见）
        completeCheck.play();
        islandView.showEnrolled(pendingName);

        mainHandler.postDelayed(() -> {
            if (epoch == flowEpoch) showStep(Step.HOME);
        }, 2600);
    }

    // ---------------- 扫描流程（glance NotchOverlay 三态：pill → progress → success/fail）----------------

    private void startScan() {
        if (engine == null) { Toast.makeText(this, "人脸引擎不可用", Toast.LENGTH_SHORT).show(); return; }
        if (store.count() == 0) {
            Toast.makeText(this, R.string.not_enrolled, Toast.LENGTH_LONG).show();
            return;
        }
        beginFlowTransition();   // 作废上一次识别/录入遗留的收尾回调，避免灵动岛被旧任务抢回去
        stopFrameLoop();
        flow = Flow.SCAN;
        scanMatchCount = 0;
        scanCandidate = null;
        scanVotes.clear();
        liveness.reset();
        faceAnim.setState(FaceIdAnimationView.State.SCAN);
        homeStatus.setText("识别中…");
        homeTip.setText("");
        islandView.showProgress();
        showScrim(true);
        startFrameLoop();
    }

    private void handleScanFrame(FaceEngine.Detection det) {
        if (!det.hasFace || det.embedding == null) {
            homeStatus.setText(getString(R.string.no_face));
            return;
        }
        // 静默活体：每帧用 478 关键点累积真人证据（眨眼 / 微动），照片不会累积
        liveness.update(det.landmarks);

        // 3D 联合比对：512D 身份 + 478 点 3D 几何，每次识别都参与
        FaceStore.Match best = store.findBest(det.embedding, det.feature);
        if (best == null) { failFlow(R.string.not_enrolled); return; }

        // 扫描判定加固：身份主门限 + 累积投票，不再依赖"连续 2 帧恰好同一人"
        // （多人库里 findBest 每帧最佳可能跳变，旧逻辑会永远到不了连续帧数）。
        // 门限内每一票都累计，谁先拿到 SCAN_MATCH_FRAMES 票就判谁；离门限很近的帧也算弱票。
        boolean embOk = best.distance < FaceStore.DIST_THRESHOLD;
        boolean geoReject = det.feature != null && best.geoDistance > FaceStore.GEO_THRESHOLD * 3f;
        if (embOk && !geoReject) {
            int votes = scanVotes.getOrDefault(best.name, 0) + 1;
            scanVotes.put(best.name, votes);
            scanCandidate = best.name;
            scanMatchCount = votes;
            // Face ID 式无感通过：身份 + 3D 几何高置信匹配即判成功，不要求任何刻意动作。
            // 真人在多朝向 3D 资产上的稳定匹配本身已是很强证据，票够就直接过、秒识别。
            // 活体检测只作为低置信/临界情况下的辅助门槛：置信不够时才需要眨眼或微动补强，
            // 这样正常看一眼就成功（准确率拉满），而照片/翻拍的临界匹配仍会被活体拦下。
            boolean strong = best.confidence >= 0.60f && !geoReject;
            if (votes >= SCAN_MATCH_FRAMES && (strong || liveness.isLive())) successScan(best);
        } else {
            // 未过门限的帧不清零全部投票（避免偶发一帧抖动毁掉累积），仅缓慢衰减领先者
            if (scanMatchCount > 0) scanMatchCount--;
        }
    }

    private void successScan(FaceStore.Match match) {
        stopFrameLoop();
        // 成功/失败反馈统一由顶部灵动岛卡片弹出，面板内不再重复播放对勾动画
        homeStatus.setText("已识别 · " + match.name);
        homeTip.setText("");
        islandView.showSuccess(match.name);
        vibrate();
        finishFlow();
    }

    private void failFlow(int msgRes) {
        stopFrameLoop();
        homeStatus.setText(msgRes);
        homeTip.setText("");
        islandView.showFail(getString(msgRes));
        finishFlow();
    }

    // ---------------- 人员管理 ----------------

    private void showManageDialog() {
        // 每次打开/删除后都重新读库，列表实时刷新（删除后原地消失，无需退出重进）
        new Runnable() {
            final int[] epoch = {0};
            @Override public void run() {
                final int myEpoch = ++epoch[0];
                List<FaceStore.Person> persons = store.load();
                if (persons.isEmpty()) {
                    Toast.makeText(MainActivity.this, R.string.no_persons, Toast.LENGTH_SHORT).show();
                    return;
                }
                List<String> labels = new ArrayList<>();
                int[] indices = new int[persons.size()];
                for (int i = 0; i < persons.size(); i++) {
                    labels.add(persons.get(i).name + " · " + persons.get(i).embeddings.size() + " 组 3D 资产");
                    indices[i] = i;
                }
                UiDialogs.showManageDialog(MainActivity.this, labels, indices, idx -> {
                    FaceStore.Person p = persons.get(idx);
                    UiDialogs.showConfirm(MainActivity.this, "删除此人", "确定删除「" + p.name + "」的 3D 档案吗？", () -> {
                        store.delete(p.name);
                        refreshCount();
                        Toast.makeText(MainActivity.this, "已删除 " + p.name, Toast.LENGTH_SHORT).show();
                        // 原地重建列表：关掉旧弹窗，立刻用最新库重新打开 → 被删的人马上消失
                        UiDialogs.dismissManage();
                        if (store.count() > 0) run();
                    });
                }, name -> {
                    Intent intent = new Intent(MainActivity.this, MeshViewerActivity.class);
                    intent.putExtra("name", name);
                    startActivity(intent);
                });
            }
        }.run();
    }

    // ---------------- 通用 ----------------

    private Runnable timeoutTask;

    /**
     * 开启取帧：现在检测完全由后台 analyzeFrame 驱动（在主线程之外），
     * 这里只置位开关并挂一个超时保护，不再有主线程 detect 轮询。
     */
    private void startFrameLoop() {
        capturing = true;
        lastDetectAt = 0;
        final long limit = flow == Flow.ENROLL ? 90000 : 12000;
        final long start = System.currentTimeMillis();
        if (timeoutTask != null) mainHandler.removeCallbacks(timeoutTask);
        timeoutTask = () -> {
            if (flow == Flow.NONE) return;
            if (System.currentTimeMillis() - start < limit) {
                mainHandler.postDelayed(this.timeoutTask, 200);
                return;
            }
            if (flow == Flow.ENROLL && enrollSamples.size() > 0) finishEnroll();
            else if (flow == Flow.SCAN && liveness != null && !liveness.isLive()
                    && scanMatchCount > 0) failFlow(R.string.liveness_fail);  // 身份匹配但活体未过=疑似照片
            else failFlow(R.string.failed);
        };
        mainHandler.postDelayed(timeoutTask, 200);
    }

    private void stopFrameLoop() {
        flow = Flow.NONE;
        capturing = false;
        if (timeoutTask != null) { mainHandler.removeCallbacks(timeoutTask); timeoutTask = null; }
    }

    private void finishFlow() {
        // 用代次校验兜底：即使这段延时侥幸没被新流程清空，只要用户已经切到别的流程，
        // 这里也不会再把灵动岛收回药丸，避免"扫描成功立刻点录入时卡片弹不出来"。
        postFlowAction(2600, () -> {
            faceAnim.setState(FaceIdAnimationView.State.IDLE);
            homeStatus.setText("");
            homeTip.setText("");
            islandView.showPill();
            showScrim(false);
        });
    }

    private void showScrim(boolean show) {
        // 已按需求去掉灵动岛展开时的全屏压暗遮罩：始终隐藏，背景保持白色。
        // 保留方法签名，避免改动各处调用逻辑。
        if (scrimView == null) return;
        scrimView.setVisibility(View.GONE);
        scrimView.setAlpha(0f);
    }

    private void refreshCount() {
        int n = store.count();
        homeCount.setText(n == 0 ? "" : "已录入 " + n + " 人");
    }

    // 最近一次震动调用诊断（供自检弹窗显示，纯内存不落库）
    private String lastVibrateDiag = "尚未调用";

    /**
     * 触觉反馈（单次短震，覆盖所有机型兼容死角）。
     * 要点：
     *  1) 不再用 hasVibrator() 做硬性拦截——部分定制 ROM 会误报 false，导致直接 return 不震动；
     *  2) 不再用 DEFAULT_AMPLITUDE(-1)——部分 ROM 会把 -1 当成"不震动"忽略掉，改用明确振幅；
     *  3) 单次短震（约 90ms），只震一下，干净利落，不做震-停-震的连震；
     *  4) 多档 API 兜底，任何一档抛异常都继续尝试下一档，绝不因异常静默失效。
     */
    private void vibrate() {
        // 单次短震：立即震 90ms 后停止，只震一下
        long[] pattern = {0, 90};
        int[] amplitudes = {0, 220}; // 明确振幅，避开 -1(DEFAULT_AMPLITUDE)
        doVibrate(pattern, amplitudes, 90L);
    }

    /** 自检用：让触点尽可能强、尽可能长，方便用户立刻确认手机是否支持震动。 */
    private void vibrateStrong() {
        doVibrate(new long[]{0, 300, 120, 300, 120, 400},
                  new int[]{0, 255, 0, 255, 0, 255}, 1240L);
    }

    /** 统一的震动执行：多 API 分档 + 每档 try-catch 兜底，绝不因单一档失败而全程失效。 */
    private void doVibrate(long[] pattern, int[] amplitudes, long fallbackDuration) {
        StringBuilder diag = new StringBuilder();
        diag.append("安卓 ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        boolean anyVibrator = false;
        // 第 1 档：Android 12+ 通过 VibratorManager 取默认震动器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    Vibrator v = vm.getDefaultVibrator();
                    if (v != null) {
                        anyVibrator = true;
                        diag.append("默认震动器: ").append(v.hasVibrator() ? "有" : "无").append("\n");
                        if (playVibration(v, pattern, amplitudes, fallbackDuration)) {
                            lastVibrateDiag = diag + "调用结果: 已发送震动指令(波形)\n" + mediaVolumeLine();
                            return;
                        }
                    }
                }
            } catch (Throwable t) { diag.append("12+通道异常: ").append(t.getClass().getSimpleName()).append("\n"); }
        }
        // 第 2 档：传统 VIBRATOR_SERVICE
        try {
            //noinspection deprecation
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                anyVibrator = true;
                if (playVibration(v, pattern, amplitudes, fallbackDuration)) {
                    lastVibrateDiag = diag + "调用结果: 已发送震动指令(传统通道)\n" + mediaVolumeLine();
                    return;
                }
            }
        } catch (Throwable t) { diag.append("传统通道异常: ").append(t.getClass().getSimpleName()).append("\n"); }
        // 第 3 档：最老的裸 API 兜底（无振幅、无 effect）
        try {
            //noinspection deprecation
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                //noinspection deprecation
                v.vibrate(fallbackDuration);
                lastVibrateDiag = diag + "调用结果: 已发送震动指令(裸API兜底)\n" + mediaVolumeLine();
                return;
            }
        } catch (Throwable t) { diag.append("裸API异常: ").append(t.getClass().getSimpleName()).append("\n"); }
        lastVibrateDiag = diag.append("调用结果: ").append(anyVibrator ? "未找到可用震动通道" : "系统报告无震动器").append("\n").append(mediaVolumeLine()).toString();
    }

    /** 诊断行：铃声音量，帮助判断是否为系统静音/勿扰导致无声无震。 */
    private String mediaVolumeLine() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int mode = am.getRingerMode();
                String m = mode == android.media.AudioManager.RINGER_MODE_SILENT ? "静音"
                        : (mode == android.media.AudioManager.RINGER_MODE_VIBRATE ? "震动" : "响铃");
                return "系统铃声模式: " + m;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /** 自检弹窗：显示震动诊断信息，便于用户判断是代码、安装包还是系统设置的问题。 */
    private void showVibrationDiag() {
        String info = "【震动自检诊断】\n\n" + lastVibrateDiag
                + "\n说明：若显示『已发送震动指令』但手机无反应，通常是系统层面的限制——"
                + "请检查：① 是否开启勿扰/静音模式 ② 省电模式是否限制震动 ③ 系统设置里该 App 的震动/通知权限 ④ 手机是否支持震动马达。";
        new AlertDialog.Builder(this)
                .setTitle("震动自检 v3.9.0 震动版")
                .setMessage(info)
                .setPositiveButton("再震一次", (d, w) -> { vibrateStrong(); showVibrationDiag(); })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 在单个 Vibrator 上播放，优先振幅波形，退而求其次用普通波形，最后是单次。 */
    private boolean playVibration(Vibrator v, long[] pattern, int[] amplitudes, long fallbackDuration) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // Android 8.0+
                // 振幅波形需要硬件支持，不支持会抛异常，捕获后退回普通波形
                try {
                    v.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1));
                    return true;
                } catch (Throwable ignored) { }
                try {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                    return true;
                } catch (Throwable ignored) { }
                v.vibrate(VibrationEffect.createOneShot(fallbackDuration, 200));
                return true;
            } else {
                //noinspection deprecation
                v.vibrate(pattern, -1);
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopFrameLoop();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (engine != null) engine.close();
    }
}
