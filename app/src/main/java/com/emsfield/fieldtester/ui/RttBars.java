package com.emsfield.fieldtester.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Ping RTT 막대 그래프 (최근 36개). 높이 64dp, 막대 최대 폭 10dp, 간격 2dp,
 * 실패는 꽉 찬 높이의 흐린 오류색, 아래쪽에 페이드 기준선.
 */
public final class RttBars extends View {
    private double[] ms = new double[0];
    private boolean[] ok = new boolean[0];
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final FadeLineDrawable base = new FadeLineDrawable(N.DIVIDER, true);
    private final RectF rect = new RectF();

    public RttBars(Context c) {
        super(c);
    }

    public void setData(double[] ms, boolean[] ok) {
        this.ms = ms;
        this.ok = ok;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), N.dp(64));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        base.setBounds(0, 0, w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        base.draw(c);
        int n = ms.length;
        if (n == 0) return;
        double max = 3;
        for (int i = 0; i < n; i++) if (ok[i]) max = Math.max(max, ms[i]);
        float gap = N.dp(2);
        float bw = Math.min(N.dp(10), (getWidth() - gap * (n - 1)) / n);
        float bottom = getHeight() - N.dp(1);
        float full = N.dp(60);
        float r = N.dp(2);
        for (int i = 0; i < n; i++) {
            float h = ok[i] ? Math.max(N.dp(4), (float) (ms[i] / max * full)) : full;
            bar.setColor(ok[i] ? N.ACCENT : N.alpha(N.ERR, 0.35f));
            float x = i * (bw + gap);
            rect.set(x, bottom - h, x + bw, bottom + r);
            c.save();
            c.clipRect(x, bottom - h, x + bw, bottom);
            c.drawRoundRect(rect, r, r, bar);
            c.restore();
        }
    }
}
