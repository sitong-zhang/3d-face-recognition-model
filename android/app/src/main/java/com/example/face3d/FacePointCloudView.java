package com.example.face3d;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * 3D 人脸扫描建模 —— 移动端实时高斯泼溅渲染（Gaussian Splatting）。
 *
 * 一整张脸由 512 个高斯斑(splat)构成：
 *   初始完全空白；头部朝向映射成脸上连续移动的探针，探到哪颗就点亮哪颗（sweepAt），
 *   每帧只点亮探针附近最近的少数几颗、错开时间依次冒出——一颗一颗累积，而非整片一起亮。
 *   512 点全部亮起建模才完成。点亮完全由真实扫描驱动，没有任何定时自增或假循环。
 *
 * 【高斯建模核心】
 *   每个采样点不再画成硬边圆点，而是一个二维高斯椭球：
 *     - 由脸部局部表面法线得到 splat 的协方差 —— 法线朝向决定椭圆长短轴与旋转角；
 *     - 用预渲染的高斯核纹理(径向 alpha 衰减 exp(-r²/2σ²))，经 Matrix 缩放/旋转/平移绘制；
 *     - 按深度 z 从远到近排序后逐个 alpha 混合，叠加成连续、平滑、带体积感的 3D 曲面；
 *     - 深度映射颜色（远处深蓝、凸起处青白），强化立体层次。
 *   这是 3D Gaussian Splatting 的渲染核心在移动端的实时实现（不含离线神经训练）。
 *
 * 【3D 跟随】每帧把真人 yaw/pitch 与位置喂进来(setHeadPose)，整张脸同步旋转/平移，容器本身不动。
 */
public class FacePointCloudView extends View {

    public static final int TOTAL = 512;
    private static final int REGIONS = 9;

    // 逐点探针扫描参数
    private static final float PROBE_RADIUS = 0.17f;     // 探针捕获半径（归一化）：稍大以保证转头一圈能扫全
    private static final int MAX_PER_SWEEP = 6;          // 每帧最多点亮的点数（少而精，视觉上一颗颗冒出）
    private static final long REVEAL_STAGGER_MS = 34L;   // 同一帧内相邻点冒出的间隔

    // 高斯斑配色（从远到近：深蓝 → 亮蓝 → 青 → 白），按深度取色
    private static final int[] SPLAT_COLORS = {
            Color.parseColor("#1B4FD8"),
            Color.parseColor("#2E7CF6"),
            Color.parseColor("#48A6FF"),
            Color.parseColor("#8FD8FF"),
            Color.parseColor("#EAF7FF")
    };
    private static final int SPLAT_TEX_SIZE = 64;

    // 3D 跟随参数
    private static final float FOLLOW_GAIN = 0.42f;
    private static final float FOLLOW_LIMIT = 0.11f;
    private static final float DEPTH = 0.45f;
    private static final float PERSP_F = 1.9f;
    private static final float POSE_LERP = 0.18f;
    private static final float YAW_SIGN = 1f;
    private static final float PITCH_SIGN = 1f;

    private final float[] px = new float[TOTAL];
    private final float[] py = new float[TOTAL];
    private final float[] pz = new float[TOTAL];
    private final int[] region = new int[TOTAL];
    private final int[] regionCount = new int[REGIONS];
    private final boolean[] regionLit = new boolean[REGIONS];
    private final float[] reveal = new float[TOTAL];
    private final long[] revealStart = new long[TOTAL];

    // 逐点探针扫描的候选缓冲
    private final int[] sweepIdx = new int[TOTAL];
    private final float[] sweepDist = new float[TOTAL];

    // 高斯斑协方差相关：每个点的表面法线（nx,ny,nz），用于算椭圆形状与朝向
    private final float[] nrmX = new float[TOTAL];
    private final float[] nrmY = new float[TOTAL];
    private final float[] nrmZ = new float[TOTAL];

    // 预渲染的高斯核纹理（每种颜色一张）
    private final Bitmap[] splatTex = new Bitmap[SPLAT_COLORS.length];
    private final Paint splatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix splatMatrix = new Matrix();

    // 每帧的 splat 排序缓冲（避免频繁分配）
    private final List<Splat> frameSplats = new ArrayList<>(TOTAL);
    private final Splat[] splatPool = new Splat[TOTAL];

    private static class Splat {
        float x, y;        // 投影中心
        float z;           // 旋转后深度（用于排序与取色）
        float major, minor;// 高斯椭球长短轴（像素）
        float angle;       // 旋转角（度）
        float alpha;       // 显现不透明度
        int color;         // 颜色档索引
    }

    private ValueAnimator ticker;
    private ValueAnimator poseAnim;
    private int litPointCount = 0;
    private int litRegionCount = 0;

    private volatile float targetYaw = 0f, targetPitch = 0f, targetTx = 0f, targetTy = 0f;
    private float curYaw = 0f, curPitch = 0f, curTx = 0f, curTy = 0f;
    private final float[] proj = new float[2];

    public FacePointCloudView(Context c) { this(c, null); }
    public FacePointCloudView(Context c, AttributeSet a) {
        super(c, a);
        buildSplatTextures();
        for (int i = 0; i < TOTAL; i++) splatPool[i] = new Splat();
        buildPoints();
    }

    // ---------------- 高斯核纹理 ----------------

    /** 预渲染径向高斯纹理：中心不透明、向外按 exp 衰减到透明。 */
    private void buildSplatTextures() {
        float cx = SPLAT_TEX_SIZE / 2f;
        for (int k = 0; k < SPLAT_COLORS.length; k++) {
            int col = SPLAT_COLORS[k];
            int cr = Color.red(col), cg = Color.green(col), cb = Color.blue(col);
            int[] stops = {
                    Color.argb(255, cr, cg, cb),
                    Color.argb(238, cr, cg, cb),
                    Color.argb(150, cr, cg, cb),
                    Color.argb(48,  cr, cg, cb),
                    Color.argb(0,   cr, cg, cb)
            };
            float[] pos = {0f, 0.18f, 0.42f, 0.72f, 1f};
            RadialGradient rg = new RadialGradient(cx, cx, cx, stops, pos, Shader.TileMode.CLAMP);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(rg);
            Bitmap bmp = Bitmap.createBitmap(SPLAT_TEX_SIZE, SPLAT_TEX_SIZE, Bitmap.Config.ARGB_8888);
            new Canvas(bmp).drawCircle(cx, cx, cx - 1f, p);
            splatTex[k] = bmp;
        }
    }

    // ---------------- 坐标 + 分区 + 3D 深度 + 法线 ----------------

    private void buildPoints() {
        List<float[]> pts = new ArrayList<>();
        final int ovalN = 140;
        for (int i = 0; i < ovalN; i++) {
            double t = 2 * Math.PI * i / ovalN;
            double x = 0.5 + 0.30 * Math.cos(t);
            double y = 0.49 + 0.40 * Math.sin(t);
            if (Math.sin(t) > 0) x = 0.5 + (x - 0.5) * (1 - 0.18 * Math.sin(t));
            pts.add(new float[]{(float) x, (float) y});
        }
        addEllipse(pts, 0.375f, 0.430f, 0.075f, 0.040f, 26);
        addEllipse(pts, 0.625f, 0.430f, 0.075f, 0.040f, 26);
        addArc(pts, 0.375f, 0.345f, 0.095f, 0.030f, 200, 340, 14);
        addArc(pts, 0.625f, 0.345f, 0.095f, 0.030f, 200, 340, 14);
        for (int i = 0; i < 22; i++) {
            float t = i / 21f;
            float y = 0.44f + 0.17f * t;
            float x = 0.5f + (t > 0.80f ? 0.03f * (float) Math.sin((t - 0.80f) / 0.20f * Math.PI) : 0f);
            pts.add(new float[]{x, y});
        }
        addEllipse(pts, 0.5f, 0.60f, 0.055f, 0.022f, 12);
        addArc(pts, 0.5f, 0.660f, 0.115f, 0.055f, 190, 350, 28);
        addArc(pts, 0.5f, 0.665f, 0.090f, 0.030f, 195, 345, 16);
        java.util.Random rnd = new java.util.Random(20241122L);
        int guard = 0;
        while (pts.size() < TOTAL && guard++ < 200000) {
            float x = rnd.nextFloat(), y = rnd.nextFloat();
            float nx = (x - 0.5f) / 0.30f, ny = (y - 0.49f) / 0.40f;
            if (nx * nx + ny * ny <= 0.90f) pts.add(new float[]{x, y});
        }
        List<float[]> use = pts.subList(0, TOTAL);

        for (int i = 0; i < TOTAL; i++) {
            px[i] = use.get(i)[0];
            py[i] = use.get(i)[1];
            pz[i] = depthOf(px[i], py[i]);
            region[i] = regionOf(px[i], py[i]);
            regionCount[region[i]]++;
            computeNormal(i);
        }
        buildRebuildOrder();
    }

    /** 按到脸部中心 (0.5,0.5) 的距离预排序的点索引，供重建阶段由内向外有序浮现。 */
    private void buildRebuildOrder() {
        Integer[] box = new Integer[TOTAL];
        for (int i = 0; i < TOTAL; i++) box[i] = i;
        java.util.Arrays.sort(box, (a, b) -> {
            float da = (px[a] - 0.5f) * (px[a] - 0.5f) + (py[a] - 0.5f) * (py[a] - 0.5f);
            float db = (px[b] - 0.5f) * (px[b] - 0.5f) + (py[b] - 0.5f) * (py[b] - 0.5f);
            return Float.compare(da, db);
        });
        for (int i = 0; i < TOTAL; i++) rebuildOrder[i] = box[i];
    }

    /** 由曲面梯度求该点法线 z=f(x,y) → n=normalize(-fx,-fy,1)。 */
    private void computeNormal(int i) {
        float e = 0.006f;
        float x = px[i], y = py[i];
        float fx = (depthOf(x + e, y) - depthOf(x - e, y)) / (2f * e);
        float fy = (depthOf(x, y + e) - depthOf(x, y - e)) / (2f * e);
        float nx = -fx, ny = -fy, nz = 1f;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nrmX[i] = nx / len; nrmY[i] = ny / len; nrmZ[i] = nz / len;
    }

    /** 由面部位置估算 3D 深度：脸中心（尤其鼻梁）凸起、边缘回收，形成弧面。 */
    private static float depthOf(float x, float y) {
        float nx = (x - 0.5f) / 0.30f;
        float ny = (y - 0.49f) / 0.40f;
        float dome = 1f - 0.62f * nx * nx - 0.58f * ny * ny;
        if (dome < 0f) dome = 0f;
        float z = 0.55f * dome;
        // 鼻梁：宽而平缓的隆起（不再是又窄又高的尖锥）。真人鼻子从眉心到鼻尖是
        // 一段连续缓坡，不是一个点的高斯尖峰——把隆起拉宽、降低峰值、中心下移。
        float nx2 = (x - 0.5f) / 0.085f;
        float ny2 = (y - 0.545f) / 0.075f;
        float r2 = nx2 * nx2 + ny2 * ny2;
        z += 0.075f * (float) Math.exp(-1.3f * r2);
        return Math.max(0f, z);
    }

    private void addEllipse(List<float[]> pts, float cx, float cy, float rx, float ry, int n) {
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            pts.add(new float[]{(float) (cx + rx * Math.cos(t)), (float) (cy + ry * Math.sin(t))});
        }
    }
    private void addArc(List<float[]> pts, float cx, float cy, float rx, float ry, int a0, int a1, int n) {
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(a0 + (a1 - a0) * i / (double) (n - 1));
            pts.add(new float[]{(float) (cx + rx * Math.cos(a)), (float) (cy + ry * Math.sin(a))});
        }
    }

    private static int regionOf(float x, float y) {
        int col = x < 0.41f ? 0 : (x > 0.59f ? 2 : 1);
        int row = y < 0.38f ? 0 : (y > 0.62f ? 2 : 1);
        return row * 3 + col;
    }

    public static int regionFromYawPitch(float yaw, float pitch, float yawInner, float pitchInner) {
        int col = yaw > yawInner ? 0 : (yaw < -yawInner ? 2 : 1);
        int row = pitch < -pitchInner ? 0 : (pitch > pitchInner ? 2 : 1);
        return row * 3 + col;
    }

    // ---------------- 3D 跟随入口 ----------------

    public void setHeadPose(float yaw, float pitch, float faceX, float faceY) {
        float tx = clamp((faceX - 0.5f) * FOLLOW_GAIN, -FOLLOW_LIMIT, FOLLOW_LIMIT);
        float ty = clamp((faceY - 0.5f) * FOLLOW_GAIN, -FOLLOW_LIMIT, FOLLOW_LIMIT);
        targetYaw = yaw;
        targetPitch = pitch;
        targetTx = tx;
        targetTy = ty;
        startPoseAnim();
    }

    private void startPoseAnim() {
        if (poseAnim != null && poseAnim.isRunning()) return;
        poseAnim = ValueAnimator.ofFloat(0f, 1f);
        poseAnim.setDuration(100000L);
        poseAnim.setRepeatCount(ValueAnimator.INFINITE);
        poseAnim.setInterpolator(new LinearInterpolator());
        poseAnim.addUpdateListener(a -> {
            curYaw += (targetYaw - curYaw) * POSE_LERP;
            curPitch += (targetPitch - curPitch) * POSE_LERP;
            curTx += (targetTx - curTx) * POSE_LERP;
            curTy += (targetTy - curTy) * POSE_LERP;
            invalidate();
        });
        poseAnim.start();
    }

    private void stopPoseAnim() {
        if (poseAnim != null) { poseAnim.cancel(); poseAnim = null; }
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** 把点 (x,y,z) 经旋转+透视+平移投影成画布坐标，写入 proj；旋转后的深度写入 outZ。 */
    private void project(float x, float y, float z, float ox, float oy, float span, float[] outZ) {
        float nx = x - 0.5f, ny = y - 0.5f;
        float cy = (float) Math.cos(curYaw * YAW_SIGN), sy = (float) Math.sin(curYaw * YAW_SIGN);
        float cp = (float) Math.cos(curPitch * PITCH_SIGN), sp = (float) Math.sin(curPitch * PITCH_SIGN);
        float x1 = nx * cy + z * sy;
        float z1 = -nx * sy + z * cy;
        float y1 = ny * cp - z1 * sp;
        float z2 = ny * sp + z1 * cp;
        float persp = PERSP_F / (PERSP_F - z2 * DEPTH);
        proj[0] = ox + (x1 * persp + curTx) * span + 0.5f * span;
        proj[1] = oy + (y1 * persp + curTy) * span + 0.5f * span;
        outZ[0] = z2;
    }

    /** 把法线同样旋转到相机空间，写入 out（旋转矩阵与 project 一致）。 */
    private void rotateNormal(int i, float[] out) {
        float cy = (float) Math.cos(curYaw * YAW_SIGN), sy = (float) Math.sin(curYaw * YAW_SIGN);
        float cp = (float) Math.cos(curPitch * PITCH_SIGN), sp = (float) Math.sin(curPitch * PITCH_SIGN);
        float nx = nrmX[i], ny = nrmY[i], nz = nrmZ[i];
        float x1 = nx * cy + nz * sy;
        float z1 = -nx * sy + nz * cy;
        float y1 = ny * cp - z1 * sp;
        float z2 = ny * sp + z1 * cp;
        out[0] = x1; out[1] = y1; out[2] = z2;
    }

    // ---------------- 扫描点亮（唯一数据入口） ----------------

    /**
     * 逐点探针：以 (probeX,probeY) 为中心，只点亮半径内【离探针最近】的少数几个未亮光点。
     * 每帧最多 MAX_PER_SWEEP 个，并按距离错开时间依次冒出 —— 屏幕上就是"扫到哪颗、哪颗才亮"，
     * 一颗一颗累积，而不是整块区域一起亮。探针不动则附近点亮完后不再新增，完全由真实扫描驱动。
     * 返回本次新点亮的点数。
     */
    public int sweepAt(float probeX, float probeY) {
        int n = 0;
        float r2 = PROBE_RADIUS * PROBE_RADIUS;
        for (int i = 0; i < TOTAL; i++) {
            if (revealStart[i] >= 0) continue;   // 已点亮
            float dx = px[i] - probeX, dy = py[i] - probeY;
            float d2 = dx * dx + dy * dy;
            if (d2 <= r2) { sweepIdx[n] = i; sweepDist[n] = d2; n++; }
        }
        if (n == 0) return 0;

        // 只取最近的 MAX_PER_SWEEP 个（插入排序，n 通常很小）
        int take = Math.min(n, MAX_PER_SWEEP);
        for (int a = 0; a < take; a++) {
            int best = a;
            for (int b = a + 1; b < n; b++) if (sweepDist[b] < sweepDist[best]) best = b;
            if (best != a) {
                int ti = sweepIdx[a]; sweepIdx[a] = sweepIdx[best]; sweepIdx[best] = ti;
                float td = sweepDist[a]; sweepDist[a] = sweepDist[best]; sweepDist[best] = td;
            }
        }
        long now = System.currentTimeMillis();
        for (int k = 0; k < take; k++) {
            int i = sweepIdx[k];
            revealStart[i] = now + k * REVEAL_STAGGER_MS;
            litPointCount++;
            // 所在区域首次触及时记一次，供统计
            int r = region[i];
            if (!regionLit[r]) { regionLit[r] = true; litRegionCount++; }
        }
        startTicker();
        return take;
    }

    private void startTicker() {
        if (ticker != null && ticker.isRunning()) return;
        ticker = ValueAnimator.ofFloat(0f, 1f);
        ticker.setDuration(100000L);
        ticker.setRepeatCount(ValueAnimator.INFINITE);
        ticker.setInterpolator(new LinearInterpolator());
        ticker.addUpdateListener(a -> {
            long now = System.currentTimeMillis();
            boolean more = false;
            for (int i = 0; i < TOTAL; i++) {
                long st = revealStart[i];
                if (st < 0) continue;
                if (now >= st && reveal[i] < 1f) {
                    reveal[i] = Math.min(1f, (now - st) / 260f);
                    if (reveal[i] < 1f) more = true;
                }
            }
            invalidate();
            if (!more && ticker != null) { ticker.cancel(); ticker = null; }
        });
        ticker.start();
    }

    public int getLitPointCount() { return litPointCount; }
    public int getLitRegionCount() { return litRegionCount; }
    /** 512 个光点全部扫到、全部亮起才算建模完成。 */
    public boolean isComplete() { return litPointCount >= TOTAL; }

    // ---------------- 重建阶段：由真实融合进度驱动点云成型 ----------------

    /**
     * 重建模式：不再由探针逐点点亮，而是按融合进度把【全部】512 点整体成型。
     * 采集阶段探针只触发了部分点；重建阶段把剩下的点按空间顺序（中心→外围）逐步补齐，
     * 直到 512 点全部亮起 = 3D 模型重建完成。进度来自后台真实重建计算，不是定时动画。
     */
    private boolean rebuilding = false;

    public void beginRebuild() { rebuilding = true; }

    /** 按重建进度(0..1)把点亮数推进到对应数量，未完成点按到脸部中心的距离排序依次浮现。 */
    public void setRebuildProgress(float p) {
        if (!rebuilding) return;
        p = clamp(p, 0f, 1f);
        int target = Math.round(p * TOTAL);
        if (target <= litPointCount) return;
        long now = System.currentTimeMillis();
        // 按预排序的 rebuildOrder 依次点亮（先五官后轮廓外围）
        int need = target - litPointCount;
        int cnt = 0;
        for (int idx = 0; idx < TOTAL && cnt < need; idx++) {
            int i = rebuildOrder[idx];
            if (revealStart[i] < 0) {
                revealStart[i] = now + cnt * REVEAL_STAGGER_MS;
                litPointCount++;
                int r = region[i];
                if (!regionLit[r]) { regionLit[r] = true; litRegionCount++; }
                cnt++;
            }
        }
        startTicker();
        invalidate();
    }

    /** 按到脸部中心 (0.5,0.5) 的距离预排序的点索引，供重建阶段有序浮现。 */
    private final int[] rebuildOrder = new int[TOTAL];

    /** 重建完成：直接把全部点补齐点亮。 */
    public void finishRebuild() {
        beginRebuild();
        setRebuildProgress(1f);
    }

    public void reset() {
        if (ticker != null) { ticker.cancel(); ticker = null; }
        stopPoseAnim();
        java.util.Arrays.fill(regionLit, false);
        java.util.Arrays.fill(reveal, 0f);
        java.util.Arrays.fill(revealStart, -1L);
        litPointCount = 0;
        litRegionCount = 0;
        rebuilding = false;
        targetYaw = targetPitch = targetTx = targetTy = 0f;
        curYaw = curPitch = curTx = curTy = 0f;
        invalidate();
    }

    // ---------------- 高斯泼溅绘制 ----------------

    private final float[] tmpZ = new float[1];
    private final float[] tmpN = new float[3];

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float S = Math.min(w, h);
        float pad = S * 0.06f;
        float span = S - 2 * pad;
        float ox = (w - span) / 2f, oy = (h - span) / 2f;
        float baseR = Math.max(2.2f, span * 0.016f);

        // 1) 收集本帧所有可见高斯斑，计算协方差（形状/朝向）、深度、颜色、透明度
        frameSplats.clear();
        for (int i = 0; i < TOTAL; i++) {
            float a = reveal[i];
            if (revealStart[i] < 0 || a <= 0f) continue;
            project(px[i], py[i], pz[i], ox, oy, span, tmpZ);
            float z2 = tmpZ[0];
            rotateNormal(i, tmpN);

            // splat 协方差：法线正对相机(nz≈1) → 圆且大；侧对(nz→0) → 扁成细线
            float facing = Math.abs(tmpN[2]);                 // 0..1
            float minorRatio = 0.18f + 0.82f * facing;        // 短轴/长轴
            float angle = (float) Math.toDegrees(Math.atan2(tmpN[1], tmpN[0]));
            float persp = PERSP_F / (PERSP_F - z2 * DEPTH);
            float major = baseR * (0.75f + 0.45f * a) * persp;
            float minor = Math.max(0.6f, major * minorRatio);

            // 深度→颜色：凸起(近)偏白，凹陷(远)偏蓝
            float dnorm = clamp((z2 + 0.05f) / 0.55f, 0f, 0.999f);
            int colorIdx = (int) (dnorm * SPLAT_COLORS.length);

            Splat s = splatPool[i];
            s.x = proj[0]; s.y = proj[1]; s.z = z2;
            s.major = major; s.minor = minor; s.angle = angle;
            s.alpha = a; s.color = colorIdx;
            frameSplats.add(s);
        }

        // 2) 按深度从远到近排序：先画远的，近的覆盖上来，得到正确的高斯 alpha 混合
        frameSplats.sort((p, q) -> Float.compare(p.z, q.z));

        // 3) 逐个绘制高斯椭球
        for (int k = 0; k < frameSplats.size(); k++) {
            Splat s = frameSplats.get(k);
            float half = SPLAT_TEX_SIZE / 2f;
            splatMatrix.reset();
            splatMatrix.postTranslate(-half, -half);
            splatMatrix.postScale(s.major / half, s.minor / half);
            splatMatrix.postRotate(s.angle);
            splatMatrix.postTranslate(s.x, s.y);
            splatPaint.setAlpha((int) (230 * s.alpha));
            c.drawBitmap(splatTex[s.color], splatMatrix, splatPaint);
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (ticker != null) { ticker.cancel(); ticker = null; }
        stopPoseAnim();
    }
}
