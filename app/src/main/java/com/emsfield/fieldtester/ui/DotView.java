package com.emsfield.fieldtester.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** 섹션 상태 점: 7px 원 + 같은 색 8px 글로우 (box-shadow: 0 0 8px color) */
public final class DotView extends View {
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = N.NEUTRAL_700;

    public DotView(Context c) {
        super(c);
    }

    public void setColor(int color) {
        if (this.color == color) return;
        this.color = color;
        glow.setShader(null);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = N.dp(7) + 2 * N.dp(8);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = N.dp(3.5f);
        float gr = r + N.dp(8);
        if (glow.getShader() == null) {
            glow.setShader(new RadialGradient(cx, cy, gr,
                    new int[]{N.alpha(color, 0.55f), N.alpha(color, 0.18f), color & 0x00FFFFFF},
                    new float[]{r / gr, 0.6f, 1f}, Shader.TileMode.CLAMP));
        }
        boolean idle = color == N.NEUTRAL_700;
        if (!idle) c.drawCircle(cx, cy, gr, glow);
        dot.setColor(color);
        c.drawCircle(cx, cy, r, dot);
    }
}
