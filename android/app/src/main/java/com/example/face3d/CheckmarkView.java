package com.example.face3d;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * 描边对勾动画 —— 1:1 移植自 jonnyoo/glance（MIT）的 AnimatedCheckmark.swift。
 *
 * glance 原始：CheckmarkShape 三点 (0.16w,0.52h)->(0.40w,0.78h)->(0.86w,0.22h)，
 * trim(0->progress) 描边，timingCurve(0.65,0,0.35,1)，duration 0.28s，
 * lineWidth=8，录入完成时使用 accent 色 #3499FF。
 */
public class CheckmarkView extends View {

    private static final int ACCENT = Color.parseColor("#3499FF"); // 录入完成对勾：黑底取景上用 glance 亮蓝
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final Path drawn = new Path();
    private float progress = 0f;
    private ValueAnimator anim;

    public CheckmarkView(Context c) { this(c, null); }
    public CheckmarkView(Context c, AttributeSet a) {
        super(c, a);
        paint.setColor(ACCENT);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(10f);
    }

    /** glance: 录入完成对勾用 accent 蓝，Complete 步骤对勾用白色。 */
    public void setColor(int color) { paint.setColor(color); invalidate(); }

    /** 从头播放描边对勾（glance: 0.28s, timingCurve 0.65,0,0.35,1）。 */
    public void play() {
        if (anim != null) anim.cancel();
        progress = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(280);
        anim.setInterpolator(new PathInterpolator(0.65f, 0f, 0.35f, 1f));
        anim.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); invalidate(); });
        setVisibility(VISIBLE);
        anim.start();
    }

    public void hide() {
        if (anim != null) anim.cancel();
        progress = 0f;
        setVisibility(GONE);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        path.reset();
        path.moveTo(w * 0.16f, h * 0.52f);
        path.lineTo(w * 0.40f, h * 0.78f);
        path.lineTo(w * 0.86f, h * 0.22f);
        measure.setPath(path, false);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (progress <= 0f) return;
        drawn.reset();
        measure.getSegment(0f, measure.getLength() * progress, drawn, true);
        c.drawPath(drawn, paint);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }
}
