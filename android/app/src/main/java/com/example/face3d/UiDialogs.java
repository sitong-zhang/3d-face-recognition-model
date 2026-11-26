package com.example.face3d;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

/** 统一的应用内弹窗：命名录入 / 人员管理，白黑配色圆角卡片。 */
final class UiDialogs {
    private UiDialogs() {}

    // 当前人员管理弹窗，供删除后原地关闭并重建列表（实时刷新）
    private static AlertDialog manageDialog;
    static void dismissManage() {
        if (manageDialog != null) {
            try { manageDialog.dismiss(); } catch (Exception ignored) { }
            manageDialog = null;
        }
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable cardBg(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(c, 24));
        g.setStroke(1, Color.rgb(229, 229, 234));
        return g;
    }

    private static Window setup(AlertDialog d, Context c) {
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.CENTER);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = dp(c, 320);
            lp.horizontalMargin = dp(c, 24);
            w.setAttributes(lp);
        }
        return w;
    }

    /** 人员行内的紧凑文字按钮。 */
    private static TextView smallBtn(Context c, String text, int bg, int fg) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(11);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 10), dp(c, 7), dp(c, 10), dp(c, 7));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(bg);
        g.setCornerRadius(dp(c, 9));
        b.setBackground(g);
        return b;
    }

    private static Button primaryBtn(Context c, String text, int bgColor) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(bgColor);
        g.setCornerRadius(dp(c, 14));
        b.setBackground(g);
        b.setStateListAnimator(null);
        return b;
    }

    /** 命名录入弹窗。 */
    static void showNameDialog(Context c, String title, String hint, String startLabel,
                               NameCallback cb) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(cardBg(c));
        box.setPadding(dp(c, 24), dp(c, 26), dp(c, 24), dp(c, 22));

        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.ic_scan_face);
        icon.setColorFilter(Color.rgb(60, 60, 67));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40));
        ip.bottomMargin = dp(c, 12);
        icon.setLayoutParams(ip);
        box.addView(icon);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Color.rgb(28, 28, 30));
        t.setTextSize(20);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = dp(c, 6);
        t.setLayoutParams(tp);
        box.addView(t);

        TextView sub = new TextView(c);
        sub.setText("录入后可为这张脸建立独立的 3D 模型");
        sub.setTextColor(Color.rgb(110, 110, 115));
        sub.setTextSize(12);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.bottomMargin = dp(c, 18);
        sub.setLayoutParams(sp);
        box.addView(sub);

        EditText input = new EditText(c);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(hint);
        input.setHintTextColor(Color.rgb(142, 142, 147));
        input.setTextColor(Color.rgb(28, 28, 30));
        input.setSingleLine(true);
        GradientDrawable eg = new GradientDrawable();
        eg.setShape(GradientDrawable.RECTANGLE);
        eg.setColor(Color.rgb(242, 242, 247));
        eg.setCornerRadius(dp(c, 12));
        eg.setStroke(1, Color.rgb(209, 209, 214));
        input.setBackground(eg);
        input.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams inp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inp.bottomMargin = dp(c, 22);
        input.setLayoutParams(inp);
        box.addView(input);

        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        Button cancel = new Button(c);
        cancel.setText("取消");
        cancel.setTextColor(Color.rgb(110, 110, 115));
        cancel.setTextSize(15);
        cancel.setAllCaps(false);
        cancel.setBackgroundColor(Color.TRANSPARENT);
        Button ok = primaryBtn(c, startLabel, Color.rgb(28, 28, 30));

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 44));
        cancel.setLayoutParams(bp);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 44));
        op.leftMargin = dp(c, 8);
        ok.setLayoutParams(op);
        actions.addView(cancel);
        actions.addView(ok);
        box.addView(actions);

        AlertDialog d = new AlertDialog.Builder(c)
                .setView(box)
                .setCancelable(true)
                .create();
        setup(d, c);
        cancel.setOnClickListener(v -> d.dismiss());
        ok.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError("请输入姓名");
                return;
            }
            d.dismiss();
            cb.onName(name);
        });
        d.show();
        input.requestFocus();
    }

    /** 人员管理弹窗。 */
    static void showManageDialog(Context c, List<String> labels, int[] indices,
                                 DeleteCallback deleteCb, ViewCallback viewCb) {
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(cardBg(c));
        box.setPadding(dp(c, 22), dp(c, 22), dp(c, 22), dp(c, 16));

        TextView t = new TextView(c);
        t.setText("已录入人员");
        t.setTextColor(Color.rgb(28, 28, 30));
        t.setTextSize(19);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = dp(c, 14);
        t.setLayoutParams(tp);
        box.addView(t);

        ScrollView sv = new ScrollView(c);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.size(); i++) {
            final int idx = indices[i];
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable rg = new GradientDrawable();
            rg.setShape(GradientDrawable.RECTANGLE);
            rg.setColor(Color.rgb(242, 242, 247));
            rg.setCornerRadius(dp(c, 14));
            row.setBackground(rg);
            row.setPadding(dp(c, 14), dp(c, 12), dp(c, 10), dp(c, 12));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(c, 8);
            row.setLayoutParams(rp);

            TextView name = new TextView(c);
            name.setText(labels.get(i));
            name.setTextColor(Color.rgb(28, 28, 30));
            name.setTextSize(14);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            np.rightMargin = dp(c, 8);
            name.setLayoutParams(np);
            row.addView(name);

            final String personName = labels.get(i).split(" · ")[0];

            TextView view = smallBtn(c, "查看3D", Color.rgb(242, 242, 247), Color.rgb(28, 28, 30));
            view.setOnClickListener(v -> { if (holder[0] != null) holder[0].dismiss(); viewCb.onView(personName); });
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            vp.rightMargin = dp(c, 6);
            view.setLayoutParams(vp);
            row.addView(view);

            TextView del = smallBtn(c, "删除", Color.rgb(255, 235, 237), Color.rgb(215, 0, 21));
            del.setOnClickListener(v -> deleteCb.onDelete(idx));
            del.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(del);
            list.addView(row);
        }
        sv.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.bottomMargin = dp(c, 12);
        sv.setLayoutParams(slp);
        box.addView(sv);

        Button close = primaryBtn(c, "关闭", Color.rgb(28, 28, 30));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 46));
        close.setLayoutParams(cp);
        box.addView(close);

        holder[0] = new AlertDialog.Builder(c).setView(box).setCancelable(true).create();
        setup(holder[0], c);
        manageDialog = holder[0];
        close.setOnClickListener(v -> { holder[0].dismiss(); manageDialog = null; });
        holder[0].setOnDismissListener(d -> { if (manageDialog == holder[0]) manageDialog = null; });
        holder[0].show();
    }

    /** 删除确认弹窗。 */
    static void showConfirm(Context c, String title, String message, Runnable onYes) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(cardBg(c));
        box.setPadding(dp(c, 24), dp(c, 26), dp(c, 24), dp(c, 20));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Color.rgb(28, 28, 30));
        t.setTextSize(19);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = dp(c, 8);
        t.setLayoutParams(tp);
        box.addView(t);

        TextView sub = new TextView(c);
        sub.setText(message);
        sub.setTextColor(Color.rgb(110, 110, 115));
        sub.setTextSize(14);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.bottomMargin = dp(c, 20);
        sub.setLayoutParams(sp);
        box.addView(sub);

        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        Button cancel = new Button(c);
        cancel.setText("取消");
        cancel.setTextColor(Color.rgb(110, 110, 115));
        cancel.setTextSize(15);
        cancel.setAllCaps(false);
        cancel.setBackgroundColor(Color.TRANSPARENT);
        Button ok = primaryBtn(c, "删除", Color.rgb(215, 0, 21));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 44));
        cancel.setLayoutParams(bp);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 44));
        op.leftMargin = dp(c, 8);
        ok.setLayoutParams(op);
        actions.addView(cancel);
        actions.addView(ok);
        box.addView(actions);

        AlertDialog d = new AlertDialog.Builder(c).setView(box).setCancelable(true).create();
        setup(d, c);
        cancel.setOnClickListener(v -> d.dismiss());
        ok.setOnClickListener(v -> { d.dismiss(); onYes.run(); });
        d.show();
    }

    interface NameCallback { void onName(String name); }
    interface DeleteCallback { void onDelete(int index); }
    interface ViewCallback { void onView(String name); }
}
