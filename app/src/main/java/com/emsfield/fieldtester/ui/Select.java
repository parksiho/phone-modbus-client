package com.emsfield.fieldtester.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListPopupWindow;
import android.widget.TextView;

/**
 * select.input: 입력창과 같은 모양 + 오른쪽 셰브론, 누르면 어두운 목록 팝업.
 */
public final class Select extends TextView {
    public interface OnChange {
        void changed(int index);
    }

    private String[] labels;
    private int selected;
    private OnChange listener;

    public Select(Context c, String[] labels, int selected) {
        super(c);
        this.labels = labels;
        setTypeface(N.regular());
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        setTextColor(N.TEXT);
        setIncludeFontPadding(false);
        setSingleLine(true);
        setEllipsize(android.text.TextUtils.TruncateAt.END);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinHeight(N.dp(36));
        setBackground(N.inputBackground());
        setPadding(N.dp(10), N.dp(6), N.dp(28), N.dp(6));
        setFocusable(true);
        setClickable(true);
        setOnClickListener(v -> open());
        select(selected);
    }

    public Select onChange(OnChange l) {
        listener = l;
        return this;
    }

    public void setLabels(String[] labels) {
        this.labels = labels;
        select(Math.min(selected, labels.length - 1));
    }

    public void select(int index) {
        selected = Math.max(0, index);
        setText(labels.length > 0 ? labels[selected] : "");
    }

    public int selected() {
        return selected;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.45f);
    }

    private void open() {
        if (!isEnabled()) return;
        ListPopupWindow p = new ListPopupWindow(getContext());
        p.setAnchorView(this);
        p.setModal(true);
        p.setBackgroundDrawable(N.rect(N.SURFACE, N.NEUTRAL_600, N.RADIUS_MD));
        p.setAdapter(new ArrayAdapter<String>(getContext(), 0, labels) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = N.text(getContext(), labels[pos], 14, pos == selected ? N.ACCENT : N.TEXT);
                t.setPadding(N.dp(12), N.dp(11), N.dp(12), N.dp(11));
                return t;
            }
        });
        p.setOnItemClickListener((parent, view, pos, id) -> {
            p.dismiss();
            if (pos != selected) {
                select(pos);
                if (listener != null) listener.changed(pos);
            }
        });
        p.show();
    }

    private final Paint chevron = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        // CSS: 5px 대각 그라데이션 두 개로 만든 작은 ▾ (neutral-500)
        chevron.setColor(N.NEUTRAL_500);
        float cx = getWidth() - N.dp(12.5f), cy = getHeight() / 2f, s = N.dp(2.5f);
        path.reset();
        path.moveTo(cx - s * 1.6f, cy - s * 0.8f);
        path.lineTo(cx + s * 1.6f, cy - s * 0.8f);
        path.lineTo(cx, cy + s * 0.9f);
        path.close();
        c.drawPath(path, chevron);
    }
}
