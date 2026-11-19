package com.example.face3d;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 主界面：相机预览 + 录入/扫描 + 苹果 Face ID 风格成功动画。 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;
    private static final String PREF = "face3d_pref";
    private static final String KEY_FEATURE = "feature_3d";
    private static final float SIM_THRESHOLD = 0.985f; // 3D 几何余弦相似度阈值

    private PreviewView previewView;
    private FaceIdAnimationView faceAnim;
    private TextView statusText, tipText, enrollCount;
    private Button btnEnroll, btnScan;

    private FaceEngine engine;
    private ExecutorService cameraExecutor;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Bitmap latestFrame;
    private volatile boolean analyzing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        faceAnim = findViewById(R.id.faceAnim);
        statusText = findViewById(R.id.statusText);
        tipText = findViewById(R.id.tipText);
        enrollCount = findViewById(R.id.enrollCount);
        btnEnroll = findViewById(R.id.btnEnroll);
        btnScan = findViewById(R.id.btnScan);

        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            engine = new FaceEngine(this);
        } catch (Exception e) {
            Toast.makeText(this, "人脸引擎初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        updateEnrollCount();
        faceAnim.setState(FaceIdAnimationView.State.IDLE);

        btnEnroll.setOnClickListener(v -> runFlow(true));
        btnScan.setOnClickListener(v -> runFlow(false));

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

    /** 持续抓取最新一帧（intentional：真正的人脸检测在按钮点击时执行）。 */
    private void analyzeFrame(@NonNull ImageProxy image) {
        if (analyzing && latestFrame == null) {
            try {
                latestFrame = toBitmap(image);
            } catch (Exception ignored) { }
        }
        image.close();
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
        // 前置摄像头通常旋转 270°，这里做镜像+旋转以正向预览
        Matrix m = new Matrix();
        m.postScale(-1, 1, bmp.getWidth() / 2f, bmp.getHeight() / 2f);
        m.postRotate(270, bmp.getWidth() / 2f, bmp.getHeight() / 2f);
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
    }

    /** 统一流程：录入(true) 或 扫描(false)。 */
    private void runFlow(boolean enrollMode) {
        if (engine == null) {
            Toast.makeText(this, "人脸引擎不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        setButtonsEnabled(false);
        latestFrame = null;
        analyzing = true;
        faceAnim.setState(FaceIdAnimationView.State.SCANNING);
        statusText.setText(enrollMode ? "正在录入…" : "识别中…");
        tipText.setText(enrollMode ? R.string.tip_enroll : R.string.tip_scan);

        // 等待抓到一帧
        mainHandler.postDelayed(() -> {
            Bitmap frame = latestFrame;
            analyzing = false;
            if (frame == null) {
                fail("未获取到相机画面");
                return;
            }
            FaceEngine.Detection det = engine.detect(frame);
            if (!det.hasFace) {
                fail(getString(R.string.no_face));
                return;
            }
            if (enrollMode) {
                saveFeature(det.feature);
                updateEnrollCount();
                success(getString(R.string.enroll_success));
            } else {
                float[] saved = loadFeature();
                if (saved == null) {
                    fail(getString(R.string.not_enrolled));
                    return;
                }
                float sim = FaceEngine.similarity(saved, det.feature);
                boolean ok = sim >= SIM_THRESHOLD;
                statusText.setText("相似度: " + String.format(Locale.US, "%.3f", sim));
                if (ok) success(getString(R.string.success));
                else fail(getString(R.string.failed));
            }
        }, 900); // 约 0.9 秒扫描过程，接近 Face ID 体验
    }

    private void success(String msg) {
        faceAnim.setState(FaceIdAnimationView.State.SUCCESS);
        statusText.setText(msg);
        tipText.setText("3D 人脸验证通过");
        finishFlow();
    }

    private void fail(String msg) {
        faceAnim.setState(FaceIdAnimationView.State.FAIL);
        statusText.setText(msg);
        tipText.setText("请重试");
        finishFlow();
    }

    private void finishFlow() {
        mainHandler.postDelayed(() -> {
            faceAnim.setState(FaceIdAnimationView.State.IDLE);
            setButtonsEnabled(true);
        }, 2200);
    }

    private void setButtonsEnabled(boolean enabled) {
        btnEnroll.setEnabled(enabled);
        btnScan.setEnabled(enabled);
    }

    private void saveFeature(float[] f) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.5f", f[i]));
        }
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY_FEATURE, sb.toString()).apply();
    }

    private float[] loadFeature() {
        String s = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_FEATURE, null);
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split(",");
        float[] f = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) f[i] = Float.parseFloat(parts[i]);
        } catch (Exception e) {
            return null;
        }
        return f;
    }

    private void updateEnrollCount() {
        float[] f = loadFeature();
        enrollCount.setText(f == null ? "尚未录入人脸" : "已录入 1 张人脸（3D 特征 " + f.length + " 维）");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (engine != null) engine.close();
    }
}
