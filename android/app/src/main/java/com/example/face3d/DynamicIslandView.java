package com.example.face3d;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 灵动岛 —— 单一连续形体的形变（morph），不是两个 view 淡入淡出。
 *
 * 整个灵动岛就是【一个】黑色圆角容器（island，FrameLayout）。展开/收起时动态改变它的
 * 宽度、高度、圆角半径，让药丸真正"长"成一张卡片、"缩"回药丸，形似 iPhone 灵动岛。
 *   收起态：96×30dp，圆角 15dp（药丸）
 *   录入建模态：232×180dp（小巧卡片，刻意做小以避让下方取景圆圈）
 *   成功/失败态：300×272dp，圆角 28dp（完整卡片）
 * 药丸内的摄像头点、卡片内的动画与文案只是叠在这一个形体上的内容层，随形变进度交叉淡入淡出。
 *
 * 形变进度 morph ∈ [0,1]：0=完全药丸，1=完全卡片。几何与内容透明度都是它的函数，
 * 因此展开与收起天然对称、全程连续，没有"消失再出现"的断裂感。
 */
public class DynamicIslandView extends FrameLayout {

    public enum Mode { HIDDEN, PILL, PROGRESS, ENROLL_MODEL, SUCCESS, FAIL }
    public enum Scenario { RECOGNIZE, PAYMENT, ENROLL }

    private Mode mode = Mode.HIDDEN;
    private Scenario scenario = Scenario.RECOGNIZE;

    // 唯一的形变主体（一个可容纳内容层的容器）
    private final FrameLayout island;
    private final GradientDrawable islandBg;
    private final View pillContent;              // 药丸内的摄像头/传感器点
    private final LinearLayout cardContent;      // 卡片内的动画+文案
    private final FrameAnimView anim;
    private final FacePointCloudView faceCloud;   // 录入建模：512 光点随真实进度点亮
    private final TextView captionText, subText, nameText;

    // 几何参数（px）
    private final float pillW, pillH, pillR;
    private final float cardW, cardH, cardR;      // 成功/失败完整卡片
    private final float modelW, modelH, modelR;   // 录入建模紧凑卡片（避让取景圆圈）
    private float curCardW, curCardH, curCardR;   // 当前展开卡片尺寸（可在形态间平滑过渡）
    private final float topMarginPx;
    private final float breatheAmp;
    private float morph = 0f;                    // 0=药丸 1=卡片

    private ValueAnimator morphAnim;
    private ValueAnimator sizeAnim;
    private ValueAnimator breatheAnim;
    private boolean breathing = false;

    private String name = "", caption = "", subtitle = "";

    public DynamicIslandView(Context c) { this(c, null); }
    public DynamicIslandView(Context c, AttributeSet a) {
        super(c, a);
        float d = c.getResources().getDisplayMetrics().density;

        pillW = 96f * d; pillH = 30f * d; pillR = 15f * d;
        cardW = 300f * d; cardH = 272f * d; cardR = 28f * d;
        // 录入建模卡片做小巧：展开后底部停在取景圆环上方，不遮挡圆圈
        modelW = 232f * d; modelH = 180f * d; modelR = 24f * d;
        curCardW = cardW; curCardH = cardH; curCardR = cardR;
        topMarginPx = 12f * d;
        breatheAmp = 3.5f * d;

        // —— 形变主体：黑色圆角胶囊/卡片（唯一形体）——
        island = new FrameLayout(c);
        islandBg = new GradientDrawable();
        islandBg.setShape(GradientDrawable.RECTANGLE);
        islandBg.setColor(0xFF05070C);
        islandBg.setStroke(1, 0xFF1A1F2B);
        islandBg.setCornerRadius(pillR);
        island.setBackground(islandBg);
        LayoutParams ip = new LayoutParams((int) pillW, (int) pillH);
        ip.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        ip.topMargin = (int) topMarginPx;
        addView(island, ip);

        // —— 内容层 1：药丸圆点（随 morph 淡出）——
        pillContent = new View(c);
        pillContent.setBackgroundResource(R.drawable.island_dots);
        island.addView(pillContent, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // —— 内容层 2：卡片内容（动画+文案，随 morph 淡入）——
        cardContent = new LinearLayout(c);
        cardContent.setOrientation(LinearLayout.VERTICAL);
        cardContent.setGravity(Gravity.CENTER_HORIZONTAL);
        island.addView(cardContent, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 对勾动画尺寸 124dp（成功/失败态）
        int aw = Math.round(124 * d), ah = Math.round(124 * d);
        anim = new FrameAnimView(c);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(aw, ah);
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        ap.topMargin = Math.round(10 * d);
        cardContent.addView(anim, ap);

        // 录入建模点云：点云在小巧卡片里缩到 88dp，默认隐藏，仅 ENROLL_MODEL 态显示
        faceCloud = new FacePointCloudView(c);
        int cw = Math.round(88 * d), ch = Math.round(88 * d);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(cw, ch);
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        cp.topMargin = Math.round(10 * d);
        faceCloud.setVisibility(GONE);
        cardContent.addView(faceCloud, cp);

        captionText = new TextView(c);
        captionText.setTextColor(0xFFFFFFFF);
        captionText.setTextSize(15);
        captionText.setTypeface(null, android.graphics.Typeface.BOLD);
        captionText.setGravity(Gravity.CENTER);
        captionText.setSingleLine(true);
        captionText.setEllipsize(TextUtils.TruncateAt.END);

        nameText = new TextView(c);
        nameText.setTextColor(0xFFFFFFFF);
        nameText.setTextSize(23);
        nameText.setTypeface(null, android.graphics.Typeface.BOLD);
        nameText.setGravity(Gravity.CENTER);
        nameText.setSingleLine(true);
        nameText.setEllipsize(TextUtils.TruncateAt.END);
        nameText.setIncludeFontPadding(false);

        subText = new TextView(c);
        subText.setTextColor(0xFFB0B7C3);
        subText.setTextSize(11);
        subText.setGravity(Gravity.CENTER);
        subText.setSingleLine(true);
        subText.setEllipsize(TextUtils.TruncateAt.END);

        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Math.round(4 * d);
        cardContent.addView(captionText, tp);
        cardContent.addView(nameText, tp);
        cardContent.addView(subText, tp);

        // 初始为纯药丸
        applyMorph(0f);
        pillContent.setAlpha(1f);
        cardContent.setAlpha(0f);
        cardContent.setVisibility(GONE);
    }

    /** 兼容旧接口：星星已并入灵动岛，无独立显示。 */
    public void attachSparkles(View v) { /* no-op */ }

    /** 收回为药丸：同一形体连续缩回。 */
    public void showPill() {
        if (mode == Mode.PILL && morph < 0.01f && !breathing) return;
        mode = Mode.PILL;
        stopBreathe();
        anim.stop();
        anim.setVisibility(GONE);
        faceCloud.setVisibility(GONE);
        morphTo(0f, 440, new DecelerateInterpolator(), null);
    }

    /** 进行中：药丸状态轻微呼吸（宽度起伏），不形变。 */
    public void showProgress() {
        if (mode == Mode.PROGRESS) return;
        mode = Mode.PROGRESS;
        stopBreathe();
        anim.stop();
        faceCloud.setVisibility(GONE);
        morphTo(0f, 260, new DecelerateInterpolator(), this::startBreathe);
    }

    /**
     * 录入建模态：药丸形变展开成【紧凑】卡片，卡片内显示 512 光点云。
     * 紧凑尺寸确保卡片底部停在取景圆环上方，不遮挡圆形相机画面。
     */
    public void showEnrollModel() {
        mode = Mode.ENROLL_MODEL;
        scenario = Scenario.ENROLL;
        stopBreathe();
        anim.stop();
        anim.setVisibility(GONE);
        faceCloud.reset();
        faceCloud.setVisibility(VISIBLE);
        name = "";
        caption = "正在建立 3D 模型";
        subtitle = "0 / 9 区域";
        applyText();
        cardContent.setVisibility(VISIBLE);
        // 按点云 + 文案的实际测量尺寸自适应：文字少就收窄、点云大就放宽
        float[] sz = cardSizeFor(Mode.ENROLL_MODEL);
        setCardSize(sz[0], sz[1], sz[2]);   // 先切到自适应尺寸（此时仍在药丸态，无跳变）
        morphTo(1f, 560, new OvershootInterpolator(0.7f), null);   // 展开带回弹，灵动岛手感
    }

    /**
     * 逐点探针扫描：以 (probeX,probeY) 为探针位置，只点亮扫到的少数几颗光点。
     * 视觉上是"扫到哪颗、哪颗才亮"，一颗颗累积。字幕显示真实点数进度。
     */
    public int sweepModel(float probeX, float probeY) {
        if (mode != Mode.ENROLL_MODEL) return 0;
        int lit = faceCloud.sweepAt(probeX, probeY);
        subtitle = faceCloud.getLitPointCount() + " / " + FacePointCloudView.TOTAL + " 光点";
        subText.setText(subtitle);
        return lit;
    }

    public boolean isModelComplete() { return faceCloud.isComplete(); }
    /** 当前已点亮的真实光点数（供资产采集节流）。 */
    public int getModelLitCount() { return faceCloud.getLitPointCount(); }

    /**
     * 进入重建态：灵动岛保持建模卡片形态，标题切到"正在重建 3D 模型"，
     * 让用户在采集完成后能亲眼看到 3D 人脸从已扫的部分逐点成型到完整。
     */
    public void showRebuilding() {
        if (mode != Mode.ENROLL_MODEL) return;
        caption = "正在重建 3D 模型";
        subtitle = "0 / " + FacePointCloudView.TOTAL + " 光点";
        applyText();
        faceCloud.beginRebuild();
    }

    /** 由后台真实融合进度驱动：更新点云成型 + 字幕百分比。 */
    public void setRebuildProgress(float p) {
        if (mode != Mode.ENROLL_MODEL) return;
        faceCloud.setRebuildProgress(p);
        subtitle = faceCloud.getLitPointCount() + " / " + FacePointCloudView.TOTAL + " 光点";
        subText.setText(subtitle);
    }

    /** 录入建模态：每帧把真实人头朝向与位置喂给点云，让 3D 人脸跟着真人的头移动/转动。 */
    public void updateHeadPose(float yaw, float pitch, float faceX, float faceY) {
        if (mode != Mode.ENROLL_MODEL) return;
        faceCloud.setHeadPose(yaw, pitch, faceX, faceY);
    }

    public void showSuccess(String personName) {
        scenario = Scenario.RECOGNIZE;
        name = personName == null ? "" : personName;
        caption = "已识别";
        subtitle = "Face ID 验证通过";
        expand(true);
    }

    public void showEnrolled(String personName) {
        scenario = Scenario.ENROLL;
        name = personName == null ? "" : personName;
        caption = "录入成功";
        subtitle = "3D 人脸模型已建立";
        expand(true);
    }

    public void showPaymentSuccess(String amount) {
        scenario = Scenario.PAYMENT;
        name = amount == null || amount.isEmpty() ? "已完成" : amount;
        caption = "支付成功";
        subtitle = "Face ID 已确认 · Apple Pay";
        expand(true);
    }

    public void showFail(String message) {
        scenario = Scenario.RECOGNIZE;
        name = "";
        caption = "未识别";
        subtitle = message == null ? "请重试" : message;
        expand(false);
    }

    public void showPaymentFail() {
        scenario = Scenario.PAYMENT;
        name = "";
        caption = "支付未完成";
        subtitle = "Face ID 未通过 · 请重试";
        expand(false);
    }

    /** 展开：药丸连续形变成卡片，形变完成后再播对勾动画。 */
    private void expand(boolean success) {
        mode = success ? Mode.SUCCESS : Mode.FAIL;
        stopBreathe();
        faceCloud.setVisibility(GONE);
        anim.setVisibility(VISIBLE);
        applyText();
        cardContent.setVisibility(VISIBLE);
        // 按姓名/文案实际宽度自适应卡片尺寸，再平滑长大（带回弹）
        float[] sz = cardSizeFor(mode);
        tweenCardSize(sz[0], sz[1], sz[2], 500);
        morphTo(1f, 560, new OvershootInterpolator(0.7f), () -> {
            if (mode == Mode.SUCCESS || mode == Mode.FAIL) {
                anim.play(success ? "success" : "fail", false, 24);
                if (!success) shake();
            }
        });
    }

    /** 把 morph 从当前值平滑驱动到 target，并同步几何与内容透明度。 */
    private void morphTo(float target, long duration, Interpolator interp, Runnable endAction) {
        if (morphAnim != null) morphAnim.cancel();
        final float from = morph;
        morphAnim = ValueAnimator.ofFloat(from, target);
        morphAnim.setDuration(duration);
        morphAnim.setInterpolator(interp);
        morphAnim.addUpdateListener(a -> applyMorph((float) a.getAnimatedValue()));
        if (endAction != null) {
            morphAnim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { endAction.run(); }
            });
        }
        morphAnim.start();
    }

    /** 立即切换当前展开卡片的目标尺寸（用于药丸态出发前，不会造成视觉跳变）。 */
    private void setCardSize(float w, float h, float r) {
        if (sizeAnim != null) sizeAnim.cancel();
        curCardW = w; curCardH = h; curCardR = r;
        applyMorph(morph);
    }

    /** 在已展开状态下平滑过渡卡片尺寸（紧凑建模卡 → 完整成功卡）。 */
    private void tweenCardSize(float tw, float th, float tr, long duration) {
        if (sizeAnim != null) sizeAnim.cancel();
        final float fw = curCardW, fh = curCardH, fr = curCardR;
        sizeAnim = ValueAnimator.ofFloat(0f, 1f);
        sizeAnim.setDuration(duration);
        sizeAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            curCardW = fw + (tw - fw) * t;
            curCardH = fh + (th - fh) * t;
            curCardR = fr + (tr - fr) * t;
            applyMorph(morph);
        });
        sizeAnim.start();
    }

    /**
     * 形变核心：morph 同时决定几何尺寸、圆角、两层内容透明度。
     * 呼吸只在药丸态（m≈0）叠加宽度起伏，展开后自动消失。
     */
    private void applyMorph(float m) {
        morph = m;
        float breath = 0f;
        if (breathing) {
            double phase = System.currentTimeMillis() / 175.0;
            breath = breatheAmp * (float) (0.5 - 0.5 * Math.cos(phase));
        }
        float pillBreath = (1f - m) * breath;

        int w = Math.round(pillW + (curCardW - pillW) * m + pillBreath);
        int h = Math.round(pillH + (curCardH - pillH) * m);
        float r = pillR + (curCardR - pillR) * Math.min(1f, Math.max(0f, m));
        LayoutParams lp = (LayoutParams) island.getLayoutParams();
        lp.width = w;
        lp.height = h;
        island.setLayoutParams(lp);
        islandBg.setCornerRadius(r);

        // 药丸圆点：形变前 30% 快速淡出；卡片内容：形变过 28% 后淡入
        pillContent.setAlpha(Math.max(0f, 1f - m / 0.30f));
        float cardAlpha = Math.max(0f, Math.min(1f, (m - 0.28f) / 0.6f));
        cardContent.setAlpha(cardAlpha);
    }

    private void startBreathe() {
        if (mode != Mode.PROGRESS) return;
        breathing = true;
        if (breatheAnim != null) breatheAnim.cancel();
        breatheAnim = ValueAnimator.ofFloat(0f, 1f);
        breatheAnim.setDuration(1200);
        breatheAnim.setRepeatCount(ValueAnimator.INFINITE);
        breatheAnim.addUpdateListener(a -> { if (breathing) applyMorph(morph); });
        breatheAnim.start();
    }

    private void stopBreathe() {
        breathing = false;
        if (breatheAnim != null) { breatheAnim.cancel(); breatheAnim = null; }
    }

    private void shake() {
        float d = getResources().getDisplayMetrics().density;
        island.animate().translationX(8 * d).setDuration(70)
                .withEndAction(() -> island.animate().translationX(-7 * d).setDuration(90)
                        .withEndAction(() -> island.animate().translationX(5 * d).setDuration(90)
                                .withEndAction(() -> island.animate().translationX(0).setDuration(90)
                                        .start()).start()).start()).start();
    }

    private void applyText() {
        captionText.setText(caption);
        subText.setText(subtitle);
        nameText.setText(name);
        nameText.setVisibility(name.isEmpty() ? GONE : VISIBLE);
    }

    /**
     * 按当前文案的真实测量宽度 + 内容元素尺寸，算出灵动岛此刻【实际需要】的卡片大小。
     * 姓名短 → 卡片收窄；姓名长 → 卡片放宽；建模态则按点云与提示文字取较大值。
     * 这样灵动岛会随内容真正地变大变小，而不是永远一个固定尺寸。
     * 返回 {宽, 高, 圆角}（px）。
     */
    private float[] cardSizeFor(Mode m) {
        float d = getResources().getDisplayMetrics().density;
        float padX = 34f * d;   // 左右内边距
        float maxTextW;
        if (m == Mode.ENROLL_MODEL) {
            // 建模态：点云边长 88dp 与两行提示文字取较宽者
            maxTextW = Math.max(measureTextW(captionText), measureTextW(subText));
            maxTextW = Math.max(maxTextW, 88f * d);
        } else {
            // 成功/失败态：姓名、标题、副标题三行取最宽者（姓名通常决定宽度）
            maxTextW = Math.max(measureTextW(nameText),
                    Math.max(measureTextW(captionText), measureTextW(subText)));
            maxTextW = Math.max(maxTextW, 124f * d);   // 不小于对勾动画宽度
        }
        float needW = maxTextW + 2f * padX;

        if (m == Mode.ENROLL_MODEL) {
            float w = clamp(needW, 200f * d, 300f * d);
            float h = 180f * d;
            return new float[]{w, h, 24f * d};
        } else {
            float w = clamp(needW, 240f * d, 380f * d);
            float h = 272f * d;
            return new float[]{w, h, 28f * d};
        }
    }

    /** 用 TextView 的 Paint 精确测量当前文本的像素宽度。 */
    private float measureTextW(TextView tv) {
        CharSequence t = tv.getText();
        if (t == null || t.length() == 0 || tv.getVisibility() == GONE) return 0f;
        return tv.getPaint().measureText(t.toString());
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public Mode getMode() { return mode; }
    public Scenario getScenario() { return scenario; }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (morphAnim != null) morphAnim.cancel();
        if (sizeAnim != null) sizeAnim.cancel();
        stopBreathe();
        anim.stop();
    }
}
