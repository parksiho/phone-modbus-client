package com.emsfield.fieldtester;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.emsfield.fieldtester.MainActivity.Ad;
import com.emsfield.fieldtester.MainActivity.IpSt;
import com.emsfield.fieldtester.MainActivity.PingRes;
import com.emsfield.fieldtester.ui.N;
import com.emsfield.fieldtester.ui.RttBars;
import com.emsfield.fieldtester.ui.Seg;

import java.util.List;
import java.util.Locale;

/** Ping 테스트 화면: ICMP 또는 TCP(IP:포트) 연결 확인, 통계 카드, RTT 막대, 응답 라인 */
final class PingScreen {
    private static final int[] COUNTS = {4, 10, 0};
    private static final int[] IVS = {200, 500, 1000};

    private final MainActivity a;
    private final Context c;

    private EditText sizeIn;
    private View portField;
    private TextView route, minMax, empty;
    private N.Button startBtn;
    private final TextView[] statVals = new TextView[4];
    private RttBars bars;
    private LinearLayout lines;
    private int shown = -1;

    PingScreen(MainActivity a) {
        this.a = a;
        this.c = a;
    }

    private String t(String k) {
        return a.t(k);
    }

    View build() {
        shown = -1;
        LinearLayout b = N.col(c, 12);

        EditText target = N.ipInput(c, a.pingTarget);
        target.addTextChangedListener(new MainActivity.Watch(s -> {
            a.pingTarget = s;
            refreshRoute();
        }));
        sizeIn = N.input(c, a.pingSize, true);
        sizeIn.addTextChangedListener(new MainActivity.Watch(s -> {
            a.pingSize = s;
            refreshRoute();
        }));
        b.addView(N.grid(c, 8, new float[]{1f, 0}, new float[]{0, 76},
                N.field(c, t("pTarget"), target), N.field(c, t("pSize"), sizeIn)));

        // 포트 포함 체크 + 포트 입력
        LinearLayout check = N.row(c, 10);
        check.setMinimumHeight(N.dp(44));
        CheckBox cb = new CheckBox(c);
        cb.setChecked(a.pingUsePort);
        cb.setButtonTintList(N.tint(N.ACCENT));
        cb.setPadding(0, 0, 0, 0);
        cb.setMinWidth(0);
        cb.setMinHeight(0);
        check.addView(cb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout labels = N.col(c, 2);
        labels.addView(N.text(c, t("pUsePort"), 14, N.TEXT));
        labels.addView(N.text(c, t("pUsePortSub"), 11, N.NEUTRAL_500));
        check.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        check.setOnClickListener(v -> cb.toggle());
        cb.setOnCheckedChangeListener((v, on) -> {
            a.pingUsePort = on;
            if (on && a.pingPort.isEmpty()) a.pingPort = a.devPort;
            a.render();
        });
        EditText portIn = N.input(c, a.pingPort, true);
        portIn.addTextChangedListener(new MainActivity.Watch(s -> {
            a.pingPort = s;
            refreshRoute();
        }));
        portField = N.field(c, t("port"), portIn);
        b.addView(N.grid(c, 8, new float[]{1f, 0}, new float[]{0, 76}, check, portField));

        // 횟수 / 간격
        Seg counts = new Seg(c, new String[]{"4", "10", "∞"}, 13, 0, 7, true);
        counts.setSelected(indexOf(COUNTS, a.pingCount));
        counts.onPick(i -> a.pingCount = COUNTS[i]);
        Seg ivs = new Seg(c, new String[]{"0.2s", "0.5s", "1s"}, 13, 0, 7, true);
        ivs.setSelected(indexOf(IVS, a.pingIv));
        ivs.onPick(i -> a.pingIv = IVS[i]);
        b.addView(N.equal(c, 8, N.field(c, t("pCount"), counts), N.field(c, t("pInterval"), ivs)));

        LinearLayout routeRow = N.row(c, 6);
        routeRow.addView(N.icon(c, N.I_ARROW_RIGHT, 11, N.NEUTRAL_500));
        route = N.monoText(c, "", 11, N.NEUTRAL_500);
        routeRow.addView(route, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        b.addView(routeRow);

        startBtn = N.button(c, N.Btn.PRIMARY, N.I_PLAY, t("pStart"))
                .onClick(v -> {
                    if (a.pingRunning) a.pingStop();
                    else a.pingStart();
                });
        b.addView(startBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, N.dp(44)));

        // 통계 카드 4개
        String[] keys = {"pSent", "pRecv", "pLoss", "pAvg"};
        View[] cards = new View[4];
        for (int i = 0; i < 4; i++) {
            LinearLayout card = N.card(c);
            card.addView(N.text(c, t(keys[i]), 10, N.NEUTRAL_500));
            statVals[i] = N.tnum(N.mediumText(c, "0", 17, N.TEXT));
            card.addView(statVals[i]);
            cards[i] = card;
        }
        b.addView(N.equal(c, 6, cards));

        // RTT
        LinearLayout rtt = N.col(c, 6);
        LinearLayout rttHead = new LinearLayout(c);
        rttHead.addView(N.monoText(c, "RTT", 11, N.NEUTRAL_500), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        minMax = N.monoText(c, "— ms", 11, N.NEUTRAL_500);
        minMax.setGravity(Gravity.END);
        rttHead.addView(minMax);
        rtt.addView(rttHead);
        bars = new RttBars(c);
        rtt.addView(bars);
        b.addView(rtt);

        lines = new LinearLayout(c);
        lines.setOrientation(LinearLayout.VERTICAL);
        b.addView(lines);
        empty = N.text(c, t("pEmpty"), 12, N.NEUTRAL_600);
        b.addView(empty);
        return b;
    }

    private void refreshRoute() {
        if (route == null) return;
        String src = a.ipState == IpSt.OK ? a.ip.trim() : "0.0.0.0";
        String tgt = a.pingTarget.trim();
        if (a.adapter != Ad.UP) route.setText(t("adNone"));
        else if (a.pingUsePort) route.setText("TCP · USB " + src + " → " + tgt + ":" + a.pingPort.trim());
        else route.setText("ICMP · USB " + src + " → " + tgt + " · " + MainActivity.parseIntOr(a.pingSize, 56) + " B");
    }

    void refresh() {
        if (route == null) return;
        sizeIn.setEnabled(!a.pingUsePort);
        sizeIn.setAlpha(a.pingUsePort ? 0.45f : 1f);
        portField.setVisibility(a.pingUsePort ? View.VISIBLE : View.INVISIBLE);
        refreshRoute();
        startBtn.setText(a.pingRunning ? t("pStop") : t("pStart"));
        startBtn.setIcon(a.pingRunning ? N.I_STOP : N.I_PLAY);

        if (shown == a.pingVersion) return;
        shown = a.pingVersion;
        List<PingRes> res = a.pingRes;
        int ok = 0;
        double sum = 0, min = Double.MAX_VALUE, max = 0;
        for (PingRes r : res) {
            if (!r.ok) continue;
            ok++;
            sum += r.ms;
            min = Math.min(min, r.ms);
            max = Math.max(max, r.ms);
        }
        double avg = ok > 0 ? sum / ok : 0;
        double var = 0;
        for (PingRes r : res) if (r.ok) var += (r.ms - avg) * (r.ms - avg);
        double mdev = ok > 0 ? Math.sqrt(var / ok) : 0;
        int loss = res.isEmpty() ? 0 : (int) Math.round((1 - ok / (double) res.size()) * 100);

        statVals[0].setText(String.valueOf(res.size()));
        statVals[1].setText(String.valueOf(ok));
        statVals[2].setText(loss + "%");
        statVals[2].setTextColor(loss > 0 ? N.ERR : N.TEXT);
        statVals[3].setText(ok > 0 ? String.format(Locale.US, "%.2f", avg) : "—");
        statVals[3].setTextColor(N.ACCENT_300);
        minMax.setText(ok > 0 ? String.format(Locale.US, "min %.2f · max %.2f · mdev %.2f ms", min, max, mdev) : "— ms");

        int from = Math.max(0, res.size() - 36);
        double[] ms = new double[res.size() - from];
        boolean[] oks = new boolean[ms.length];
        for (int i = from; i < res.size(); i++) {
            ms[i - from] = res.get(i).ms;
            oks[i - from] = res.get(i).ok;
        }
        bars.setData(ms, oks);

        lines.removeAllViews();
        String tgt = a.pingTarget.trim();
        int lo = Math.max(0, res.size() - 60);
        for (int i = res.size() - 1; i >= lo; i--) {
            PingRes r = res.get(i);
            String text;
            if (r.tcp) {
                text = r.ok ? String.format(Locale.US, "Connected to %s:%d seq=%d time=%.2f ms", tgt, r.port, r.seq, r.ms)
                        : r.refused ? String.format(Locale.US, "%s · %s:%d seq=%d (RST %.2f ms)", t("pRefused"), tgt, r.port, r.seq, r.ms)
                        : String.format(Locale.US, "%s · %s:%d seq=%d", t("pConnTimeout"), tgt, r.port, r.seq);
            } else {
                text = r.ok ? String.format(Locale.US, "%d bytes from %s: icmp_seq=%d ttl=%d time=%.2f ms", r.size + 8, tgt, r.seq, r.ttl, r.ms)
                        : String.format(Locale.US, "%s · icmp_seq=%d", t("pTimeout"), r.seq);
            }
            TextView line = N.monoText(c, text, 10.5f, r.ok ? N.NEUTRAL_300 : N.ERR);
            line.setPadding(0, N.dp(4), 0, N.dp(4));
            line.setBackground(N.rowRule(N.TEXT_7));
            lines.addView(line);
        }
        empty.setVisibility(res.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }
}
