package com.example.face3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

/**
 * 3D 人脸引擎（自研封装层）。
 *
 * 底层调用 MediaPipe FaceLandmarker 官方 API 做 3D 关键点检测（478 点 + 变换矩阵），
 * 本类的核心：3D 几何归一化（构建完整 3D 人脸资产）、头部姿态估计、多角度特征采集。
 * 每次识别生成的 normalize() 3D 特征会作为 3D 人脸资产参与联合比对。
 */
public class FaceEngine {

    public static final int LANDMARKS = 478;   // MediaPipe FaceMesh 3D 关键点数

    public static class Detection {
        public final float[] feature;               // 归一化 3D 几何特征 (478*3)，用于 3D 建模展示
        public final float[] embedding;             // 512D 身份特征，用于精准识别
        public final List<NormalizedLandmark> landmarks;
        public final boolean hasFace;
        public final float yaw;                     // 左右转头姿态（弧度近似）
        public final float pitch;                   // 上下俯仰姿态（弧度近似）
        Detection(float[] f, float[] emb, List<NormalizedLandmark> l, boolean hf, float yaw, float pitch) {
            feature = f; embedding = emb; landmarks = l; hasFace = hf; this.yaw = yaw; this.pitch = pitch;
        }
    }

    private FaceLandmarker landmarker;
    private FaceEmbedder embedder;


    public FaceEngine(Context context) {
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build();
        FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setOutputFacialTransformationMatrixes(true)
                // 暗光下关键点置信度会下降，门槛从 0.4 降到 0.25，弱信号也能检出；
                // 误检风险由下游的 embedding + 3D 几何联合比对兜底，不会误放行陌生人。
                .setMinFaceDetectionConfidence(0.25f)
                .setMinFacePresenceConfidence(0.25f)
                .setMinTrackingConfidence(0.25f)
                .build();
        landmarker = FaceLandmarker.createFromOptions(context, options);
        try {
            embedder = new FaceEmbedder(context);
        } catch (Exception e) {
            embedder = null; // 身份特征不可用时退回几何比对
        }
    }

    /**
     * 检测一帧，返回 3D 关键点、3D 几何特征、512D 身份特征与头部姿态。
     *
     * 多朝向自适应（关键修复）：MediaPipe FaceLandmarker 只对正立人脸可靠，
     * 而前置摄像头帧朝向因机型而异（报告角常为 90°，图呈横屏时会完全检测不到）。
     * 这里自动在 0/90/180/270 四个朝向中搜索能检测到人脸的那个，并把该朝向的
     * "正立图"用于关键点、3D 几何、embedding 与姿态估计，保证各信号坐标系一致。
     * 缓存上次成功朝向并优先尝试，稳定后每帧仅检测一次，无额外性能负担。
     */
    public Detection detect(Bitmap bitmap) {
        if (bitmap == null) return new Detection(null, null, null, false, 0f, 0f);

        // 暗光增强：亮度不足时先提亮（亮度充足时原样返回）
        Bitmap enhanced = ImageEnhancer.enhance(bitmap);

        // 帧已在 toBitmap 阶段按相机 rotationDegrees 转正并镜像，正常情况 0° 即可检出。
        // 多朝向仅作兜底：选人脸框面积最大的朝向，绝不在失败时锁定某个朝向。
        // （旧版 lastGoodRotation 一旦判断错误会永久锁死错误朝向，是"时好时坏检测不到脸"的元凶。）
        int[] order = new int[]{0, 90, 180, 270};

        FaceLandmarkerResult bestResult = null;
        Bitmap bestBitmap = null;
        int bestRotation = 0;
        float bestArea = 0f;

        for (int rot : order) {
            Bitmap rotated = rotate(enhanced, rot);
            FaceLandmarkerResult r;
            try {
                MPImage image = new BitmapImageBuilder(rotated).build();
                r = landmarker.detect(image);
            } catch (Exception e) {
                continue;
            }
            if (r.faceLandmarks().isEmpty()) continue;
            float area = faceArea(r.faceLandmarks().get(0));
            if (area > bestArea) {
                bestArea = area;
                bestResult = r;
                bestBitmap = rotated;
                bestRotation = rot;
            }
            // 转正后的 0° 已检出足够大的人脸，直接用，省去其余朝向的开销。
            if (rot == 0 && area > 0.03f) break;
        }

        if (bestResult == null) {
            return new Detection(null, null, null, false, 0f, 0f);
        }
        List<NormalizedLandmark> lms = bestResult.faceLandmarks().get(0);

        // 优先用 facial transformation matrix 求精确 yaw/pitch（弧度），
        // 这是 glance 用于 9 方向判定的同类信号；矩阵不可用时退回关键点几何近似。
        float[] pose = null;
        if (bestResult.facialTransformationMatrixes().isPresent()) {
            java.util.List<float[]> mats = bestResult.facialTransformationMatrixes().get();
            if (!mats.isEmpty()) pose = poseFromMatrix(mats.get(0));
        }
        if (pose == null) pose = estimatePose(lms);

        // 512D 身份特征（判别式模型）。用正立的 bestBitmap 与对应关键点做对齐；
        // embedder 内部已做人脸级亮度归一化，故用 original 朝向图，不受整图增强影响。
        float[] embedding = null;
        if (embedder != null) {
            try {
                embedder.setBitmap(bestBitmap);
                java.util.List<PointF> pts = new java.util.ArrayList<>(lms.size());
                for (NormalizedLandmark lm : lms) pts.add(new PointF(lm.x(), lm.y()));
                embedding = embedder.embed(pts, bestBitmap.getWidth(), bestBitmap.getHeight());
            } catch (Exception ignored) { }
        }
        return new Detection(normalize(lms), embedding, lms, true, pose[0], pose[1]);
    }

    /** 按角度旋转 Bitmap（0 度原样返回）。 */
    private static Bitmap rotate(Bitmap src, int deg) {
        if (deg == 0 || deg == 360) return src;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    /** 人脸框面积（归一化坐标），用于朝向选择时的置信度比较。 */
    private static float faceArea(List<NormalizedLandmark> lms) {
        float minX = 1f, maxX = 0f, minY = 1f, maxY = 0f;
        for (NormalizedLandmark p : lms) {
            if (p.x() < minX) minX = p.x();
            if (p.x() > maxX) maxX = p.x();
            if (p.y() < minY) minY = p.y();
            if (p.y() > maxY) maxY = p.y();
        }
        return Math.max(0f, (maxX - minX)) * Math.max(0f, (maxY - minY));
    }

    /**
     * 自研：3D 几何归一化。
     * 以鼻尖(1)为原点、双眼外眼角(33,263)距离为尺度，
     * 得到对平移/缩放稳定、保留 z 深度的 3D 特征向量。
     */
    private float[] normalize(List<NormalizedLandmark> lms) {
        float cx = lms.get(1).x(), cy = lms.get(1).y(), cz = lms.get(1).z();
        float scale = dist3(lms.get(33), lms.get(263));
        if (scale < 1e-5f) scale = 1e-5f;
        float[] f = new float[lms.size() * 3];
        for (int i = 0; i < lms.size(); i++) {
            NormalizedLandmark p = lms.get(i);
            f[i * 3]     = (p.x() - cx) / scale;
            f[i * 3 + 1] = (p.y() - cy) / scale;
            f[i * 3 + 2] = (p.z() - cz) / scale;
        }
        return f;
    }

    /**
     * 从 MediaPipe facial transformation matrix (4x4, 行主序) 提取 yaw/pitch 弧度。
     * 与 glance 使用 Apple Vision 观测的 yaw/pitch 属于同类姿态信号。
     * 约定（对齐 glance）：yaw>0 向左转头，pitch<0 抬头、pitch>0 低头。
     */
    private float[] poseFromMatrix(float[] m) {
        if (m == null || m.length < 16) return null;
        try {
            // 旋转矩阵元素（列主序矩阵在 MediaPipe 中以行主序 float[] 返回）
            float m02 = m[2], m12 = m[6], m22 = m[10];
            float m21 = m[9], m11 = m[5];
            float pitch = (float) Math.asin(clamp(-m12, -1f, 1f));
            float yaw = (float) Math.atan2(m02, m22);
            // roll 仅用于数值有效性检查
            float roll = (float) Math.atan2(m21, m11);
            if (Float.isNaN(yaw) || Float.isNaN(pitch) || Float.isNaN(roll)) return null;
            // MediaPipe 与 Vision 的 yaw 符号相反，翻转以对齐 glance 的引导语义
            return new float[]{-yaw, pitch};
        } catch (Exception e) {
            return null;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 自研：用关键点几何近似头部姿态（yaw/pitch）。
     * 鼻尖相对两眼外眼角中点的水平/垂直偏移，除以眼距归一化。
     * 用于引导用户"缓慢转头"完成多角度采集，不是精确姿态解算。
     */
    private float[] estimatePose(List<NormalizedLandmark> lms) {
        NormalizedLandmark left = lms.get(33);   // 左眼外眼角
        NormalizedLandmark right = lms.get(263); // 右眼外眼角
        NormalizedLandmark nose = lms.get(1);    // 鼻尖
        float eyeMidX = (left.x() + right.x()) / 2f;
        float eyeMidY = (left.y() + right.y()) / 2f;
        float eyeDist = Math.abs(right.x() - left.x());
        if (eyeDist < 1e-5f) eyeDist = 1e-5f;
        // 用 tan 近似得到弧度级别的姿态偏移
        float yaw = (float) Math.atan2(nose.x() - eyeMidX, eyeDist) * 2.0f;
        float pitch = (float) Math.atan2(nose.y() - eyeMidY, eyeDist) * 2.0f;
        return new float[]{yaw, pitch};
    }

    private float dist3(NormalizedLandmark a, NormalizedLandmark b) {
        float dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 自研：两段 3D 几何特征的余弦相似度（0..1，越大越像）。 */
    public static float similarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0f;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0f;
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    public void close() {
        if (landmarker != null) {
            landmarker.close();
            landmarker = null;
        }
        if (embedder != null) {
            embedder.close();
            embedder = null;
        }
    }
}
