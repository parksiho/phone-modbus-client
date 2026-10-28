package com.emsfield.fieldtester;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.emsfield.fieldtester.MainActivity.Ad;
import com.emsfield.fieldtester.MainActivity.Check;
import com.emsfield.fieldtester.MainActivity.IpSt;
import com.emsfield.fieldtester.MainActivity.Item;
import com.emsfield.fieldtester.MainActivity.LogEntry;
import com.emsfield.fieldtester.MainActivity.Result;
import com.emsfield.fieldtester.ui.DotView;
import com.emsfield.fieldtester.ui.N;
import com.emsfield.fieldtester.ui.Seg;
import com.emsfield.fieldtester.ui.Select;
import com.emsfield.modbus.RegisterDecoder;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Modbus 클라이언트 화면: 어댑터 / 앱 IP / 대상 장비 / 요청 / 응답 / 로그 (접이식 섹션) */
final class MainScreen {
    private static final String[] SECS = {"adapter", "ip", "target", "req", "res", "log"};
    private static final String[] TYPES = {"UINT16", "INT16", "UINT32", "INT32", "FLOAT32"};
    private static final String[] ORDERS = {"ABCD", "CDAB", "BADC", "DCBA"};
    private static final int[] INTERVALS = {500, 1000, 2000, 5000};

    private final MainActivity a;
    private final Context c;

    private final Map<String, DotView> dots = new HashMap<>();
    private final Map<String, TextView> sums = new HashMap<>();
    private final Map<String, TextView> carets = new HashMap<>();
    private final Map<String, View> bodies = new HashMap<>();

    // adapter
    private TextView phoneNet, chipLine;
    private N.Button usbBtn, connectBtn, releaseBtn;
    // ip
    private N.Button applyBtn;
    private LinearLayout ipMsgRow;
    private TextView ipMsgIcon, ipMsgText;
    // target
    private final Map<String, N.CheckButton> checkBtns = new HashMap<>();
    // req
    private Select fnSelect, typeSelect, orderSelect;
    private View countField, writeField, typeRow;
    private TextView countLabel, writeLabel;
    private EditText wvalInput;
    private N.Button pollBtn;
    private Seg intervalSeg;
    // res
    private LinearLayout resBox, rowsBox;
    private TextView noRes, noResHint, resMeta, rowHint, rawHead, valHead;
    private Seg fmtSeg;
    private View pollingTag;
    // log
    private LinearLayout logBox;
    private TextView logEmpty;
    private Seg logSeg;

    private int shownResult = -1, shownLog = -1;
    private String shownFmt, shownLang, shownFilter;

    MainScreen(MainActivity a) {
        this.a = a;
        this.c = a;
    }

    private String t(String k) {
        return a.t(k);
    }

    View build() {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        section(col, "adapter", "secAdapter", buildAdapter(), false);
        section(col, "ip", "secIp", buildIp(), true);
        section(col, "target", "secTarget", buildTarget(), true);
        section(col, "req", "secReq", buildReq(), true);
        section(col, "res", "secRes", buildRes(), true);
        section(col, "log", "secLog", buildLog(), true);
        shownResult = shownLog = -1;
        return col;
    }

    // ------------------------------------------------------------------ section frame
    private void section(LinearLayout col, String key, String titleKey, View body, boolean tnum) {
        if (!key.equals("adapter")) col.addView(N.fadeRule(c, N.DIVIDER));
        LinearLayout head = N.row(c, 8);
        head.setClipChildren(false); // 상태 점 글로우가 잘리지 않게
        head.setPadding(0, N.dp(10), 0, N.dp(8));
        head.setOnClickListener(v -> a.toggleSection(key));
        DotView dot = new DotView(c);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.leftMargin = -N.dp(8);
        dl.rightMargin = -N.dp(8);
        head.addView(dot, dl);
        TextView title = N.mediumText(c, t(titleKey), 13, N.NEUTRAL_200);
        title.setSingleLine(true);
        head.addView(title);
        TextView sum = N.ellipsize(N.text(c, "", 12, N.NEUTRAL_500));
        if (tnum) N.tnum(sum);
        sum.setGravity(Gravity.END);
        head.addView(sum, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView caret = N.icon(c, N.I_CARET_DOWN, 14, N.NEUTRAL_600);
        head.addView(caret);
        col.addView(head);
        body.setPadding(0, 0, 0, key.equals("log") ? 0 : N.dp(12));
        col.addView(body);
        dots.put(key, dot);
        sums.put(key, sum);
        carets.put(key, caret);
        bodies.put(key, body);
    }

    private EditText bound(EditText e, MainActivity.Watch.On on) {
        e.addTextChangedListener(new MainActivity.Watch(on));
        return e;
    }

    // ------------------------------------------------------------------ adapter
    private View buildAdapter() {
        LinearLayout b = N.col(c, 8);
        LinearLayout line = N.row(c, 8);
        line.addView(N.icon(c, N.I_DEVICE_MOBILE, 14, N.NEUTRAL_400));
        phoneNet = N.text(c, "", 12, N.NEUTRAL_400);
        line.addView(phoneNet, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chipLine = N.monoText(c, "", 11, N.NEUTRAL_500);
        line.addView(chipLine);
        b.addView(line);
        usbBtn = N.button(c, N.Btn.SECONDARY, N.I_USB, t("usbInfo")).onClick(v -> a.usbInfo());
        connectBtn = N.button(c, N.Btn.PRIMARY, null, t("connect")).onClick(v -> a.connectAdapter());
        releaseBtn = N.button(c, N.Btn.SECONDARY, null, t("disconnect")).onClick(v -> a.disconnect(true));
        b.addView(fixedH(N.equal(c, 8, usbBtn, connectBtn, releaseBtn), 40));
        return b;
    }

    // ------------------------------------------------------------------ app IP
    private View buildIp() {
        LinearLayout b = N.col(c, 8);
        b.addView(N.text(c, t("ipNote"), 11, N.NEUTRAL_500));
        EditText ip = bound(N.ipInput(c, a.ip), s -> {
            a.ip = s;
            a.ipEdited();
        });
        EditText pf = bound(N.input(c, a.prefix, true), s -> {
            a.prefix = s;
            a.ipEdited();
        });
        EditText gw = bound(N.ipInput(c, a.gw), s -> {
            a.gw = s;
            a.ipEdited();
        });
        gw.setHint(t("optional"));
        b.addView(N.grid(c, 8, new float[]{1.3f, 0, 1f}, new float[]{0, 52, 0},
                N.field(c, t("ipAddr"), ip), N.field(c, "Prefix", pf), N.field(c, t("gw"), gw)));
        applyBtn = N.button(c, N.Btn.PRIMARY, N.I_SHIELD_CHECK, t("apply")).onClick(v -> a.applyIp());
        b.addView(applyBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, N.dp(40)));
        ipMsgRow = N.row(c, 6);
        ipMsgIcon = N.icon(c, N.I_CHECK_CIRCLE, 13, N.ACCENT_300);
        ipMsgText = N.text(c, "", 12, N.ACCENT_300);
        ipMsgRow.addView(ipMsgIcon);
        ipMsgRow.addView(ipMsgText);
        b.addView(ipMsgRow);
        return b;
    }

    // ------------------------------------------------------------------ target
    private View buildTarget() {
        LinearLayout b = N.col(c, 8);
        EditText ip = bound(N.ipInput(c, a.devIp), s -> {
            a.devIp = s;
            a.render();
        });
        EditText port = bound(N.input(c, a.devPort, true), s -> {
            a.devPort = s;
            a.render();
        });
        EditText unit = bound(N.input(c, a.unit, true), s -> {
            a.unit = s;
            a.render();
        });
        b.addView(N.grid(c, 8, new float[]{1f, 0, 0}, new float[]{0, 64, 56},
                N.field(c, t("ipAddr"), ip), N.field(c, t("port"), port), N.field(c, "Unit ID", unit)));
        String[][] cks = {{"arp", "ARP", N.I_GRAPH}, {"ping", "Ping", N.I_PULSE}, {"port", t("portChk"), N.I_DOOR_OPEN}};
        View[] btns = new View[3];
        for (int i = 0; i < 3; i++) {
            String k = cks[i][0];
            N.CheckButton cb = new N.CheckButton(c, cks[i][2], cks[i][1]);
            cb.setOnClickListener(v -> a.runCheck(k));
            checkBtns.put(k, cb);
            btns[i] = cb;
        }
        b.addView(fixedH(N.equal(c, 8, btns), 54));
        return b;
    }

    // ------------------------------------------------------------------ request
    private View buildReq() {
        LinearLayout b = N.col(c, 8);
        String[] fnLabels = new String[I18n.FN.length];
        int fnIdx = 0;
        for (int i = 0; i < I18n.FN.length; i++) {
            fnLabels[i] = "en".equals(a.lang) ? I18n.FN[i][2] : I18n.FN[i][1];
            if (I18n.FN[i][0].equals(a.fn)) fnIdx = i;
        }
        fnSelect = new Select(c, fnLabels, fnIdx).onChange(i -> {
            a.onFnChanged(I18n.FN[i][0]);
            wvalInput.setText(a.wval);
        });
        b.addView(N.field(c, "Function", fnSelect));

        EditText startIn = bound(N.input(c, a.start, true), s -> {
            a.start = s;
            a.render();
        });
        EditText countIn = bound(N.input(c, a.count, true), s -> {
            a.count = s;
            a.render();
        });
        wvalInput = bound(N.input(c, a.wval, false), s -> a.wval = s);
        LinearLayout cf = N.field(c, t("count"), countIn);
        countLabel = (TextView) cf.getChildAt(0);
        LinearLayout wf = N.field(c, t("value"), wvalInput);
        writeLabel = (TextView) wf.getChildAt(0);
        countField = cf;
        writeField = wf;
        b.addView(N.equal(c, 8, N.field(c, t("start"), startIn), stack(cf, wf)));

        typeSelect = new Select(c, TYPES, indexOf(TYPES, a.type)).onChange(i -> {
            a.type = TYPES[i];
            a.render();
        });
        orderSelect = new Select(c, ORDERS, indexOf(ORDERS, a.order)).onChange(i -> {
            a.order = ORDERS[i];
            a.render();
        });
        typeRow = N.equal(c, 8, N.field(c, t("type"), typeSelect), N.field(c, t("order"), orderSelect));
        b.addView(typeRow);

        N.Button run = N.button(c, N.Btn.PRIMARY, N.I_PAPER_PLANE, t("run")).onClick(v -> a.execute());
        pollBtn = N.button(c, N.Btn.SECONDARY, N.I_REPEAT, t("poll")).onClick(v -> a.togglePoll());
        b.addView(fixedH(N.equal(c, 8, run, pollBtn), 42));

        LinearLayout ivRow = N.row(c, 8);
        ivRow.addView(N.text(c, t("interval"), 12, N.NEUTRAL_500), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        intervalSeg = new Seg(c, new String[]{"0.5s", "1s", "2s", "5s"}, 12, 10, 4, false);
        intervalSeg.onPick(i -> a.setInterval(INTERVALS[i]));
        ivRow.addView(intervalSeg);
        b.addView(ivRow);
        return b;
    }

    /** 개수 / 값 필드를 같은 칸에 두고 하나만 보이게 */
    private View stack(View x, View y) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(x);
        l.addView(y);
        return l;
    }

    // ------------------------------------------------------------------ response
    private View buildRes() {
        LinearLayout b = N.col(c, 6);
        resBox = N.col(c, 6);
        LinearLayout top = N.row(c, 8);
        fmtSeg = new Seg(c, new String[]{"HEX", "DEC", "BIN"}, 12, 12, 4, false);
        fmtSeg.onPick(i -> {
            a.fmt = new String[]{"HEX", "DEC", "BIN"}[i];
            a.render();
        });
        top.addView(fmtSeg);
        top.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout tag = N.row(c, 5);
        tag.setBackground(N.rect(N.ACCENT_800, 0, N.RADIUS_MD * 0.75f));
        tag.setPadding(N.dp(10), N.dp(3), N.dp(10), N.dp(3));
        View tdot = new View(c);
        tdot.setBackground(N.rect(N.ACCENT_300, 0, 3));
        tag.addView(tdot, new LinearLayout.LayoutParams(N.dp(6), N.dp(6)));
        TextView tl = N.text(c, t("polling"), 11, N.ACCENT_100);
        tl.setLetterSpacing(0.02f);
        tag.addView(tl);
        pollingTag = tag;
        top.addView(tag);
        resMeta = N.tnum(N.text(c, "", 11, N.NEUTRAL_500));
        top.addView(resMeta);
        resBox.addView(top);

        LinearLayout th = new LinearLayout(c);
        th.setBackground(N.rowRule(N.DIVIDER));
        TextView h1 = headCell(t("addr"));
        rawHead = headCell("");
        valHead = headCell("");
        valHead.setGravity(Gravity.END);
        th.addView(h1, cell(0.8f));
        th.addView(rawHead, cell(1.5f));
        th.addView(valHead, cell(1.3f));
        LinearLayout table = new LinearLayout(c); // 헤더와 행은 붙어 있어야 하므로 gap 없는 컨테이너
        table.setOrientation(LinearLayout.VERTICAL);
        table.addView(th);
        rowsBox = new LinearLayout(c);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        table.addView(rowsBox);
        resBox.addView(table);
        rowHint = N.text(c, "", 11, N.NEUTRAL_600);
        resBox.addView(rowHint);
        b.addView(resBox);

        LinearLayout none = N.col(c, 2);
        none.setPadding(0, N.dp(12), 0, N.dp(12));
        noRes = N.text(c, t("noRes"), 13, N.NEUTRAL_500);
        noResHint = N.text(c, t("noResHint"), 12, N.NEUTRAL_600);
        none.addView(noRes);
        none.addView(noResHint);
        b.addView(none);
        return b;
    }

    private TextView headCell(String s) {
        TextView t = N.text(c, s.toUpperCase(Locale.ROOT), 11, N.alpha(N.TEXT, 0.60f));
        t.setLetterSpacing(0.08f);
        t.setPadding(N.dp(4), N.dp(6), N.dp(4), N.dp(6));
        return t;
    }

    private static LinearLayout.LayoutParams cell(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    // ------------------------------------------------------------------ log
    private View buildLog() {
        LinearLayout b = N.col(c, 8);
        LinearLayout top = N.row(c, 8);
        logSeg = new Seg(c, new String[]{t("all"), "TX/RX", t("errors")}, 12, 10, 4, true);
        logSeg.onPick(i -> {
            a.logFilter = new String[]{"all", "frames", "errors"}[i];
            a.render();
        });
        top.addView(logSeg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        N.Button clear = N.button(c, N.Btn.SECONDARY, N.I_TRASH, t("clear")).onClick(v -> a.clearLog());
        clear.label.setTextSize(12);
        clear.iconView.setTextSize(13);
        top.addView(clear, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, N.dp(30)));
        b.addView(top);
        logBox = new LinearLayout(c);
        logBox.setOrientation(LinearLayout.VERTICAL);
        b.addView(logBox);
        logEmpty = N.text(c, t("logEmpty"), 12, N.NEUTRAL_600);
        logEmpty.setPadding(0, N.dp(8), 0, N.dp(8));
        b.addView(logEmpty);
        return b;
    }

    // ================================================================== refresh
    void refresh() {
        boolean adUp = a.adapter == Ad.UP;
        boolean ipOk = a.ipState == IpSt.OK;
        for (String k : SECS) {
            boolean on = Boolean.TRUE.equals(a.open.get(k));
            bodies.get(k).setVisibility(on ? View.VISIBLE : View.GONE);
            carets.get(k).setText(on ? N.I_CARET_UP : N.I_CARET_DOWN);
        }

        // ---- dots & summaries
        boolean anyCheckErr = false, anyCheckDone = false;
        for (Check ck : a.checks.values()) {
            if (ck.err) anyCheckErr = true;
            else if (!ck.pending) anyCheckDone = true;
        }
        Result r = a.result;
        int idle = N.NEUTRAL_700;
        dots.get("adapter").setColor(adUp ? N.ACCENT : a.adapter == Ad.BUSY ? N.ACCENT_700 : idle);
        dots.get("ip").setColor(ipOk ? N.ACCENT : (a.ipState == IpSt.CONFLICT || a.ipState == IpSt.BAD) ? N.ERR : idle);
        dots.get("target").setColor(anyCheckErr ? N.ERR : anyCheckDone ? N.ACCENT : idle);
        dots.get("req").setColor(a.polling ? N.ACCENT : idle);
        dots.get("res").setColor(a.lastErr ? N.ERR : r != null ? N.ACCENT : idle);
        dots.get("log").setColor(a.err > 0 ? N.ERR : idle);

        int st0 = MainActivity.parseIntOr(a.start, 0);
        int cnt = Math.max(1, MainActivity.parseIntOr(a.count, 1));
        int W = MainActivity.w(a.type);
        boolean reg = MainActivity.isReg(a.fn);
        String fs = MainActivity.fnShort(a.fn);
        sums.get("adapter").setText(adUp ? a.adapterSummary : a.adapter == Ad.BUSY ? t("connecting") : t("adNone"));
        sums.get("ip").setText(ipOk ? a.ip.trim() + "/" + a.prefix.trim() : a.ipState == IpSt.BUSY ? t("checking") : t("ipNotSet"));
        sums.get("target").setText(a.devIp.trim() + ":" + a.devPort.trim() + " · U" + a.unit.trim());
        sums.get("req").setText(MainActivity.isRead(a.fn)
                ? fs + " · " + st0 + "–" + (st0 + cnt * (reg ? W : 1) - 1) + (reg ? " · " + a.type : "")
                : fs + " · @" + st0);
        String avg = a.rx > 0 ? String.format(Locale.US, "%.1f", a.msSum / a.rx) : "—";
        sums.get("res").setText("TX " + a.tx + " · RX " + a.rx + " · ERR " + a.err + " · " + avg + " ms");
        sums.get("log").setText(a.log.size() + " " + t("entries"));

        // ---- adapter
        phoneNet.setText(a.phoneNetLine());
        chipLine.setText(adUp ? a.adapterChip : "");
        connectBtn.setText(a.adapter == Ad.BUSY ? t("connecting") : adUp ? t("adUp") : t("connect"));
        connectBtn.setEnabled(a.adapter == Ad.NONE);
        releaseBtn.setEnabled(adUp);

        // ---- ip
        applyBtn.setEnabled(a.ipState != IpSt.BUSY);
        applyBtn.setText(a.ipState == IpSt.BUSY ? t("checking") : t("apply"));
        String msg = a.ipState == IpSt.OK ? t("ipOk") : a.ipState == IpSt.CONFLICT ? t("ipConflict")
                : a.ipState == IpSt.BAD ? t("ipBad") : null;
        ipMsgRow.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        if (msg != null) {
            int col = ipOk ? N.ACCENT_300 : N.ERR;
            ipMsgIcon.setText(ipOk ? N.I_CHECK_CIRCLE : N.I_WARNING);
            ipMsgIcon.setTextColor(col);
            ipMsgText.setText(msg);
            ipMsgText.setTextColor(col);
        }

        // ---- target checks
        for (Map.Entry<String, N.CheckButton> e : checkBtns.entrySet()) {
            Check ck = a.checks.get(e.getKey());
            TextView res = e.getValue().result;
            if (ck == null) {
                res.setText(t("notChecked"));
                res.setTextColor(N.NEUTRAL_600);
            } else {
                res.setText(ck.key != null ? t(ck.key) : ck.text);
                res.setTextColor(ck.err ? N.ERR : N.ACCENT_300);
            }
        }

        // ---- request
        boolean writeFn = !MainActivity.isRead(a.fn);
        countField.setVisibility(writeFn ? View.GONE : View.VISIBLE);
        writeField.setVisibility(writeFn ? View.VISIBLE : View.GONE);
        countLabel.setText(reg && W == 2 ? t("countWords") : t("count"));
        boolean multi = a.fn.equals("0F") || a.fn.equals("10");
        writeLabel.setText(multi ? t("values") : t("value"));
        wvalInput.setHint(a.fn.equals("05") ? "ON / OFF" : a.fn.equals("0F") ? "1,0,1,1" : "100, 200");
        typeRow.setVisibility(reg && !a.fn.equals("06") ? View.VISIBLE : View.GONE);
        pollBtn.setEnabled(!writeFn);
        pollBtn.setText(a.polling ? t("stop") : t("poll"));
        pollBtn.setIcon(a.polling ? N.I_STOP : N.I_REPEAT);
        pollBtn.setTone(a.polling ? N.ACCENT : N.TEXT, a.polling ? N.ACCENT : N.DIVIDER);
        for (int i = 0; i < INTERVALS.length; i++) if (INTERVALS[i] == a.interval) intervalSeg.setSelected(i);

        // ---- response
        refreshResult();

        // ---- log
        refreshLog();
    }

    private void refreshResult() {
        Result r = a.result;
        resBox.setVisibility(r != null ? View.VISIBLE : View.GONE);
        ((View) noRes.getParent()).setVisibility(r == null ? View.VISIBLE : View.GONE);
        if (r == null) return;
        boolean regRes = r.fn.equals("03") || r.fn.equals("04");
        fmtSeg.setVisibility(regRes ? View.VISIBLE : View.GONE);
        fmtSeg.setSelected(a.fmt.equals("DEC") ? 1 : a.fmt.equals("BIN") ? 2 : 0);
        pollingTag.setVisibility(a.polling ? View.VISIBLE : View.GONE);
        resMeta.setText(r.at.substring(0, 8) + " · " + String.format(Locale.US, "%.1f", r.ms) + " ms");
        rawHead.setText(regRes ? "RAW " + a.fmt : "BIT");
        int W = MainActivity.w(r.type);
        valHead.setText((regRes ? r.type + (W == 2 ? " " + r.order : "") : t("state")).toUpperCase(Locale.ROOT));
        rowHint.setText(r.fn.equals("03") ? t("tapReg") : r.fn.equals("01") ? t("tapCoil") : t("readOnly"));

        if (shownResult == a.resultVersion && a.fmt.equals(shownFmt)) return;
        shownResult = a.resultVersion;
        shownFmt = a.fmt;
        rowsBox.removeAllViews();
        int refBase = r.fn.equals("01") ? 1 : r.fn.equals("02") ? 10001 : r.fn.equals("03") ? 40001 : 30001;
        boolean tappable = r.fn.equals("03") || r.fn.equals("01");
        for (Item it : r.items) {
            boolean changed = r.changed.contains(it.addr);
            LinearLayout row = new LinearLayout(c);
            row.setBackground(N.rowRule(N.TEXT_8));
            if (tappable) {
                row.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                        MainActivity.pressable(N.alpha(N.TEXT, 0.04f), 0), N.rowRule(N.TEXT_8)}));
                row.setOnClickListener(v -> a.tapRow(it));
            }
            LinearLayout addr = new LinearLayout(c);
            addr.setOrientation(LinearLayout.VERTICAL);
            addr.setPadding(N.dp(4), N.dp(7), N.dp(4), N.dp(7));
            addr.addView(N.tnum(N.text(c, String.valueOf(it.addr), 13, N.TEXT)));
            addr.addView(N.tnum(N.text(c, String.format(Locale.US, "%05d", refBase + it.addr), 10, N.NEUTRAL_600)));
            row.addView(addr, cell(0.8f));

            TextView raw;
            String val;
            int color;
            if (it.words != null) {
                StringBuilder sb = new StringBuilder();
                for (int x : it.words) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(formatRaw(x, a.fmt));
                }
                raw = N.monoText(c, sb, 11, N.NEUTRAL_400);
                val = MainActivity.fmtVal(RegisterDecoder.decode(it.words, 0, MainActivity.dt(r.type), MainActivity.bo(r.order)), r.type);
                color = changed ? N.ACCENT_300 : N.TEXT;
            } else {
                raw = N.monoText(c, String.valueOf(it.bit), 11, N.NEUTRAL_400);
                val = it.bit == 1 ? "ON" : "OFF";
                color = changed ? N.ACCENT_300 : it.bit == 1 ? N.ACCENT_400 : N.NEUTRAL_500;
            }
            raw.setLineSpacing(0, 1.5f);
            raw.setPadding(N.dp(4), N.dp(7), N.dp(4), N.dp(7));
            row.addView(raw, cell(1.5f));
            TextView v = N.monoText(c, val, 13, color);
            v.setGravity(Gravity.END);
            v.setPadding(N.dp(4), N.dp(7), N.dp(4), N.dp(7));
            row.addView(v, cell(1.3f));
            rowsBox.addView(row);
        }
    }

    static String formatRaw(int x, String fmt) {
        if (fmt.equals("DEC")) return String.valueOf(x);
        if (fmt.equals("BIN")) {
            String b = String.format(Locale.US, "%16s", Integer.toBinaryString(x & 0xFFFF)).replace(' ', '0');
            return b.substring(0, 4) + " " + b.substring(4, 8) + " " + b.substring(8, 12) + " " + b.substring(12);
        }
        return String.format(Locale.US, "0x%04X", x & 0xFFFF);
    }

    private void refreshLog() {
        logSeg.setSelected(a.logFilter.equals("frames") ? 1 : a.logFilter.equals("errors") ? 2 : 0);
        if (shownLog == a.logVersion && a.logFilter.equals(shownFilter) && a.lang.equals(shownLang)) return;
        shownLog = a.logVersion;
        shownFilter = a.logFilter;
        shownLang = a.lang;
        logBox.removeAllViews();
        int shown = 0;
        boolean en = "en".equals(a.lang);
        for (LogEntry l : a.log) {
            boolean keep = a.logFilter.equals("all")
                    || (a.logFilter.equals("frames") ? (l.dir.equals("TX") || l.dir.equals("RX")) : l.dir.equals("ERR"));
            if (!keep) continue;
            if (++shown > 60) break;
            LinearLayout row = new LinearLayout(c);
            row.setPadding(0, N.dp(4), 0, N.dp(4));
            row.setBackground(N.rowRule(N.TEXT_7));
            TextView time = N.monoText(c, l.t, 10.5f, N.NEUTRAL_600);
            row.addView(time, new LinearLayout.LayoutParams(N.dp(70), ViewGroup.LayoutParams.WRAP_CONTENT));
            int dc = l.dir.equals("TX") ? N.ACCENT_400 : l.dir.equals("RX") ? N.NEUTRAL_300 : l.dir.equals("ERR") ? N.ERR : N.NEUTRAL_600;
            TextView dir = N.monoText(c, l.dir, 10.5f, dc);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(N.dp(30), ViewGroup.LayoutParams.WRAP_CONTENT);
            dl.leftMargin = N.dp(6);
            row.addView(dir, dl);
            int tc = l.dir.equals("ERR") ? N.ERR : l.dir.equals("INFO") ? N.NEUTRAL_400 : N.NEUTRAL_200;
            TextView text = N.monoText(c, en ? l.en : l.ko, 10.5f, tc);
            text.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tl.leftMargin = N.dp(6);
            row.addView(text, tl);
            logBox.addView(row);
        }
        logEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ helpers
    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    private static LinearLayout fixedH(LinearLayout row, float hDp) {
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < row.getChildCount(); i++) {
            View ch = row.getChildAt(i);
            ch.getLayoutParams().height = N.dp(hDp);
        }
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, N.dp(hDp)));
        return row;
    }
}
