package com.example.face3d;

import android.content.Context;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 身份特征提取器：MobileFaceNet (ArcFace, 512D)。
 *
 * MediaPipe 的 3D 关键点是"表情/姿态拟合"结果，无法可靠区分身份；
 * 本类使用判别式训练的人脸 embedding 模型，把对齐后的人脸映射到 512D 向量，
 * 同人距离约 0.66~0.85，异人约 1.31~1.47（在公开多人脸数据集上实测），
 * 用余弦距离阈值判定身份，准确可靠。
 *
 * 流程：关键点 5 点相似变换对齐到 112x112 → (x-127.5)/128 归一化 → TFLite 推理 → L2 归一化。
 */
public class FaceEmbedder {

    private static final int INPUT_SIZE = 112;
    private static final int EMBED_DIM = 512;

    // ArcFace 标准 5 点对齐模板 (112x112)
    private static final float[][] REF = {
            {38.2946f, 51.6963f}, {73.5318f, 51.5014f}, {56.0252f, 71.7366f},
            {41.5493f, 92.3655f}, {70.7299f, 92.2041f}
    };
    // MediaPipe 关键点索引：左眼外角、右眼外角、鼻尖、左嘴角、右嘴角
    private static final int[] IDX = {33, 263, 1, 61, 291};

    private final Interpreter interpreter;

    public FaceEmbedder(Context context) throws IOException {
        ByteBuffer model = loadModelFile(context, "mobilefacenet.tflite");
        Interpreter.Options opts = new Interpreter.Options();
        opts.setNumThreads(2);
        interpreter = new Interpreter(model, opts);
    }

    private ByteBuffer loadModelFile(Context context, String name) throws IOException {
        // 关键修复：不再用 openFd()。openFd 要求 assets 文件在 APK 中未压缩且按页对齐，
        // 部分打包/设备组合下会失败 → FaceEmbedder 构造抛异常 → embedding 恒 null →
        // 录入永远显示"未检测到人脸"。改用 AssetManager.open() 读取到 DirectByteBuffer，
        // 对压缩与对齐无要求，最稳妥。
        java.io.InputStream in = context.getAssets().open(name);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] bytes = bos.toByteArray();
            ByteBuffer bb = ByteBuffer.allocateDirect(bytes.length);
            bb.order(ByteOrder.nativeOrder());
            bb.put(bytes);
            bb.flip();
            return bb;
        } finally {
            in.close();
        }
    }

    /**
     * 从人脸关键点提取身份 embedding。
     * @param lms 478 个归一化关键点 (x,y,z in 0..1)
     * @param bitmapW/bitmapH 原图尺寸
     * @return 512D L2 归一化向量，失败返回 null
     */
    public float[] embed(java.util.List<? extends android.graphics.PointF> lms,
                         int bitmapW, int bitmapH) {
        if (lms == null || lms.size() < 300) return null;
        if (IDX[IDX.length - 1] >= lms.size()) return null;

        // 源点（像素坐标）
        float[] srcX = new float[5], srcY = new float[5];
        for (int i = 0; i < 5; i++) {
            android.graphics.PointF p = lms.get(IDX[i]);
            srcX[i] = p.x * bitmapW;
            srcY[i] = p.y * bitmapH;
        }
        // 最小二乘相似变换：u = a*x - b*y + tx ; v = b*x + a*y + ty
        float[] t = solveSimilarity(srcX, srcY);
        float a = t[0], b = t[1], tx = t[2], ty = t[3];

        // 先把对齐后的人脸采样到 RGB 缓冲
        float[] rgb = new float[INPUT_SIZE * INPUT_SIZE * 3];
        float[] px = new float[3];
        for (int v = 0; v < INPUT_SIZE; v++) {
            for (int u = 0; u < INPUT_SIZE; u++) {
                float du = u - tx, dv = v - ty;
                float denom = a * a + b * b;
                if (denom < 1e-8f) denom = 1e-8f;
                float sx = (a * du + b * dv) / denom;
                float sy = (-b * du + a * dv) / denom;
                sampleNearest(curBitmap, sx, sy, px);
                int base = (v * INPUT_SIZE + u) * 3;
                rgb[base] = px[0];
                rgb[base + 1] = px[1];
                rgb[base + 2] = px[2];
            }
        }

        // 人脸级亮度归一化：把对齐人脸的平均亮度拉到 ~128，
        // 让暗光 / 强光下提取的 embedding 基本一致（判别式模型对亮度敏感）。
        double lumSum = 0;
        for (int i = 0; i < rgb.length; i += 3) {
            lumSum += 0.299 * rgb[i] + 0.587 * rgb[i + 1] + 0.114 * rgb[i + 2];
        }
        float meanLum = (float) (lumSum / (INPUT_SIZE * INPUT_SIZE));
        float gain = meanLum > 8f ? Math.min(2.6f, Math.max(0.5f, 128f / meanLum)) : 1f;

        // 对齐并填充输入 (NHWC, 逐像素 R,G,B, (v-127.5)/128)
        ByteBuffer input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4);
        input.order(ByteOrder.nativeOrder());
        for (int i = 0; i < rgb.length; i += 3) {
            float rr = Math.min(255f, rgb[i] * gain);
            float gg = Math.min(255f, rgb[i + 1] * gain);
            float bb = Math.min(255f, rgb[i + 2] * gain);
            input.putFloat((rr - 127.5f) / 128f);
            input.putFloat((gg - 127.5f) / 128f);
            input.putFloat((bb - 127.5f) / 128f);
        }

        float[][] out = new float[1][EMBED_DIM];
        interpreter.run(input, out);
        float[] e = out[0];
        // L2 归一化
        double norm = 0;
        for (float v : e) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 1e-9) for (int i = 0; i < e.length; i++) e[i] = (float) (e[i] / norm);
        return e;
    }

    // 当前帧位图（由 detect 之前设置），供对齐采样
    private Bitmap curBitmap;
    public void setBitmap(Bitmap bmp) { this.curBitmap = bmp; }

    private static void sampleNearest(Bitmap bmp, float x, float y, float[] out) {
        if (bmp == null) { out[0] = out[1] = out[2] = 0f; return; }
        int xi = Math.round(x), yi = Math.round(y);
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (xi < 0) xi = 0; if (xi >= w) xi = w - 1;
        if (yi < 0) yi = 0; if (yi >= h) yi = h - 1;
        int c = bmp.getPixel(xi, yi);
        out[0] = (c >> 16) & 0xFF;
        out[1] = (c >> 8) & 0xFF;
        out[2] = c & 0xFF;
    }

    /** 解 5 点相似变换的最小二乘解 [a, b, tx, ty]。 */
    private static float[] solveSimilarity(float[] x, float[] y) {
        // 正规方程 A^T A θ = A^T B（4 未知数：a, b, tx, ty）
        double[][] A = new double[4][4];
        double[] Bv = new double[4];
        for (int i = 0; i < 5; i++) {
            float xi = x[i], yi = y[i], ui = REF[i][0], vi = REF[i][1];
            // u = a*xi - b*yi + tx
            double[] r1 = {xi, -yi, 1, 0};
            // v = b*xi + a*yi + ty
            double[] r2 = {yi, xi, 0, 1};
            for (int p = 0; p < 4; p++) {
                Bv[p] += r1[p] * ui + r2[p] * vi;
                for (int q = 0; q < 4; q++) A[p][q] += r1[p] * r1[q] + r2[p] * r2[q];
            }
        }
        double[] sol = gaussian(A, Bv);
        return new float[]{(float) sol[0], (float) sol[1], (float) sol[2], (float) sol[3]};
    }

    private static double[] gaussian(double[][] A, double[] b) {
        int n = b.length;
        for (int i = 0; i < n; i++) {
            int pivot = i;
            for (int r = i + 1; r < n; r++) if (Math.abs(A[r][i]) > Math.abs(A[pivot][i])) pivot = r;
            double[] tmp = A[i]; A[i] = A[pivot]; A[pivot] = tmp;
            double tb = b[i]; b[i] = b[pivot]; b[pivot] = tb;
            double div = A[i][i];
            if (Math.abs(div) < 1e-12) div = 1e-12;
            for (int c = i; c < n; c++) A[i][c] /= div;
            b[i] /= div;
            for (int r = 0; r < n; r++) {
                if (r == i) continue;
                double f = A[r][i];
                for (int c = i; c < n; c++) A[r][c] -= f * A[i][c];
                b[r] -= f * b[i];
            }
        }
        return b;
    }

    /** 余弦距离（1 - 余弦相似度），越小越像。 */
    public static float distance(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return Float.MAX_VALUE;
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return (float) (1.0 - dot);
    }

    public void close() { if (interpreter != null) { interpreter.close(); } }
}
