package com.example.face3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 透明逐帧动画播放器。
 *
 * 动画素材直接取自开源项目 jonnyoo/glance（Face Unlock for Mac, MIT）的官方解锁动画：
 * - anim/scan    待机扫描：Face ID 扫描脸循环动画
 * - anim/success 解锁成功：圆环旋转 -> 对勾
 * - anim/fail    解锁失败：圆环 -> 退回扫描脸
 * 原素材为黑底 MP4，已做黑底转透明处理（保留蓝色发光线条），可直接叠加在相机预览/深色卡片上。
 *
 * 本类只负责按 assets 中的帧序列播放，是通用播放器；不绘制任何图形。
 */
public class FrameAnimView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final List<Bitmap> frames = new ArrayList<>();
    private String currentSet = "";
    private int index = 0;
    private boolean loop = true;
    private boolean running = false;
    private int fps = 24;
    private long lastTick = 0;

    public FrameAnimView(Context c) { this(c, null); }
    public FrameAnimView(Context c, AttributeSet a) { super(c, a); }

    /** 加载并切换到一组动画。setName 为 assets/anim/ 下的目录名。 */
    public void play(String setName, boolean loop, int fps) {
        if (!setName.equals(currentSet) || frames.isEmpty()) {
            loadFrames(setName);
            currentSet = setName;
        }
        this.loop = loop;
        this.fps = fps;
        this.index = 0;
        this.running = true;
        this.lastTick = 0;
        unschedule();
        schedule();
        invalidate();
    }

    public void stop() {
        running = false;
        clearFrames();
        unschedule();
    }

    private final android.view.Choreographer.FrameCallback ticker =
            new android.view.Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    if (!running) return;
                    tick(frameTimeNanos / 1_000_000L);
                    schedule();
                }
            };

    private void schedule() {
        if (running) android.view.Choreographer.getInstance().postFrameCallback(ticker);
    }

    private void unschedule() {
        android.view.Choreographer.getInstance().removeFrameCallback(ticker);
    }

    private void loadFrames(String setName) {
        clearFrames();
        android.content.res.AssetManager am = getContext().getAssets();
        try {
            String[] names = am.list("anim/" + setName);
            if (names == null) return;
            java.util.Arrays.sort(names);
            for (String n : names) {
                if (!n.toLowerCase().endsWith(".webp") && !n.toLowerCase().endsWith(".png")) continue;
                android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
                opt.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
                java.io.InputStream is = am.open("anim/" + setName + "/" + n);
                Bitmap b = android.graphics.BitmapFactory.decodeStream(is, null, opt);
                is.close();
                if (b != null) frames.add(b);
            }
        } catch (Exception ignored) { }
    }

    private void clearFrames() {
        for (Bitmap b : frames) if (!b.isRecycled()) b.recycle();
        frames.clear();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frames.isEmpty()) return;
        if (index >= frames.size()) index = loop ? 0 : frames.size() - 1;
        Bitmap b = frames.get(index);
        if (b == null) return;
        float left = (getWidth() - b.getWidth()) / 2f;
        float top = (getHeight() - b.getHeight()) / 2f;
        canvas.drawBitmap(b, left, top, paint);
    }

    /** 由外部帧循环驱动（与相机帧率解耦，保证动画流畅）。 */
    public void tick(long nowMs) {
        if (!running || frames.isEmpty()) return;
        if (lastTick == 0) lastTick = nowMs;
        long interval = 1000L / Math.max(1, fps);
        if (nowMs - lastTick >= interval) {
            lastTick = nowMs;
            if (index < frames.size() - 1) {
                index++;
            } else if (loop) {
                index = 0;
            } else {
                running = false; // 播完停在最后一帧
            }
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        clearFrames();
    }
}
