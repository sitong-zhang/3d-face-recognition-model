package com.example.face3d;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * 苹果 Face ID 风格的扫描动画视图。
 *
 * 状态：IDLE -> SCANNING -> SUCCESS / FAIL
 * SUCCESS 时绘制绿色圆环 + 对勾（PathMeasure 实现勾逐步画出），模仿 Face ID 成功效果。
 */
public class FaceIdAnimationView extends View {

    public enum State { IDLE, SCANNING, SUCCESS, FAIL }

    private State state = State.IDLE;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path checkPath = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();
    private final float[] pos = new float[2];
    private final RectF ringRect = new RectF();

    private int ringColor = Color.rgb(79, 141, 247);
    private float sweep = 0f;          // 扫描旋转角度
    private float checkProgress = 0f;  // 对勾绘制进度 0..1
    private float successScale = 1f;   // 成功弹跳缩放
    private float pulse = 0f;          // 呼吸透明度

    private ValueAnimator scanAnimator;
    private ValueAnimator successAnimator;

    public FaceIdAnimationView(Context c) { this(c, null); }
    public FaceIdAnimationView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        ringPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setState(State s) {
        this.state = s;
        clearAnimations();
        switch (s) {
            case SCANNING:
                ringColor = Color.rgb(79, 141, 247);
                checkProgress = 0f;
                successScale = 1f;
                startScanAnimation();
                break;
            case SUCCESS:
                ringColor = Color.rgb(50, 215, 75); // 苹果绿 #32D74B
                startSuccessAnimation();
                break;
            case FAIL:
                ringColor = Color.rgb(255, 69, 58);
                checkProgress = 0f;
                successScale = 1f;
                break;
            default: // IDLE
                ringColor = Color.rgb(79, 141, 247);
                checkProgress = 0f;
                successScale = 1f;
                sweep = 0f;
                break;
        }
        invalidate();
    }

    public State getState() { return state; }

    private void clearAnimations() {
        if (scanAnimator != null) scanAnimator.cancel();
        if (successAnimator != null) successAnimator.cancel();
        scanAnimator = null;
        successAnimator = null;
    }

    private void startScanAnimation() {
        scanAnimator = ValueAnimator.ofFloat(0f, 360f);
        scanAnimator.setDuration(1400);
        scanAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanAnimator.addUpdateListener(a -> {
            sweep = (float) a.getAnimatedValue();
            pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 300.0));
            invalidate();
        });
        scanAnimator.start();
    }

    /** 成功动画：圆环先弹一下，同时对勾逐步画出（模仿 Face ID）。 */
    private void startSuccessAnimation() {
        successAnimator = ValueAnimator.ofFloat(0f, 1f);
        successAnimator.setDuration(650);
        successAnimator.setInterpolator(new OvershootInterpolator(1.2f));
        successAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            checkProgress = t;
            successScale = t;
            invalidate();
        });
        successAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - 24f;
        ringRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        float stroke = 10f;
        ringPaint.setStrokeWidth(stroke);
        ringPaint.setColor(ringColor);
        ringPaint.setAlpha(state == State.SCANNING ? 255 : 255);

        // 外发光
        glowPaint.setStrokeWidth(stroke + 10);
        glowPaint.setColor(ringColor);
        glowPaint.setAlpha(state == State.SUCCESS ? 40 : 22);

        canvas.save();
        canvas.scale(successScale, successScale, cx, cy);

        if (state == State.SCANNING) {
            // 静态半透明底环
            ringPaint.setAlpha(60);
            canvas.drawArc(ringRect, 0, 360, false, ringPaint);
            // 旋转高亮弧
            ringPaint.setAlpha(255);
            canvas.drawArc(ringRect, sweep - 90, 90, false, ringPaint);
            canvas.drawArc(ringRect, sweep + 180 - 90, 60, false, ringPaint);
            // 呼吸外环
            glowPaint.setAlpha((int) (30 + 40 * pulse));
            canvas.drawArc(ringRect, 0, 360, false, glowPaint);
        } else {
            // 完整圆环
            glowPaint.setAlpha(state == State.SUCCESS ? 45 : 25);
            canvas.drawArc(ringRect, 0, 360, false, glowPaint);
            ringPaint.setAlpha(255);
            canvas.drawArc(ringRect, 0, 360, false, ringPaint);

            if (state == State.SUCCESS && checkProgress > 0f) {
                drawCheck(canvas, cx, cy, radius, checkProgress);
            } else if (state == State.FAIL) {
                drawCross(canvas, cx, cy, radius);
            } else if (state == State.IDLE) {
                ringPaint.setAlpha(90);
                float inner = radius - 26;
                canvas.drawCircle(cx, cy, inner, ringPaint);
            }
        }
        canvas.restore();
    }

    /** 画对勾：两个线段构成的勾，按进度逐步绘制。 */
    private void drawCheck(Canvas canvas, float cx, float cy, float radius, float progress) {
        float s = radius * 0.82f;
        checkPath.reset();
        checkPath.moveTo(cx - s * 0.5f, cy);
        checkPath.lineTo(cx - s * 0.12f, cy + s * 0.35f);
        checkPath.lineTo(cx + s * 0.55f, cy - s * 0.38f);

        checkPaint.setColor(ringColor);
        checkPaint.setStrokeWidth(14f);
        pathMeasure.setPath(checkPath, false);
        float len = pathMeasure.getLength();
        Path partial = new Path();
        float drawLen = len * Math.min(1f, progress);
        pathMeasure.getSegment(0, drawLen, partial, true);
        canvas.drawPath(partial, checkPaint);
    }

    private void drawCross(Canvas canvas, float cx, float cy, float radius) {
        float s = radius * 0.45f;
        checkPaint.setColor(ringColor);
        checkPaint.setStrokeWidth(12f);
        canvas.drawLine(cx - s, cy - s, cx + s, cy + s, checkPaint);
        canvas.drawLine(cx + s, cy - s, cx - s, cy + s, checkPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        clearAnimations();
        super.onDetachedFromWindow();
    }
}
