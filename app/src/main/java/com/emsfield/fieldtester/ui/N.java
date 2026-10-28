package com.emsfield.fieldtester.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Nocturne 디자인 시스템 (Claude Design 핸드오프 _ds/nocturne/styles.css) 의 안드로이드 구현.
 * 색상·간격·반경 토큰과 버튼/입력/카드/태그 등 컴포넌트 팩토리.
 */
public final class N {
    private N() {}

    // ------------------------------------------------------------------ color tokens
    public static final int BG = 0xFF161826;
    public static final int SURFACE = 0xFF232532;
    public static final int TEXT = 0xFFE9E9ED;
    public static final int ACCENT = 0xFF9184D9;
    /** --color-divider: text 16% */
    public static final int DIVIDER = alpha(TEXT, 0.16f);
    /** oklch(0.74 0.12 25) - 디자인의 오류 색 */
    public static final int ERR = 0xFFED8C84;

    public static final int NEUTRAL_100 = 0xFFF3F5FE;
    public static final int NEUTRAL_200 = 0xFFE4E7F5;
    public static final int NEUTRAL_300 = 0xFFCFD3E5;
    public static final int NEUTRAL_400 = 0xFFB2B6CA;
    public static final int NEUTRAL_500 = 0xFF9397AB;
    public static final int NEUTRAL_600 = 0xFF75798C;
    public static final int NEUTRAL_700 = 0xFF595D6C;
    public static final int NEUTRAL_800 = 0xFF3F424D;
    public static final int NEUTRAL_900 = 0xFF292B31;

    public static final int ACCENT_100 = 0xFFF5F4FF;
    public static final int ACCENT_300 = 0xFFD2CEFD;
    public static final int ACCENT_400 = 0xFFB5ABFC;
    public static final int ACCENT_700 = 0xFF5D5294;
    public static final int ACCENT_800 = 0xFF423A6A;
    public static final int ACCENT_900 = 0xFF2B2741;

    /** 버튼 hover/active 등 반투명 틴트 */
    public static final int TEXT_7 = alpha(TEXT, 0.07f);
    public static final int TEXT_8 = alpha(TEXT, 0.08f);
    public static final int TEXT_14 = alpha(TEXT, 0.14f);
    public static final int SCRIM = alpha(NEUTRAL_900, 0.70f);

    public static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }

    // ------------------------------------------------------------------ fonts
    private static Typeface inter400, inter500, icons;

    public static void init(Context c) {
        if (icons != null) return;
        try {
            inter400 = Typeface.createFromAsset(c.getAssets(), "fonts/inter_400.ttf");
            inter500 = Typeface.createFromAsset(c.getAssets(), "fonts/inter_500.ttf");
        } catch (RuntimeException e) {
            inter400 = Typeface.SANS_SERIF;
            inter500 = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        }
        try {
            icons = Typeface.createFromAsset(c.getAssets(), "fonts/phosphor.ttf");
        } catch (RuntimeException e) {
            icons = Typeface.DEFAULT;
        }
    }

    public static Typeface regular() {
        return inter400;
    }

    public static Typeface medium() {
        return inter500;
    }

    public static Typeface mono() {
        return Typeface.MONOSPACE;
    }

    // ------------------------------------------------------------------ Phosphor icons (regular)
    public static final String I_LIST = "";
    public static final String I_ARROW_RIGHT = "";
    public static final String I_STOP = "";
    public static final String I_PLAY = "";
    public static final String I_DEVICE_MOBILE = "";
    public static final String I_USB = "";
    public static final String I_SHIELD_CHECK = "";
    public static final String I_CHECK_CIRCLE = "";
    public static final String I_WARNING = "";
    public static final String I_GRAPH = "";
    public static final String I_PULSE = "";
    public static final String I_DOOR_OPEN = "";
    public static final String I_CARET_UP = "";
    public static final String I_CARET_DOWN = "";
    public static final String I_PAPER_PLANE = "";
    public static final String I_REPEAT = "";
    public static final String I_TRASH = "";
    public static final String I_PLUGS = "";

    // ------------------------------------------------------------------ metrics
    public static float density = 1f;

    public static int dp(float v) {
        return Math.round(v * density);
    }

    public static final int RADIUS_SM = 4, RADIUS_MD = 8, RADIUS_LG = 14;

    // ------------------------------------------------------------------ text
    public static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(regular());
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    public static TextView mediumText(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(medium());
        return t;
    }

    public static TextView monoText(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(mono());
        t.setLineSpacing(0, 1.45f);
        return t;
    }

    /** font-variant-numeric: tabular-nums */
    public static <T extends TextView> T tnum(T t) {
        t.setFontFeatureSettings("tnum");
        return t;
    }

    public static TextView ellipsize(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static TextView icon(Context c, String glyph, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(glyph);
        t.setTypeface(icons);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ------------------------------------------------------------------ drawables
    public static GradientDrawable rect(int fill, int strokeColor, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(Math.max(1, dp(1)), strokeColor);
        return g;
    }

    private static Drawable states(Drawable normal, Drawable pressed) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[0], normal);
        return s;
    }

    // ------------------------------------------------------------------ buttons
    public enum Btn { PRIMARY, SECONDARY, GHOST }

    /** 아이콘 + 라벨 버튼 (.btn .btn-primary / .btn-secondary / .btn-ghost) */
    public static Button button(Context c, Btn kind, String glyph, String label) {
        return new Button(c, kind, glyph, label);
    }

    public static final class Button extends LinearLayout {
        public final TextView iconView;
        public final TextView label;
        private final Btn kind;
        private int strokeOverride;

        Button(Context c, Btn kind, String glyph, String text) {
            super(c);
            this.kind = kind;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setPadding(dp(10), dp(5.6f), dp(10), dp(5.6f));
            int fg = kind == Btn.SECONDARY ? TEXT : ACCENT;
            iconView = icon(c, glyph == null ? "" : glyph, 15, fg);
            iconView.setVisibility(glyph == null ? GONE : VISIBLE);
            addView(iconView);
            label = mediumText(c, text, 14, fg);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = glyph == null ? 0 : dp(6);
            addView(label, lp);
            setClickable(true);
            setFocusable(true);
            applyBackground();
        }

        private void applyBackground() {
            int stroke = strokeOverride != 0 ? strokeOverride
                    : kind == Btn.PRIMARY ? ACCENT : kind == Btn.SECONDARY ? DIVIDER : 0;
            int pressFill = kind == Btn.SECONDARY ? TEXT_14 : alpha(ACCENT, 0.20f);
            setBackground(states(rect(0, stroke, RADIUS_MD), rect(pressFill, stroke, RADIUS_MD)));
        }

        public Button setText(String text) {
            label.setText(text);
            return this;
        }

        public Button setIcon(String glyph) {
            iconView.setText(glyph == null ? "" : glyph);
            iconView.setVisibility(glyph == null ? GONE : VISIBLE);
            ((LayoutParams) label.getLayoutParams()).leftMargin = glyph == null ? 0 : dp(6);
            return this;
        }

        /** 폴링 버튼처럼 상태에 따라 글자·테두리 색이 바뀌는 경우 */
        public void setTone(int fg, int stroke) {
            label.setTextColor(fg);
            iconView.setTextColor(fg);
            strokeOverride = stroke;
            applyBackground();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.45f);
        }

        public Button onClick(View.OnClickListener l) {
            setOnClickListener(l);
            return this;
        }
    }

    /** 세로 2줄 버튼 (대상 장비 ARP/Ping/포트 확인) */
    public static final class CheckButton extends LinearLayout {
        public final TextView result;

        public CheckButton(Context c, String glyph, String label) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            setPadding(dp(10), dp(6), dp(10), dp(6));
            setBackground(states(rect(0, DIVIDER, RADIUS_MD), rect(TEXT_14, DIVIDER, RADIUS_MD)));
            setClickable(true);
            LinearLayout top = new LinearLayout(c);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(icon(c, glyph, 14, TEXT));
            TextView l = mediumText(c, label, 13, TEXT);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(6);
            top.addView(l, lp);
            addView(top);
            result = ellipsize(monoText(c, "", 11, NEUTRAL_600));
            LayoutParams rp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(2);
            addView(result, rp);
        }
    }

    // ------------------------------------------------------------------ forms
    /** .field: 라벨 + 입력 */
    public static LinearLayout field(Context c, String label, View input) {
        LinearLayout f = new LinearLayout(c);
        f.setOrientation(LinearLayout.VERTICAL);
        TextView l = ellipsize(text(c, label, 12, alpha(TEXT, 0.70f)));
        f.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        f.addView(input, lp);
        return f;
    }

    public static Drawable inputBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rect(SURFACE, ACCENT, RADIUS_MD));
        s.addState(new int[0], rect(SURFACE, DIVIDER, RADIUS_MD));
        return s;
    }

    /** .input */
    public static EditText input(Context c, String value, boolean numeric) {
        EditText e = new EditText(c);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setTextColor(TEXT);
        e.setHintTextColor(NEUTRAL_600);
        e.setTypeface(regular());
        e.setFontFeatureSettings("tnum");
        e.setBackground(inputBackground());
        e.setPadding(dp(10), dp(6), dp(10), dp(6));
        e.setMinHeight(dp(36));
        e.setIncludeFontPadding(false);
        e.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (android.os.Build.VERSION.SDK_INT >= 29) e.setTextCursorDrawable(cursor()); // caret-color: accent
        e.setHighlightColor(alpha(ACCENT, 0.30f));
        return e;
    }

    private static Drawable cursor() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setSize(Math.max(2, dp(1.5f)), 0);
        return g;
    }

    /** IP 주소 입력 (inputmode="decimal") */
    public static EditText ipInput(Context c, String value) {
        EditText e = input(c, value, false);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789."));
        return e;
    }

    // ------------------------------------------------------------------ card / tag
    /** .card.elev-sm */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rect(SURFACE, NEUTRAL_800, RADIUS_MD));
        l.setPadding(dp(10), dp(8), dp(10), dp(8));
        return l;
    }

    /** .tag.tag-accent */
    public static TextView tag(Context c, String s) {
        TextView t = text(c, s, 11, ACCENT_100);
        t.setLetterSpacing(0.02f);
        t.setBackground(rect(ACCENT_800, 0, RADIUS_MD * 0.75f));
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    // ------------------------------------------------------------------ layout helpers
    public static LinearLayout col(Context c, float gapDp) {
        LinearLayout l = new GapLayout(c, gapDp);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c, float gapDp) {
        LinearLayout l = new GapLayout(c, gapDp);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** CSS grid 처럼: 각 칸 가중치 (0 이면 고정 dp 폭 fixedDp[i]) */
    public static LinearLayout grid(Context c, float gapDp, float[] weights, float[] fixedDp, View... cells) {
        LinearLayout r = row(c, gapDp);
        r.setGravity(Gravity.BOTTOM);
        for (int i = 0; i < cells.length; i++) {
            LinearLayout.LayoutParams lp = weights[i] > 0
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[i])
                    : new LinearLayout.LayoutParams(dp(fixedDp[i]), ViewGroup.LayoutParams.WRAP_CONTENT);
            r.addView(cells[i], lp);
        }
        return r;
    }

    /** 같은 폭 칸 */
    public static LinearLayout equal(Context c, float gapDp, View... cells) {
        float[] w = new float[cells.length];
        java.util.Arrays.fill(w, 1f);
        return grid(c, gapDp, w, new float[cells.length], cells);
    }

    public static void height(View v, float dpH) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            lp.height = dp(dpH);
            v.setLayoutParams(lp);
        } else {
            v.setMinimumHeight(dp(dpH));
        }
    }

    /** 끝이 흐려지는 1px 구분선 (Nocturne 시그니처) */
    public static View fadeRule(Context c, int color) {
        View v = new View(c);
        v.setBackground(new FadeLineDrawable(color, false));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

    /** 배경 아래쪽에 페이드 라인을 그리는 행 배경 */
    public static Drawable rowRule(int color) {
        return new FadeLineDrawable(color, true);
    }

    public static Drawable rowRuleWithFill(int fill, int line) {
        return new LayerDrawable(new Drawable[]{rect(fill, 0, 0), new FadeLineDrawable(line, true)});
    }

    /** 자식 사이 간격을 CSS gap 처럼 적용하는 LinearLayout */
    static final class GapLayout extends LinearLayout {
        private final int gap;

        GapLayout(Context c, float gapDp) {
            super(c);
            gap = dp(gapDp);
        }

        @Override
        protected void onMeasure(int w, int h) {
            applyGaps();
            super.onMeasure(w, h);
        }

        private void applyGaps() {
            boolean first = true;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                ViewGroup.LayoutParams p = ch.getLayoutParams();
                if (!(p instanceof LayoutParams)) continue;
                LayoutParams lp = (LayoutParams) p;
                int g = (first || ch.getVisibility() == GONE) ? 0 : gap;
                if (ch.getVisibility() != GONE) first = false;
                if (getOrientation() == VERTICAL) lp.topMargin = g;
                else lp.leftMargin = g;
            }
        }
    }

    public static ColorStateList tint(int color) {
        return ColorStateList.valueOf(color);
    }

    public static int parse(String hex) {
        return Color.parseColor(hex);
    }
}
