package com.emsfield.fieldtester.ui;

import android.content.Context;
import android.graphics.Outline;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 세그먼트 컨트롤 (.seg / .seg-opt). 선택된 칸은 accent 글자 + 1px accent 안쪽 테두리.
 */
public final class Seg extends LinearLayout {
    public interface OnPick {
        void pick(int index);
    }

    private final TextView[] opts;
    private int selected = -1;
    private OnPick listener;

    /**
     * @param fill true 이면 칸을 균등 분배(부모 폭 채움)
     */
    public Seg(Context c, String[] labels, float textSp, float padHDp, float padVDp, boolean fill) {
        super(c);
        setOrientation(HORIZONTAL);
        setBackground(N.rect(0, N.DIVIDER, N.RADIUS_MD));
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), N.dp(N.RADIUS_MD));
            }
        });
        int border = Math.max(1, N.dp(1));
        setPadding(border, border, border, border);
        opts = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                View d = new View(c);
                d.setBackgroundColor(N.DIVIDER);
                addView(d, new LayoutParams(border, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            TextView t = N.text(c, labels[i], textSp, N.TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
            t.setGravity(Gravity.CENTER);
            t.setPadding(N.dp(padHDp), N.dp(padVDp), N.dp(padHDp), N.dp(padVDp));
            t.setSingleLine(true);
            final int idx = i;
            t.setOnClickListener(v -> {
                setSelected(idx);
                if (listener != null) listener.pick(idx);
            });
            opts[i] = t;
            addView(t, fill ? new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    : new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    public Seg onPick(OnPick l) {
        listener = l;
        return this;
    }

    public void setSelected(int index) {
        if (index == selected) return;
        selected = index;
        for (int i = 0; i < opts.length; i++) {
            boolean on = i == index;
            opts[i].setTextColor(on ? N.ACCENT : N.TEXT);
            opts[i].setBackground(on ? N.rect(0, N.ACCENT, 0) : pressable());
        }
    }

    private static android.graphics.drawable.Drawable pressable() {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, N.rect(N.TEXT_7, 0, 0));
        s.addState(new int[0], N.rect(0, 0, 0));
        return s;
    }

    public int selected() {
        return selected;
    }
}
