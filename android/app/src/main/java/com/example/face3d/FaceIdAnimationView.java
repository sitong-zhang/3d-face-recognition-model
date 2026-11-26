package com.example.face3d;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 取景框视图 —— 动画素材直接复用开源项目 jonnyoo/glance（MIT）的官方 Face ID 解锁动画。
 *
 * - SCAN / ENROLL：Face ID 扫描脸循环动画（anim/scan）
 * - SUCCESS：圆环旋转 -> 对勾（anim/success）
 * - FAIL：圆环 -> 退回扫描脸（anim/fail）
 * - IDLE：静态扫描脸首帧
 *
 * 全部为透明逐帧动画（黑底已转透明），叠加在相机预览之上，不再自绘任何人脸或方框。
 * 对外接口保持 setState(State) / setEnrollProgress(float) 不变。
 */
public class FaceIdAnimationView extends FrameLayout {

    public enum State { IDLE, ENROLL, SCAN, SUCCESS, FAIL }

    private State state = State.IDLE;
    private final FrameAnimView anim;

    public FaceIdAnimationView(Context c) { this(c, null); }
    public FaceIdAnimationView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(Color.TRANSPARENT);
        anim = new FrameAnimView(c);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(anim, lp);
        anim.play("scan", true, 20);
    }

    public void setState(State s) {
        this.state = s;
        switch (s) {
            case SUCCESS:
                anim.play("success", false, 24);
                break;
            case FAIL:
                anim.play("fail", false, 24);
                break;
            case ENROLL:
            case SCAN:
                anim.play("scan", true, 20);
                break;
            default:
                anim.play("scan", true, 20);
                break;
        }
    }

    /** glance 扫描动画为循环素材，录入进度改由底部文字提示呈现，此处保留接口兼容。 */
    public void setEnrollProgress(float p) { /* no-op：循环扫描动画本身不占位 */ }
    public State getState() { return state; }

    @Override
    protected void onDetachedFromWindow() {
        anim.stop();
        super.onDetachedFromWindow();
    }
}
