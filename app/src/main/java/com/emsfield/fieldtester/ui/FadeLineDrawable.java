package com.emsfield.fieldtester.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 양 끝 48px 에서 투명으로 흐려지는 1px 선.
 * CSS: linear-gradient(to right, transparent, c 48px, c calc(100% - 48px), transparent)
 */
public final class FadeLineDrawable extends Drawable {
    private final int color;
    private final boolean atBottom;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public FadeLineDrawable(int color, boolean atBottom) {
        this.color = color;
        this.atBottom = atBottom;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        int w = Math.max(1, b.width());
        float ramp = Math.min(N.dp(48), w / 2f) / w;
        int clear = color & 0x00FFFFFF;
        paint.setShader(new LinearGradient(b.left, 0, b.right, 0,
                new int[]{clear, color, color, clear},
                new float[]{0f, ramp, 1f - ramp, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        int h = Math.max(1, N.dp(1));
        int top = atBottom ? b.bottom - h : b.top;
        c.drawRect(b.left, top, b.right, top + h, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
