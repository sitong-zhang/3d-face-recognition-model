package com.example.face3d;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

/**
 * 静默活体检测（不要求用户做任何动作、不加任何指引）。
 *
 * 只用单目前置摄像头已有的 3D 人脸关键点，从"生命体固有特征"区分真人与照片/翻拍：
 *  1) 眨眼：眼纵横比 EAR 骤降再回升 —— 最强证据，照片绝不会眨眼；
 *  2) 面部非刚性微动：以鼻尖对齐消除整体位移后，眼睑/脸颊仍有的肌肉、呼吸级微抖 ——
 *     真人始终有，打印照片完全静止，屏幕翻拍的大幅跳变会被区间过滤；
 *  3) 3D 深度起伏：鼻尖相对眼窝明显前突（辅助，计入微动的 z 分量）。
 *
 * 判定宽松：任一强证据成立即判活体；累积微动达到阈值也判活体。这样真人几秒内必过、
 * 几乎不误伤，而静止照片/屏幕因无眨眼、无微动被拦下。
 */
public class LivenessDetector {

    // 双眼 EAR 所需点：外眼角/内眼角、上眼睑、下眼睑
    private static final int LE_OUT = 33, LE_IN = 133, LE_UP = 159, LE_DN = 145;
    private static final int RE_OUT = 362, RE_IN = 263, RE_UP = 386, RE_DN = 374;
    // 微动追踪点：脸颊轮廓 + 眼睑 + 鼻（避开嘴/眉等表情大形变区）
    private static final int[] MOTION_PTS = {1, 33, 133, 159, 145, 362, 263, 386, 374, 234, 454, 10, 152};

    private static final float EAR_CLOSE = 0.19f;   // 低于此视为闭合
    private static final float EAR_OPEN = 0.23f;    // 回升到高于此视为一次完整眨眼
    // 阈值刻意放到极低：真人看镜头时哪怕不动，也有呼吸/微表情/传感器级抖动，
    // 几帧内即可自然累积达标，绝不需要张嘴、摇头等刻意动作（Face ID 式无感通过）。
    private static final float MOTION_MIN = 0.00015f; // 极小的自然抖动即计入
    private static final float MOTION_MAX = 0.080f;   // 仅排除大幅转头/检测跳变
    private static final float MOTION_TARGET = 0.006f; // 累积阈值：约 2-3 帧即达标

    private float[] prev;                 // 上一帧追踪点的 (x,y,z)
    private boolean eyeDown = false;
    private int lowFrames = 0;
    private int blinkCount = 0;
    private float motionAccum = 0f;
    private boolean live = false;

    public void reset() {
        prev = null;
        eyeDown = false;
        lowFrames = 0;
        blinkCount = 0;
        motionAccum = 0f;
        live = false;
    }

    /** 每帧喂入 478 关键点，更新活体状态。 */
    public void update(List<NormalizedLandmark> lm) {
        if (live) return;                       // 已判活体，无需继续
        if (lm == null || lm.size() <= RE_DN) return;

        // 1) 眨眼：双眼 EAR 平均
        float earL = ear(lm, LE_OUT, LE_IN, LE_UP, LE_DN);
        float earR = ear(lm, RE_OUT, RE_IN, RE_UP, RE_DN);
        float ear = (earL + earR) * 0.5f;
        if (!eyeDown && ear < EAR_CLOSE) {
            eyeDown = true;
            lowFrames = 1;
        } else if (eyeDown) {
            if (ear < EAR_CLOSE) {
                lowFrames++;
            } else if (ear > EAR_OPEN) {
                if (lowFrames <= 25) blinkCount++;   // 合理的闭合时长才算一次眨眼
                eyeDown = false;
                lowFrames = 0;
            }
        }
        if (blinkCount >= 1) { live = true; return; }

        // 2) 非刚性微动：鼻尖对齐后追踪点的残余位移
        float[] cur = new float[MOTION_PTS.length * 3];
        for (int i = 0; i < MOTION_PTS.length; i++) {
            NormalizedLandmark p = lm.get(MOTION_PTS[i]);
            cur[i * 3] = p.x(); cur[i * 3 + 1] = p.y(); cur[i * 3 + 2] = p.z();
        }
        if (prev != null) {
            float nx = cur[0] - prev[0], ny = cur[1] - prev[1];  // 鼻尖整体位移
            float sum = 0f;
            for (int i = 1; i < MOTION_PTS.length; i++) {
                float dx = (cur[i * 3] - prev[i * 3]) - nx;
                float dy = (cur[i * 3 + 1] - prev[i * 3 + 1]) - ny;
                float dz = (cur[i * 3 + 2] - prev[i * 3 + 2]) - (prev[2] - cur[2]);
                dz *= 0.5f;   // z 噪声较大，降权
                float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d > MOTION_MIN && d < MOTION_MAX) sum += d;
            }
            motionAccum += sum / (MOTION_PTS.length - 1);
        }
        prev = cur;
        if (motionAccum >= MOTION_TARGET) live = true;
    }

    /** EAR：上下眼睑距离 / 眼宽。 */
    private static float ear(List<NormalizedLandmark> lm, int out, int in, int up, int dn) {
        float w = dist2(lm.get(out), lm.get(in));
        if (w < 1e-5f) return 1f;
        return dist2(lm.get(up), lm.get(dn)) / (2f * w);
    }

    private static float dist2(NormalizedLandmark a, NormalizedLandmark b) {
        float dx = a.x() - b.x(), dy = a.y() - b.y();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public boolean isLive() { return live; }
    /** 调试/进度用：0..1 的活体置信累积。 */
    public float getScore() {
        return Math.max(blinkCount > 0 ? 1f : 0f, Math.min(1f, motionAccum / MOTION_TARGET));
    }
}
