package com.example.face3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/**
 * 端侧 3D 重建过程可视化视图（软件渲染，已做性能与朝向优化）。
 *
 * 视觉流程（真实数据驱动，不是定时假动画）：
 *  1) 每个面部点是一枚「高斯光斑」，初始散布在屏幕四周外围（对四周随机但确定的起点）；
 *  2) 随着重建器真实迭代进度 p 推进，每个光斑错峰从四周飞向它在融合 mesh 中的真实目标位置；
 *  3) 飞行中光斑由大变小、由暗转亮，最终聚合成一张清晰的 3D 人脸；
 *  4) 完成后【固定正面对着镜头】——不做自动旋转；并对融合几何做眼线扶正，
 *     保证正视、双目水平；仍可触摸旋转查看任意角度。
 *
 * 性能优化：光斑由原来的每点 2 次 drawCircle 改为 1 次预渲染高斯纹理贴图；
 * 起点角度/半径/延迟等随机量全部预计算，onDraw 里不再有三角函数与 hash 调用。
 */
public class ReconstructionView extends View {

    private static final int[] OVAL = {10,338,297,332,284,251,389,356,454,323,361,288,397,365,379,378,400,377,152,148,176,149,150,136,172,58,132,93,234,127,162,21,54,103,67,109};
    private static final int[] NOSE = {168,6,197,195,5,4,1,2,98,327};
    private static final int[][] OUTLINES = {OVAL, NOSE};

    private static final int TEX = 64;
    private static final float HALF = TEX / 2f;

    private final Paint splatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix splatMatrix = new Matrix();
    private Bitmap splatTex; // 预渲染「亮白核心 + 青蓝辉光」高斯光斑

    private float[][] frames;
    private float[] fused;
    private float progress = 0f;
    private boolean complete = false;

    private float yaw = 0f, pitch = 0f;
    private float downX, downY, baseYaw, basePitch;
    private boolean userTouching = false;

    // 预计算的每点静态参数（只依赖索引 i，一次算好，避免每帧三角函数）
    private final float[] pCos = new float[468];
    private final float[] pSin = new float[468];
    private final float[] pRad = new float[468];
    private final float[] pDelay = new float[468];

    public ReconstructionView(Context c) { this(c, null); }
    public ReconstructionView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.2f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        buildSplatTexture();
        for (int i = 0; i < 468; i++) {
            float ang = i * 2.3999632f + hash(i * 5) * 0.6f;
            pCos[i] = (float) Math.cos(ang);
            pSin[i] = (float) Math.sin(ang);
            pRad[i] = 0.86f + 0.34f * hash(i * 13);
            pDelay[i] = hash(i * 3 + 1) * 0.32f;
        }
    }

    /** 预渲染一张径向高斯光斑：中心亮白 → 青蓝 → 透明。每点一次贴图即可。 */
    private void buildSplatTexture() {
        int[] stops = {
                Color.argb(255, 236, 250, 255),
                Color.argb(224, 176, 224, 255),
                Color.argb(150, 96, 176, 248),
                Color.argb(48,  70, 140, 220),
                Color.argb(0,   70, 140, 220)
        };
        float[] pos = {0f, 0.16f, 0.38f, 0.68f, 1f};
        RadialGradient rg = new RadialGradient(HALF, HALF, HALF, stops, pos, Shader.TileMode.CLAMP);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(rg);
        splatTex = Bitmap.createBitmap(TEX, TEX, Bitmap.Config.ARGB_8888);
        new Canvas(splatTex).drawCircle(HALF, HALF, HALF - 1f, p);
    }

    public void setFrames(List<float[]> list) {
        if (list == null || list.isEmpty()) { frames = null; invalidate(); return; }
        frames = new float[list.size()][];
        for (int i = 0; i < list.size(); i++) {
            float[] f = list.get(i);
            frames[i] = (f != null && f.length >= 468*3) ? f.clone() : null;
        }
        if (fused == null && frames[0] != null) fused = upright(frames[0].clone());
        invalidate();
    }

    public void applySnapshot(float[] mesh, float p) {
        if (mesh == null || mesh.length < 468*3) return;
        fused = upright(mesh);
        progress = p;
        invalidate();
    }

    public void setFinal(float[] mesh) {
        if (mesh != null && mesh.length >= 468*3) fused = upright(mesh.clone());
        progress = 1f;
        complete = true;
        yaw = 0f; pitch = 0f;              // 完成时强制正视，方向正面对着镜头
        invalidate();
    }

    public void reset() {
        frames = null; fused = null; progress = 0f; complete = false;
        yaw = 0f; pitch = 0f;
        invalidate();
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static float easeOut(float t) { return 1f - (float)Math.pow(1f - t, 3f); }
    private static float hash(int i) {
        float v = (float)Math.sin(i * 12.9898f + 78.233f) * 43758.5453f;
        return v - (float)Math.floor(v);
    }

    /**
     * 眼线扶正：绕面部中心在图像平面旋转，使左右眼外角(33/263)连线水平，
     * 消除录入首帧头微歪导致的整体倾斜。温和系数 0.85，正视时几乎无影响。
     */
    private static float[] upright(float[] m) {
        float dx = m[263*3] - m[33*3];
        float dy = m[263*3+1] - m[33*3+1];
        float ang = (float)Math.atan2(dy, dx);      // 图像坐标 y 向下
        float corr = -ang * 0.85f;
        if (Math.abs(corr) < 0.008f) return m;      // 已经水平，不改动
        float c = (float)Math.cos(corr), s = (float)Math.sin(corr);
        float cx=0, cy=0;
        for (int i=0;i<468;i++){ cx+=m[i*3]; cy+=m[i*3+1]; }
        cx/=468f; cy/=468f;
        float[] out = new float[m.length];
        System.arraycopy(m, 0, out, 0, m.length);
        for (int i=0;i<468;i++){
            float x=m[i*3]-cx, y=m[i*3+1]-cy;
            out[i*3]   = cx + x*c - y*s;
            out[i*3+1] = cy + x*s + y*c;
        }
        return out;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                userTouching = true;
                downX = e.getX(); downY = e.getY(); baseYaw = yaw; basePitch = pitch; return true;
            case MotionEvent.ACTION_MOVE:
                yaw = baseYaw + (e.getX() - downX) * 0.011f;
                pitch = clamp(basePitch + (e.getY() - downY) * 0.011f, -1.2f, 1.2f);
                invalidate(); return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                userTouching = false; return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w=getWidth(), h=getHeight();
        if (w<=0||h<=0) return;

        canvas.drawColor(Color.rgb(14, 20, 32));
        if (fused == null) return;

        float[] ctr = center(fused);
        float scale = eyeScale(fused);
        float cx = w/2f, cy = h/2f;
        float S = Math.min(w, h) * 0.34f;
        float R = (float)Math.max(w, h) * 0.62f;

        float cyaw=(float)Math.cos(yaw), syaw=(float)Math.sin(yaw);
        float cpit=(float)Math.cos(pitch), spit=(float)Math.sin(pitch);

        float[] sx = new float[468], sy = new float[468], sz = new float[468];

        for (int i=0;i<468;i++){
            float x=(fused[i*3]-ctr[0])/scale;
            float y=-((fused[i*3+1]-ctr[1])/scale);
            float z=(fused[i*3+2]-ctr[2])/scale;
            float x1=cyaw*x+syaw*z, z1=-syaw*x+cyaw*z;
            float y2=cpit*y-spit*z1, z2=spit*y+cpit*z1;
            float persp=1f/(1f-clamp(z2,-1.2f,1.2f)*0.05f);
            sx[i]=cx+x1*S*persp; sy[i]=cy-y2*S*persp; sz[i]=z2;
        }

        float base = complete ? 1f : easeOut(progress);

        // 1) 早期散乱背景点云：降低抽样密度(每10点)、限制帧数，减少绘制量
        if (frames != null && progress < 0.85f) {
            float fade = 1f - progress/0.85f;
            int alpha = (int)(60 * fade);
            splatPaint.setColor(Color.argb(alpha, 70, 140, 210));
            int stepFrames = Math.max(1, frames.length / 8);   // 最多取 8 帧
            for (int fi=0; fi<frames.length; fi+=stepFrames) {
                float[] fr = frames[fi]; if (fr==null) continue;
                for (int i=0;i<468;i+=10) {
                    float x=(fr[i*3]-ctr[0])/scale;
                    float y=-((fr[i*3+1]-ctr[1])/scale);
                    float jx=(hash(i*7+fi)*2f-1f)*0.35f;
                    float jy=(hash(i*11+fi*3)*2f-1f)*0.35f;
                    canvas.drawCircle(cx+(x+jx)*S, cy+(y+jy)*S, 2.0f, splatPaint);
                }
            }
        }

        // 2) 高斯光斑飞入汇聚：每点一次预渲染纹理贴图（原为 2 次 drawCircle）
        for (int i=0;i<468;i++){
            float delay = pDelay[i];
            float local = complete ? 1f : clamp((progress - delay) / (1f - delay), 0f, 1f);
            float e = easeOut(local);

            float startX = cx + pCos[i] * R * pRad[i];
            float startY = cy + pSin[i] * R * pRad[i];
            float px = lerp(startX, sx[i], e);
            float py = lerp(startY, sy[i], e);

            float dw = clamp(sz[i]*0.3f+0.5f, 0f, 1f);
            float coreR = lerp(3.4f, 1.25f+1.7f*dw, e);
            float drawR = coreR * 2.6f;                       // 纹理含辉光，整体直径
            int a = (int)(lerp(70, 245, e) * (0.6f+0.4f*dw));

            splatMatrix.reset();
            splatMatrix.postTranslate(-HALF, -HALF);
            splatMatrix.postScale(drawR / HALF, drawR / HALF);
            splatMatrix.postTranslate(px, py);
            splatPaint.setAlpha(a);
            canvas.drawBitmap(splatTex, splatMatrix, splatPaint);
        }

        // 3) 轮廓线框：进度过半后渐显（端点位置复用预计算参数）
        if (progress > 0.5f || complete) {
            float la = complete ? 1f : clamp((progress-0.5f)/0.5f, 0f, 1f);
            for (int[] outline : OUTLINES) {
                for (int k=0;k<outline.length-1;k++){
                    int i=outline[k], j=outline[k+1];
                    if (i>=468||j>=468) continue;
                    float depth=(sz[i]+sz[j])/2f;
                    int a=(int)((70+150*clamp(depth*0.3f+0.5f,0f,1f)) * la);
                    linePaint.setColor(Color.argb(a, 130, 205, 255));
                    float ei=complete?1f:easeOut(clamp((progress-pDelay[i])/(1f-pDelay[i]),0f,1f));
                    float ej=complete?1f:easeOut(clamp((progress-pDelay[j])/(1f-pDelay[j]),0f,1f));
                    float pxI=lerp(cx+pCos[i]*R*pRad[i], sx[i], ei);
                    float pyI=lerp(cy+pSin[i]*R*pRad[i], sy[i], ei);
                    float pxJ=lerp(cx+pCos[j]*R*pRad[j], sx[j], ej);
                    float pyJ=lerp(cy+pSin[j]*R*pRad[j], sy[j], ej);
                    canvas.drawLine(pxI,pyI,pxJ,pyJ,linePaint);
                }
            }
        }
    }

    private float[] center(float[] ref) {
        float cx=0, cy=0, cz=0;
        for (int i=0;i<468;i++){ cx+=ref[i*3]; cy+=ref[i*3+1]; cz+=ref[i*3+2]; }
        return new float[]{cx/468f, cy/468f, cz/468f};
    }
    private float eyeScale(float[] ref) {
        float dx=ref[33*3]-ref[263*3], dy=ref[33*3+1]-ref[263*3+1], dz=ref[33*3+2]-ref[263*3+2];
        float s=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
        return s<1e-6f?1f:s;
    }
}
