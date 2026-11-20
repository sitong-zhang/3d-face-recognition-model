package com.example.face3d;

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
 * 苹果 Face ID 风格动画视图（自研绘制）。
 *
 * 状态：IDLE -> (ENROLL 录入引导 | SCAN 扫描) -> SUCCESS / FAIL
 * - ENROLL：分段圆环进度（随转头引导推进），中心显示人脸轮廓
 * - SCAN：旋转高亮弧 + 呼吸光晕
 * - SUCCESS：绿色圆环 + 对勾逐步绘出（模仿 Face ID），带弹跳
 * - FAIL：红色圆环 + 叉号
 */
public class FaceIdAnimationView extends View {

    public enum State { IDLE, ENROLL, SCAN, SUCCESS, FAIL }

    private State state = State.IDLE;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path checkPath = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();
    private final RectF ringRect = new RectF();

    private int ringColor = Color.rgb(79, 141, 247);
    private float sweep = 0f;           // 扫描旋转角度
    private float enrollProgress = 0f;  // 录入引导进度 0..1
    private float checkProgress = 0f;   // 对勾绘制进度 0..1
    private float successScale = 1f;    // 成功弹跳缩放
    private float pulse = 0f;           // 呼吸

    private ValueAnimator scanAnimator;
    private ValueAnimator successAnimator;
    private ValueAnimator enrollAnimator;

    public FaceIdAnimationView(Context c) { this(c, null); }
    public FaceIdAnimationView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        facePaint.setStyle(Paint.Style.STROKE);
        facePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setState(State s) {
        this.state = s;
        clearAnimations();
        switch (s) {
            case ENROLL:
                ringColor = Color.rgb(79, 141, 247);
                enrollProgress = 0f;
                checkProgress = 0f;
                successScale = 1f;
                startEnrollPulse();
                break;
            case SCAN:
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
            default:
                ringColor = Color.rgb(79, 141, 247);
                checkProgress = 0f;
                enrollProgress = 0f;
                successScale = 1f;
                sweep = 0f;
                break;
        }
        invalidate();
    }

    /** 录入时由外部驱动进度 0..1（每采到一个角度推进）。 */
    public void setEnrollProgress(float p) {
        this.enrollProgress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    public State getState() { return state; }

    private void clearAnimations() {
        if (scanAnimator != null) scanAnimator.cancel();
        if (successAnimator != null) successAnimator.cancel();
        if (enrollAnimator != null) enrollAnimator.cancel();
        scanAnimator = successAnimator = enrollAnimator = null;
    }

    private void startEnrollPulse() {
        enrollAnimator = ValueAnimator.ofFloat(0f, 1f);
        enrollAnimator.setDuration(1200);
        enrollAnimator.setRepeatCount(ValueAnimator.INFINITE);
        enrollAnimator.addUpdateListener(a -> {
            pulse = (float) (0.5 + 0.5 * Math.sin((float) a.getAnimatedValue() * 2 * Math.PI));
            invalidate();
        });
        enrollAnimator.start();
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

    /** 成功动画：圆环弹一下，同时对勾逐步画出（模仿 Face ID）。 */
    private void startSuccessAnimation() {
        successAnimator = ValueAnimator.ofFloat(0f, 1f);
        successAnimator.setDuration(650);
        successAnimator.setInterpolator(new OvershootInterpolator(1.2f));
        successAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            checkProgress = t;
            successScale = 0.9f + 0.1f * t;
            invalidate();
        });
        successAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - 20f;
        ringRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        float stroke = 9f;
        ringPaint.setStrokeWidth(stroke);
        glowPaint.setStrokeWidth(stroke + 12);

        canvas.save();
        canvas.scale(successScale, successScale, cx, cy);

        if (state == State.SCAN) {
            drawFaceOutline(canvas, cx, cy, radius, Color.argb(70, 255, 255, 255));
            ringPaint.setColor(ringColor);
            ringPaint.setAlpha(55);
            canvas.drawArc(ringRect, 0, 360, false, ringPaint);
            ringPaint.setAlpha(255);
            canvas.drawArc(ringRect, sweep - 90, 95, false, ringPaint);
            canvas.drawArc(ringRect, sweep + 180 - 90, 60, false, ringPaint);
            glowPaint.setColor(ringColor);
            glowPaint.setAlpha((int) (24 + 40 * pulse));
            canvas.drawArc(ringRect, 0, 360, false, glowPaint);
        } else if (state == State.ENROLL) {
            drawFaceOutline(canvas, cx, cy, radius, Color.argb(90, 255, 255, 255));
            ringPaint.setColor(ringColor);
            ringPaint.setAlpha(45);
            canvas.drawArc(ringRect, 0, 360, false, ringPaint);
            // 分段进度环：从顶部顺时针填充
            ringPaint.setAlpha(255);
            float angle = enrollProgress * 360f;
            if (angle > 1f) canvas.drawArc(ringRect, -90, angle, false, ringPaint);
            glowPaint.setColor(ringColor);
            glowPaint.setAlpha((int) (18 + 22 * pulse));
            canvas.drawArc(ringRect, 0, 360, false, glowPaint);
        } else {
            // IDLE / SUCCESS / FAIL：完整圆环
            glowPaint.setColor(ringColor);
            glowPaint.setAlpha(state == State.SUCCESS ? 48 : 22);
            canvas.drawArc(ringRect, 0, 360, false, glowPaint);
            ringPaint.setColor(ringColor);
            ringPaint.setAlpha(state == State.IDLE ? 80 : 255);
            canvas.drawArc(ringRect, 0, 360, false, ringPaint);

            if (state == State.SUCCESS && checkProgress > 0f) {
                drawCheck(canvas, cx, cy, radius, checkProgress);
            } else if (state == State.FAIL) {
                drawCross(canvas, cx, cy, radius);
            } else if (state == State.IDLE) {
                drawFaceOutline(canvas, cx, cy, radius, Color.argb(110, 255, 255, 255));
            }
        }
        canvas.restore();
    }

    /** 画人脸轮廓引导（额头弧 + 双眼 + 鼻 + 嘴），Face ID 取景框内的脸形。 */
    private void drawFaceOutline(Canvas canvas, float cx, float cy, float radius, int color) {
        facePaint.setColor(color);
        facePaint.setStrokeWidth(4f);
        float rx = radius * 0.52f, ry = radius * 0.66f;
        // 脸型轮廓（左右两段弧）
        RectF faceRect = new RectF(cx - rx, cy - ry, cx + rx, cy + ry);
        canvas.drawArc(faceRect, 200, 140, false, facePaint);
        canvas.drawArc(faceRect, 20, 140, false, facePaint);
        // 双眼
        float eyeY = cy - ry * 0.18f;
        float eyeDx = rx * 0.42f;
        facePaint.setStrokeWidth(6f);
        canvas.drawPoint(cx - eyeDx, eyeY, facePaint);
        canvas.drawPoint(cx + eyeDx, eyeY, facePaint);
        // 鼻
        facePaint.setStrokeWidth(4f);
        canvas.drawLine(cx, cy - ry * 0.05f, cx, cy + ry * 0.18f, facePaint);
        // 嘴
        canvas.drawArc(new RectF(cx - rx * 0.35f, cy + ry * 0.22f,
                cx + rx * 0.35f, cy + ry * 0.5f), 10, 160, false, facePaint);
    }

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
        pathMeasure.getSegment(0, len * Math.min(1f, progress), partial, true);
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
