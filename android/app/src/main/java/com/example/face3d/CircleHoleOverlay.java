package com.example.face3d;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 圆形相机取景遮罩 —— 1:1 照抄 jonnyoo/glance 录入界面的圆形 CameraPreviewView。
 *
 * glance 的圆形取景就是「原生相机预览层 clipShape(Circle())」——圆内是相机实时画面，
 * 朝向天然正确、GPU 渲染零卡顿。Android 对应实现：
 *   底层放一个全屏 CameraX PreviewView（原生渲染），本 View 全屏涂白并在正中挖一个透明圆洞，
 *   洞里透出的就是原生相机画面。圆圈外为白色背景（配合白黑主题）。
 *   绝不再用 Bitmap 自绘（那正是倒过来 / 卡顿的根因）。
 *
 * 用 even-odd 填充规则挖洞：矩形 + 中心圆 → 圆内不填充 = 透明洞。
 */
public class CircleHoleOverlay extends View {

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path maskPath = new Path();
    private final float baseRadiusPx;
    private float radiusPx;
    private float centerX = -1f, centerY = -1f;

    public CircleHoleOverlay(Context c) { this(c, null); }

    public CircleHoleOverlay(Context c, AttributeSet a) {
        super(c, a);
        maskPaint.setColor(Color.WHITE); // 圆圈外背景改白色（白黑主题）
        maskPaint.setStyle(Paint.Style.FILL);
        // 取景框放大：圆洞半径 112dp → 134dp，与放大后的 340dp 进度环内沿匹配
        // （环 tick 内端半径 = 340*0.4167 ≈ 141.7dp，圆洞略小于它，留一圈间隙）。
        baseRadiusPx = 134f * c.getResources().getDisplayMetrics().density;
        radiusPx = baseRadiusPx;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild(w, h);
    }

    private void rebuild(int w, int h) {
        maskPath.reset();
        maskPath.addRect(0, 0, w, h, Path.Direction.CW);
        float cx = centerX >= 0f ? centerX : w / 2f;
        float cy = centerY >= 0f ? centerY : h / 2f;
        maskPath.addCircle(cx, cy, radiusPx, Path.Direction.CW);
        maskPath.setFillType(Path.FillType.EVEN_ODD);
    }

    /** 把圆洞对齐到圆环中心的屏幕坐标（由 MainActivity 在布局完成后传入）。 */
    public void setHoleCenter(float cx, float cy) {
        this.centerX = cx;
        this.centerY = cy;
        rebuild(getWidth(), getHeight());
        invalidate();
    }

    /** 新一次录入：打开圆洞，露出原生相机画面。 */
    public void openHole() {
        radiusPx = baseRadiusPx;
        rebuild(getWidth(), getHeight());
        invalidate();
    }

    /** 采集完成：圆洞收缩为全黑（对应 glance 圆形画面淡出，只留完成对勾）。 */
    public void collapseHole() {
        ValueAnimator anim = ValueAnimator.ofFloat(radiusPx, 0f);
        anim.setDuration(420);
        anim.addUpdateListener(a -> {
            radiusPx = (float) a.getAnimatedValue();
            rebuild(getWidth(), getHeight());
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.drawPath(maskPath, maskPaint);
    }
}
