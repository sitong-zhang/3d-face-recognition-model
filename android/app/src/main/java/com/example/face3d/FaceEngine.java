package com.example.face3d;

import android.content.Context;
import android.graphics.Bitmap;

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
 * 本类自研的部分：3D 几何归一化、头部姿态估计、多角度特征采集与余弦比对。
 *
 * 诚实说明：基于 3D 几何的演示级比对，不是论文级身份识别模型。
 */
public class FaceEngine {

    public static class Detection {
        public final float[] feature;               // 归一化 3D 几何特征 (478*3)
        public final List<NormalizedLandmark> landmarks;
        public final boolean hasFace;
        public final float yaw;                     // 左右转头姿态（弧度近似）
        public final float pitch;                   // 上下俯仰姿态（弧度近似）
        Detection(float[] f, List<NormalizedLandmark> l, boolean hf, float yaw, float pitch) {
            feature = f; landmarks = l; hasFace = hf; this.yaw = yaw; this.pitch = pitch;
        }
    }

    private FaceLandmarker landmarker;

    public FaceEngine(Context context) {
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build();
        FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setOutputFacialTransformationMatrixes(true)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build();
        landmarker = FaceLandmarker.createFromOptions(context, options);
    }

    /** 检测一帧，返回 3D 关键点、归一化特征与头部姿态。 */
    public Detection detect(Bitmap bitmap) {
        MPImage image = new BitmapImageBuilder(bitmap).build();
        FaceLandmarkerResult result = landmarker.detect(image);
        if (result.faceLandmarks().isEmpty()) {
            return new Detection(null, null, false, 0f, 0f);
        }
        List<NormalizedLandmark> lms = result.faceLandmarks().get(0);
        float[] pose = estimatePose(lms);
        return new Detection(normalize(lms), lms, true, pose[0], pose[1]);
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
    }
}
