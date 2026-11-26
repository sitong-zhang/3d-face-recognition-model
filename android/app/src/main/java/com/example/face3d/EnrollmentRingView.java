package com.example.face3d;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * 录入进度圆环 —— 移植自开源项目 jonnyoo/glance（MIT）的 EnrollmentRingView。
 *
 * 视觉：
 *  - 80 个 tick 均分一圈；待机为白色（glance whiteToAccent(0)）。
 *  - 录入采集过程只做轻微中心脉冲反馈，不再有转头指向箭头（按需求删除转头引导）。
 *  - 录入完成：startBlueSweep() —— 一道蓝色光头沿白色圆环缓慢转一整圈，
 *    扫过的白色 tick 逐渐变成蓝色（sweepState），这就是"蓝光围着白圈转、
 *    白色慢慢变成蓝色"。转完一整圈后整环为蓝，随后完成环浮现。
 *
 * 坐标系：compass 角 0=正上、顺时针；Android Canvas 0=右、顺时针，故统一 -90°。
 */
public class EnrollmentRingView extends View {

    private static final int TICK_COUNT = 80;
    // 录入取景是全屏黑色相机场景，圆环保持白 tick + 亮蓝 accent，不跟随主面板白黑配色。
    private static final int ACCENT = Color.parseColor("#3499FF"); // glance GlanceTheme.accent
    // 圆圈外背景改为白色，未点亮的 tick 必须用深色才能在白底上看见（白→蓝改为深灰→蓝）
    private static final int IDLE = Color.parseColor("#3A4152");
    private static final int BLOOM = Color.parseColor("#CFE9FF");  // 蓝光头核心亮色

    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean complete = false;
    private float pulse = 0f;            // center 脉冲 0..1
    private float completeAnim = 0f;     // 完成过渡（ticks 淡出 + 完成环淡入）0..1

    // 蓝光绕圈状态
    private float sweepHead = -1f;       // <0 表示未开始；否则为当前光头的 tick 序号（0..TICK_COUNT）
    private float sweepBlue[] = new float[TICK_COUNT]; // 每个 tick 的变蓝程度 0..1
    private ValueAnimator sweepAnim, pulseAnim, completeAnimObj;

    public EnrollmentRingView(Context c) { this(c, null); }
    public EnrollmentRingView(Context c, AttributeSet a) {
        super(c, a);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(ACCENT);
    }

    /** 采集一帧的轻微中心脉冲反馈（替代旧的转头指向）。 */
    public void pulseCenter() {
        if (pulseAnim != null) pulseAnim.cancel();
        pulseAnim = ValueAnimator.ofFloat(0f, 1f);
        pulseAnim.setDuration(240);
        pulseAnim.addUpdateListener(a -> { pulse = (float) a.getAnimatedValue(); invalidate(); });
        pulseAnim.start();
    }

    /**
     * 完成序列：一道蓝光头沿白色圆环缓慢转一整圈，扫过的 tick 由白渐变为蓝。
     * @param durationMs 转一整圈时长
     * @param onDone 转完回调
     */
    public void startBlueSweep(long durationMs, final Runnable onDone) {
        complete = true;
        if (sweepAnim != null) sweepAnim.cancel();
        java.util.Arrays.fill(sweepBlue, 0f);
        sweepHead = 0f;
        sweepAnim = ValueAnimator.ofFloat(0f, TICK_COUNT);
        sweepAnim.setDuration(durationMs);
        sweepAnim.setInterpolator(new LinearInterpolator());
        sweepAnim.addUpdateListener(a -> {
            float head = (float) a.getAnimatedValue();
            sweepHead = head;
            // 光头扫过的 tick 变蓝：head 之前的完全变蓝，正在扫的按距离渐蓝
            for (int i = 0; i < TICK_COUNT; i++) {
                float d = head - i;
                if (d >= 1f) sweepBlue[i] = 1f;
                else if (d > 0f) sweepBlue[i] = Math.max(sweepBlue[i], d);
            }
            invalidate();
        });
        sweepAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                java.util.Arrays.fill(sweepBlue, 1f);  // 转完：整环蓝
                sweepHead = -1f;
                invalidate();
                if (onDone != null) onDone.run();
            }
        });
        sweepAnim.start();
    }

    /** 全部采集完成：蓝光转完后调用，ticks 淡出、完成环浮现。 */
    public void setComplete(boolean done) {
        this.complete = done;
        if (done) {
            if (completeAnimObj != null) completeAnimObj.cancel();
            completeAnimObj = ValueAnimator.ofFloat(0f, 1f);
            completeAnimObj.setDuration(450);
            completeAnimObj.setInterpolator(new DecelerateInterpolator());
            completeAnimObj.addUpdateListener(a -> { completeAnim = (float) a.getAnimatedValue(); invalidate(); });
            completeAnimObj.start();
        } else {
            completeAnim = 0f;
            invalidate();
        }
    }

    public void reset() {
        if (sweepAnim != null) sweepAnim.cancel();
        if (pulseAnim != null) pulseAnim.cancel();
        if (completeAnimObj != null) completeAnimObj.cancel();
        sweepHead = -1f;
        java.util.Arrays.fill(sweepBlue, 0f);
        complete = false; pulse = 0f; completeAnim = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float S = Math.min(w, h);

        float radius = S * 0.4167f;        // tick 内端半径
        float lenUnlit = S * 0.050f;
        float lenLit = S * 0.0833f;
        float widthPx = Math.max(2.5f, S * 0.010f);
        float degPerTick = 360f / TICK_COUNT;

        float pulseAmt = pulse > 0f ? (float) Math.sin(pulse * Math.PI) : 0f;
        float ticksAlpha = 1f - completeAnim; // 完成时 ticks 淡出

        if (ticksAlpha > 0.01f) {
            for (int i = 0; i < TICK_COUNT; i++) {
                float compass = i * degPerTick;                  // 0=上，顺时针
                double theta = Math.toRadians(compass - 90f);    // 转 Canvas 坐标

                float blue = sweepBlue[i];                       // 该 tick 变蓝程度
                // 蓝光头：正在扫过的几个 tick 高亮加长（渐变蓝白）
                float bloom = 0f;
                if (sweepHead >= 0f) {
                    float d = sweepHead - i;
                    if (d >= 0f && d < 1.4f) bloom = 1f - d / 1.4f;
                }
                // 中心脉冲也让它短暂变蓝加长
                float grow = Math.max(blue, Math.max(bloom, pulseAmt));
                float len = lenUnlit + (lenLit - lenUnlit) * grow;

                int color;
                if (bloom > 0f) {
                    color = lerp(ACCENT, BLOOM, bloom);           // 光头：蓝→亮蓝白
                } else if (blue > 0f) {
                    color = lerp(IDLE, ACCENT, blue);             // 扫过：深灰渐变蓝
                } else if (pulseAmt > 0.01f) {
                    color = lerp(IDLE, ACCENT, pulseAmt);
                } else {
                    color = IDLE;                                 // 未点亮：深灰（白底可见）
                }

                float cos = (float) Math.cos(theta), sin = (float) Math.sin(theta);
                float x1 = cx + radius * cos, y1 = cy + radius * sin;
                float x2 = cx + (radius + len) * cos, y2 = cy + (radius + len) * sin;

                tickPaint.setColor(color);
                tickPaint.setAlpha((int) (255 * ticksAlpha));
                tickPaint.setStrokeWidth(widthPx + (blue > 0.5f || bloom > 0f ? widthPx * 0.35f : 0f));
                c.drawLine(x1, y1, x2, y2, tickPaint);
            }
        }

        // 完成环：蓝光转完后留下的一圈 accent 环
        if (completeAnim > 0f) {
            float scale = 0.92f + 0.08f * completeAnim;
            float ringRadius = S * 0.4367f * scale;
            ringPaint.setAlpha((int) (255 * completeAnim));
            ringPaint.setStrokeWidth(Math.max(3f, S * 0.0583f));
            c.drawCircle(cx, cy, ringRadius, ringPaint);
        }
    }

    private static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.rgb(
                Math.round(ar + (br - ar) * t),
                Math.round(ag + (bg - ag) * t),
                Math.round(ab + (bb - ab) * t));
    }

    @Override
    protected void onDetachedFromWindow() {
        if (sweepAnim != null) sweepAnim.cancel();
        if (pulseAnim != null) pulseAnim.cancel();
        if (completeAnimObj != null) completeAnimObj.cancel();
        super.onDetachedFromWindow();
    }
}
