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
 * 人脸引擎：封装 MediaPipe FaceLandmarker（原样调用官方 API，未改动算法）。
 *
 * 提供 3D 人脸能力：
 *  - 检测 + 478 个 3D 关键点（带 z 深度）
 *  - 录入：把 3D 关键点做归一化后作为"3D 几何特征"保存到本地
 *  - 扫描：把当前 3D 几何特征与录入的特征比对，判断是否同一人
 *
 * 诚实说明：这是基于 3D 几何的演示级比对，不是论文级身份识别模型。
 */
public class FaceEngine {

    public static class Detection {
        public final float[] feature;      // 归一化的 3D 几何特征 (478*3)
        public final List<NormalizedLandmark> landmarks;
        public final boolean hasFace;
        Detection(float[] f, List<NormalizedLandmark> l, boolean hf) {
            feature = f; landmarks = l; hasFace = hf;
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
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build();
        landmarker = FaceLandmarker.createFromOptions(context, options);
    }

    /** 检测一帧，返回 3D 关键点与归一化几何特征。 */
    public Detection detect(Bitmap bitmap) {
        MPImage image = new BitmapImageBuilder(bitmap).build();
        FaceLandmarkerResult result = landmarker.detect(image);
        if (result.faceLandmarks().isEmpty()) {
            return new Detection(null, null, false);
        }
        List<NormalizedLandmark> lms = result.faceLandmarks().get(0);
        return new Detection(normalize(lms), lms, true);
    }

    /**
     * 3D 几何归一化：以鼻尖(索引1)为原点、双眼外眼角(33,263)距离为尺度，
     * 得到对平移/缩放/近似旋转稳定的特征向量。
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

    private float dist3(NormalizedLandmark a, NormalizedLandmark b) {
        float dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 比对两段 3D 几何特征的相似度（余弦相似度，0..1，越大越像）。
     * 归一化后每人特征模长相近，余弦相似度对整体表情变化更鲁棒。
     */
    public static float similarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;
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
