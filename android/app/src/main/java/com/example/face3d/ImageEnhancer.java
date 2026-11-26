package com.example.face3d;

import android.graphics.Bitmap;

/**
 * 暗光图像增强器。
 *
 * 暗光下相机帧亮度低、噪声大、对比度差，会导致 MediaPipe 关键点检测与 MobileFaceNet
 * 身份特征提取同时失败。本类在图像送入引擎前做轻量级增强：
 * 1) 采样估算画面平均亮度；
 * 2) 偏暗时用 Gamma 校正（暗部提亮幅度大于亮部，保住高光不溢出）；
 * 3) 轻度对比度拉伸，恢复被压扁的面部明暗层次；
 * 4) 暗图做可分离盒式平滑降噪——暗光噪声是检测失败的主因，必须先压噪再提亮。
 *
 * 全程用查表（LUT）+ getPixels/setPixels + 可分离卷积，单帧毫秒级，
 * 满足实时识别（约 11fps）的性能要求。亮度充足时直接返回原图，零开销。
 */
final class ImageEnhancer {

    private ImageEnhancer() {}

    private static int[] pixels;
    private static int[] tmp;
    private static final int[] LUT = new int[256];
    private static float lastGamma = -1f, lastContrast = -1f;

    /** 增强一帧。亮度充足时原样返回（同一对象）；偏暗时返回提亮降噪后的新 Bitmap。 */
    static Bitmap enhance(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return src;

        // 1. 稀疏采样估算平均亮度（每 16 像素取一个）
        long sum = 0;
        int count = 0;
        for (int y = 0; y < h; y += 16) {
            for (int x = 0; x < w; x += 16) {
                int c = src.getPixel(x, y);
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                sum += (r * 299 + g * 587 + b * 114) / 1000;
                count++;
            }
        }
        if (count == 0) return src;
        float avg = (float) sum / count; // 0..255

        // 亮度充足，无需增强
        if (avg >= 125f) return src;

        // 2. 根据平均亮度计算 Gamma 与对比度（越暗提亮越强）
        // avg=125 -> gamma≈1.0；avg=40 -> gamma≈0.45；avg=10 -> gamma≈0.26
        float t = Math.max(0f, Math.min(1f, avg / 125f));
        float gamma = 0.26f + 0.74f * t;     // 0.26..1.0（比旧版下限更低，暗光提亮更强）
        float contrast = 1.22f - 0.22f * t;  // 1.22..1.0（暗图对比度拉伸更明显）
        buildLut(gamma, contrast);

        // 3. 应用 LUT
        int need = w * h;
        if (pixels == null || pixels.length < need) { pixels = new int[need]; tmp = new int[need]; }
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < need; i++) {
            int c = pixels[i];
            int a = (c >>> 24);
            int r = LUT[(c >> 16) & 0xFF];
            int g = LUT[(c >> 8) & 0xFF];
            int b = LUT[c & 0xFF];
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        // 4. 暗光降噪：越暗噪声越强，平滑混合比例越高（0.15~0.5）。
        //    可分离盒式模糊（横向→纵向），O(w*h)，然后与原图按 blend 混合，
        //    既压掉孤立噪点，又保留五官边缘（blend 上限 0.5 不会糊掉关键点）。
        float blend = 0.15f + 0.35f * (1f - t);
        boxBlurThenMix(pixels, tmp, w, h, blend);

        Bitmap out = Bitmap.createBitmap(w, h, src.getConfig() != null ? src.getConfig() : Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    /** 可分离 3x3 盒式模糊，结果与 src 按 blend 混合后写回 src。 */
    private static void boxBlurThenMix(int[] src, int[] tmp, int w, int h, float blend) {
        float keep = 1f - blend;
        // 横向：tmp[y*w+x] = 相邻三像素均值
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int x0 = x > 0 ? x - 1 : 0;
                int x2 = x < w - 1 ? x + 1 : w - 1;
                int c0 = src[row + x0], c1 = src[row + x], c2 = src[row + x2];
                int a = ((c0 >>> 24) + (c1 >>> 24) + (c2 >>> 24)) / 3;
                int r = (((c0 >> 16) & 255) + ((c1 >> 16) & 255) + ((c2 >> 16) & 255)) / 3;
                int g = (((c0 >> 8) & 255) + ((c1 >> 8) & 255) + ((c2 >> 8) & 255)) / 3;
                int b = ((c0 & 255) + (c1 & 255) + (c2 & 255)) / 3;
                tmp[row + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        // 纵向 + 混合
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int y0 = y > 0 ? (y - 1) * w : row;
            int y2 = y < h - 1 ? (y + 1) * w : row;
            for (int x = 0; x < w; x++) {
                int c0 = tmp[y0 + x], c1 = tmp[row + x], c2 = tmp[y2 + x];
                int a = ((c0 >>> 24) + (c1 >>> 24) + (c2 >>> 24)) / 3;
                int r = (((c0 >> 16) & 255) + ((c1 >> 16) & 255) + ((c2 >> 16) & 255)) / 3;
                int g = (((c0 >> 8) & 255) + ((c1 >> 8) & 255) + ((c2 >> 8) & 255)) / 3;
                int b = ((c0 & 255) + (c1 & 255) + (c2 & 255)) / 3;
                int orig = src[row + x];
                int oa = (orig >>> 24), or = (orig >> 16) & 255, og = (orig >> 8) & 255, ob = orig & 255;
                src[row + x] = (oa << 24)
                        | ((int)(or * keep + r * blend) << 16)
                        | ((int)(og * keep + g * blend) << 8)
                        | (int)(ob * keep + b * blend);
            }
        }
    }

    /** 构造 Gamma + 对比度查表，参数变化时重建。 */
    private static void buildLut(float gamma, float contrast) {
        if (Math.abs(gamma - lastGamma) < 0.01f && Math.abs(contrast - lastContrast) < 0.01f) return;
        lastGamma = gamma;
        lastContrast = contrast;
        float inv = 1f / gamma;
        for (int i = 0; i < 256; i++) {
            float v = (float) Math.pow(i / 255f, inv);       // gamma 提亮
            v = (v - 0.5f) * contrast + 0.5f;               // 对比度拉伸
            LUT[i] = Math.max(0, Math.min(255, Math.round(v * 255f)));
        }
    }
}
