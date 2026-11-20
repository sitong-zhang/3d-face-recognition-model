package com.example.face3d;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面（自研交互逻辑）。
 * - 录入：命名 -> 按 Face ID 风格引导缓慢转头，多角度采集 3D 特征 -> 保存
 * - 扫描：实时检测 -> 与库中所有人比对 -> 成功显示姓名 + 绿勾动画
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;
    private static final float SIM_THRESHOLD = 0.985f; // 3D 几何余弦相似度阈值
    private static final int ENROLL_TARGET_FRAMES = 5; // 多角度采集帧数
    private static final int SCAN_MATCH_FRAMES = 2;    // 连续匹配确认帧数

    private PreviewView previewView;
    private FaceIdAnimationView faceAnim;
    private TextView statusText, nameText, tipText, enrollCount;
    private Button btnEnroll, btnScan, btnManage;
    private LinearLayout manageBar;

    private FaceEngine engine;
    private FaceStore store;
    private ExecutorService cameraExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Bitmap latestFrame;
    private volatile boolean capturing = false;

    private enum Flow { NONE, ENROLL, SCAN }
    private Flow flow = Flow.NONE;

    // 录入过程状态
    private String pendingName;
    private final List<float[]> enrollSamples = new ArrayList<>();
    private long lastCaptureMs = 0;

    // 扫描过程状态
    private String scanCandidate;
    private int scanMatchCount = 0;

    private Runnable frameLoop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        faceAnim = findViewById(R.id.faceAnim);
        statusText = findViewById(R.id.statusText);
        nameText = findViewById(R.id.nameText);
        tipText = findViewById(R.id.tipText);
        enrollCount = findViewById(R.id.enrollCount);
        btnEnroll = findViewById(R.id.btnEnroll);
        btnScan = findViewById(R.id.btnScan);
        btnManage = findViewById(R.id.btnManage);
        manageBar = findViewById(R.id.manageBar);

        cameraExecutor = Executors.newSingleThreadExecutor();
        store = new FaceStore(this);
        try {
            engine = new FaceEngine(this);
        } catch (Exception e) {
            Toast.makeText(this, "人脸引擎初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        refreshCount();
        faceAnim.setState(FaceIdAnimationView.State.IDLE);

        btnEnroll.setOnClickListener(v -> showNameDialog());
        btnScan.setOnClickListener(v -> startScan());
        btnManage.setOnClickListener(v -> showManageDialog());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_CAMERA && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            statusText.setText("需要相机权限");
            tipText.setText("请在设置中授予相机权限以使用 3D 人脸功能");
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
            } catch (Exception e) {
                statusText.setText("相机启动失败");
                tipText.setText(e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        try {
            if (capturing && latestFrame == null) latestFrame = toBitmap(image);
        } catch (Exception ignored) { }
        finally {
            image.close();
        }
    }

    private Bitmap toBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer y = planes[0].getBuffer();
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int ySize = y.remaining(), uSize = u.remaining(), vSize = v.remaining();
        byte[] data = new byte[ySize + uSize + vSize];
        y.get(data, 0, ySize);
        u.get(data, ySize, uSize);
        v.get(data, ySize + uSize, vSize);
        int w = image.getWidth(), h = image.getHeight();
        YuvImage yuv = new YuvImage(data, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, w, h), 85, out);
        Bitmap bmp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size());
        Matrix m = new Matrix();
        m.postScale(-1, 1, bmp.getWidth() / 2f, bmp.getHeight() / 2f); // 前置镜像
        m.postRotate(270, bmp.getWidth() / 2f, bmp.getHeight() / 2f);
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
    }

    // ---------------- 录入流程 ----------------

    private void showNameDialog() {
        if (engine == null) { Toast.makeText(this, "人脸引擎不可用", Toast.LENGTH_SHORT).show(); return; }
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.enroll_name_hint);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.enroll_title)
                .setView(input)
                .setPositiveButton(R.string.start_enroll, (d, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) { Toast.makeText(this, R.string.name_empty, Toast.LENGTH_SHORT).show(); return; }
                    beginEnroll(name);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void beginEnroll(String name) {
        pendingName = name;
        enrollSamples.clear();
        flow = Flow.ENROLL;
        setUiBusy(true);
        faceAnim.setState(FaceIdAnimationView.State.ENROLL);
        faceAnim.setEnrollProgress(0f);
        statusText.setText(R.string.enrolling);
        tipText.setText(R.string.guide_face_center);
        nameText.setVisibility(android.view.View.GONE);
        startFrameLoop();
    }

    /** 录入：每隔一段时间取一帧，姿态变化足够大则采集，推进引导进度。 */
    private void handleEnrollFrame(FaceEngine.Detection det) {
        if (!det.hasFace) {
            tipText.setText(R.string.no_face);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCaptureMs < 350) return; // 采集间隔

        // 判断是否与已采样的姿态足够不同（多角度）
        boolean distinct = true;
        for (float[] s : enrollSamples) {
            if (FaceEngine.similarity(det.feature, s) > 0.997f) { distinct = false; break; }
        }
        if (!distinct) {
            tipText.setText(R.string.guide_finish);
            return;
        }

        enrollSamples.add(det.feature);
        lastCaptureMs = now;
        int n = enrollSamples.size();
        faceAnim.setEnrollProgress((float) n / ENROLL_TARGET_FRAMES);
        updateEnrollGuide(n, det);

        if (n >= ENROLL_TARGET_FRAMES) {
            finishEnroll();
        }
    }

    private void updateEnrollGuide(int n, FaceEngine.Detection det) {
        switch (n) {
            case 1:
                tipText.setText(det.yaw > 0 ? R.string.guide_turn_right : R.string.guide_turn_left);
                break;
            case 2:
                tipText.setText(det.yaw < 0 ? R.string.guide_turn_right : R.string.guide_turn_left);
                break;
            case 3:
                tipText.setText(det.pitch > 0 ? R.string.guide_lookdown : R.string.guide_lookup);
                break;
            case 4:
                tipText.setText(R.string.guide_finish);
                break;
            default:
                tipText.setText(R.string.tip_enroll);
        }
    }

    private void finishEnroll() {
        stopFrameLoop();
        store.upsert(pendingName, new ArrayList<>(enrollSamples));
        refreshCount();
        faceAnim.setState(FaceIdAnimationView.State.SUCCESS);
        statusText.setText(R.string.enroll_success);
        nameText.setText(pendingName);
        nameText.setVisibility(android.view.View.VISIBLE);
        tipText.setText("已采集 " + enrollSamples.size() + " 个角度的 3D 特征");
        vibrate();
        finishFlow();
    }

    // ---------------- 扫描流程 ----------------

    private void startScan() {
        if (engine == null) { Toast.makeText(this, "人脸引擎不可用", Toast.LENGTH_SHORT).show(); return; }
        if (store.count() == 0) {
            Toast.makeText(this, R.string.not_enrolled, Toast.LENGTH_LONG).show();
            return;
        }
        flow = Flow.SCAN;
        scanMatchCount = 0;
        scanCandidate = null;
        setUiBusy(true);
        faceAnim.setState(FaceIdAnimationView.State.SCAN);
        statusText.setText(R.string.scanning);
        tipText.setText(R.string.tip_scan);
        nameText.setVisibility(android.view.View.GONE);
        startFrameLoop();
    }

    private void handleScanFrame(FaceEngine.Detection det) {
        if (!det.hasFace) {
            tipText.setText(R.string.no_face);
            return;
        }
        FaceStore.Match best = store.findBest(det.feature);
        if (best == null) { failFlow(R.string.not_enrolled); return; }

        tipText.setText("最佳相似度 " + String.format(Locale.US, "%.3f", best.similarity));
        if (best.similarity >= SIM_THRESHOLD) {
            if (scanCandidate != null && scanCandidate.equals(best.name)) {
                scanMatchCount++;
            } else {
                scanCandidate = best.name;
                scanMatchCount = 1;
            }
            if (scanMatchCount >= SCAN_MATCH_FRAMES) {
                successScan(best);
            }
        } else {
            scanMatchCount = 0;
            scanCandidate = null;
        }
    }

    private void successScan(FaceStore.Match match) {
        stopFrameLoop();
        faceAnim.setState(FaceIdAnimationView.State.SUCCESS);
        statusText.setText(R.string.success);
        nameText.setText(match.name);
        nameText.setVisibility(android.view.View.VISIBLE);
        tipText.setText("3D 特征匹配 · 相似度 " + String.format(Locale.US, "%.3f", match.similarity));
        vibrate();
        finishFlow();
    }

    private void failFlow(int msgRes) {
        stopFrameLoop();
        faceAnim.setState(FaceIdAnimationView.State.FAIL);
        statusText.setText(msgRes);
        tipText.setText("请正对镜头重试");
        nameText.setVisibility(android.view.View.GONE);
        finishFlow();
    }

    // ---------------- 人员管理 ----------------

    private void showManageDialog() {
        List<FaceStore.Person> persons = store.load();
        if (persons.isEmpty()) {
            Toast.makeText(this, R.string.no_persons, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] items = new CharSequence[persons.size()];
        for (int i = 0; i < persons.size(); i++) {
            items[i] = persons.get(i).name + "（" + persons.get(i).features.size() + " 个角度）";
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.persons_header)
                .setItems(items, (d, which) -> {
                    FaceStore.Person p = persons.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.delete_confirm)
                            .setMessage(p.name)
                            .setPositiveButton(R.string.yes, (d2, w2) -> {
                                store.delete(p.name);
                                refreshCount();
                                Toast.makeText(this, "已删除 " + p.name, Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton(R.string.no, null)
                            .show();
                })
                .setPositiveButton(R.string.close, null)
                .show();
    }

    // ---------------- 通用 ----------------

    /** 帧循环：持续抓帧并分派给当前流程，带超时保护。 */
    private void startFrameLoop() {
        capturing = true;
        latestFrame = null;
        final long start = System.currentTimeMillis();
        frameLoop = new Runnable() {
            @Override
            public void run() {
                if (flow == Flow.NONE) return;
                Bitmap frame = latestFrame;
                if (frame != null) {
                    latestFrame = null;
                    FaceEngine.Detection det = engine.detect(frame);
                    if (flow == Flow.ENROLL) handleEnrollFrame(det);
                    else if (flow == Flow.SCAN) handleScanFrame(det);
                }
                // 超时保护：录入 18s / 扫描 12s
                long limit = flow == Flow.ENROLL ? 18000 : 12000;
                if (System.currentTimeMillis() - start > limit && flow != Flow.NONE) {
                    if (flow == Flow.ENROLL && enrollSamples.size() > 0) finishEnroll();
                    else failFlow(R.string.failed);
                    return;
                }
                if (flow != Flow.NONE) mainHandler.postDelayed(this, 120);
            }
        };
        mainHandler.post(frameLoop);
    }

    private void stopFrameLoop() {
        flow = Flow.NONE;
        capturing = false;
        latestFrame = null;
        if (frameLoop != null) mainHandler.removeCallbacks(frameLoop);
    }

    private void finishFlow() {
        setUiBusy(true);
        mainHandler.postDelayed(() -> {
            faceAnim.setState(FaceIdAnimationView.State.IDLE);
            statusText.setText(R.string.hint);
            tipText.setText("点击下方按钮开始");
            nameText.setVisibility(android.view.View.GONE);
            setUiBusy(false);
        }, 2200);
    }

    private void setUiBusy(boolean busy) {
        btnEnroll.setEnabled(!busy);
        btnScan.setEnabled(!busy);
        btnManage.setEnabled(!busy);
    }

    private void refreshCount() {
        int n = store.count();
        enrollCount.setText(n == 0 ? "尚未录入人脸" : "已录入 " + n + " 人 · 点击查看/管理");
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(60);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopFrameLoop();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (engine != null) engine.close();
    }
}
