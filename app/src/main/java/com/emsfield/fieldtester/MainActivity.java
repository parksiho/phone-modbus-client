package com.emsfield.fieldtester;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.emsfield.modbus.ModbusResult;
import com.emsfield.modbus.ModbusTcpClient;
import com.emsfield.modbus.RegisterDecoder;
import com.emsfield.net.Hex;
import com.emsfield.net.Ip4;
import com.emsfield.net.IpConfig;
import com.emsfield.net.NetStack;
import com.emsfield.net.TcpConnection;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PoC 화면: USB 어댑터 점유 → 앱 내부 IP 설정 → ARP/Ping/포트 → Modbus 읽기·쓰기.
 * 폰의 인터넷 상태를 함께 표시해 장비망과 분리되어 있음을 확인한다.
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.emsfield.fieldtester.USB_PERMISSION";
    private static final String[] FC_LABELS = {
            "03 Holding 읽기", "04 Input 읽기", "01 Coil 읽기", "02 Discrete 읽기",
            "06 레지스터 1개 쓰기", "16 레지스터 여러개 쓰기", "05 Coil 1개 쓰기"};
    private static final int[] FC_CODES = {3, 4, 1, 2, 6, 16, 5};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private UsbManager usb;
    private ConnectivityManager cm;
    private SharedPreferences prefs;

    private TextView adapterStatus, internetStatus, logView;
    private ScrollView logScroll;
    private EditText ipEdit, prefixEdit, gwEdit, targetEdit, portEdit, unitEdit, addrEdit, qtyEdit;
    private Spinner fcSpinner, typeSpinner, orderSpinner;
    private Button pollButton;

    // worker 스레드 전용
    private NetStack stack;
    private UsbCdcEcmFrameIO frameIO;
    private TcpConnection tcp;
    private ModbusTcpClient modbus;
    private String tcpKey;
    private volatile boolean polling;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        prefs = getSharedPreferences("fieldtester", MODE_PRIVATE);
        setContentView(buildUi());

        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, f);

        cm.registerDefaultNetworkCallback(netCallback);
        log("앱 시작. 어댑터를 꽂고 [USB 정보] 또는 [어댑터 연결]을 누르세요.");
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) {
            log("USB 장치 연결 감지");
            showUsbInfo();
        }
    }

    @Override
    protected void onDestroy() {
        polling = false;
        unregisterReceiver(usbReceiver);
        cm.unregisterNetworkCallback(netCallback);
        worker.execute(this::closeStack);
        worker.shutdown();
        super.onDestroy();
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) {
                    log("USB 사용 권한 허용");
                    connectAdapter(d);
                } else {
                    log("USB 사용 권한 거부");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                log("USB 어댑터 분리됨");
                polling = false;
                worker.execute(MainActivity.this::closeStack);
            }
        }
    };

    /** 폰의 기본 네트워크(인터넷) 상태 표시 - 장비망과 분리되어 있는지 확인용 */
    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            String type = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "모바일 데이터"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "이더넷(⚠ OS 가 어댑터 사용 중)"
                    : "기타";
            boolean ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            setText(internetStatus, "폰 인터넷: " + type + (ok ? " · 정상" : " · 확인 중/불가"));
        }

        @Override
        public void onLost(Network network) {
            setText(internetStatus, "폰 인터넷: 연결 없음");
        }
    };

    // ------------------------------------------------------------------ actions

    private UsbDevice findAdapter() {
        UsbDevice fallback = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (UsbAdapterInfo.hasEcm(d) || UsbAdapterInfo.knownChip(d) != null) return d;
            fallback = d;
        }
        return fallback;
    }

    private void showUsbInfo() {
        if (usb.getDeviceList().isEmpty()) {
            log("연결된 USB 장치가 없습니다 (OTG 지원 여부, 어댑터 연결 확인)");
            return;
        }
        for (UsbDevice d : usb.getDeviceList().values()) log("---- USB 장치 정보 ----\n" + UsbAdapterInfo.describe(d));
    }

    private void requestAdapter() {
        UsbDevice d = findAdapter();
        if (d == null) {
            log("USB 이더넷 어댑터를 찾지 못했습니다");
            return;
        }
        if (usb.hasPermission(d)) {
            connectAdapter(d);
            return;
        }
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
        log("USB 사용 권한 요청 중...");
    }

    private void connectAdapter(UsbDevice d) {
        IpConfig cfg = readIpConfig();
        if (cfg == null) return;
        saveSettings();
        run("어댑터 연결", () -> {
            closeStack();
            frameIO = UsbCdcEcmFrameIO.open(usb, d, this::log);
            stack = new NetStack(frameIO, cfg, this::log);
            stack.start();
            setText(adapterStatus, "어댑터: " + frameIO.describe() + " · 앱 IP " + cfg);
            stack.probeAddressConflict(600);
        });
    }

    private void applyIp() {
        IpConfig cfg = readIpConfig();
        if (cfg == null) return;
        saveSettings();
        run("IP 적용", () -> {
            requireStack();
            dropTcp();
            stack.setConfig(cfg);
            setText(adapterStatus, "어댑터: " + frameIO.describe() + " · 앱 IP " + cfg);
            stack.probeAddressConflict(600);
        });
    }

    private void doArp() {
        int target = readTarget();
        if (target == 0) return;
        run("ARP", () -> {
            requireStack();
            log("장비 MAC: " + Hex.mac(stack.arp(target, 1500)));
        });
    }

    private void doPing() {
        int target = readTarget();
        if (target == 0) return;
        run("Ping", () -> {
            requireStack();
            for (int i = 1; i <= 4; i++) log("ping " + Ip4.str(target) + " #" + i + ": " + stack.ping(target, 1000));
        });
    }

    private void doPortCheck() {
        int target = readTarget();
        int port = readInt(portEdit, 502);
        if (target == 0) return;
        run("포트 확인", () -> {
            requireStack();
            long t0 = System.nanoTime();
            TcpConnection c = stack.connect(target, port, 2000);
            log(String.format(Locale.US, "포트 %d 열림 (%.1f ms)", port, (System.nanoTime() - t0) / 1e6));
            c.close();
        });
    }

    private void doModbus() {
        int target = readTarget();
        if (target == 0) return;
        saveSettings();
        int port = readInt(portEdit, 502);
        int unit = readInt(unitEdit, 1);
        int addr = readInt(addrEdit, 0);
        String qtyText = qtyEdit.getText().toString().trim();
        int fc = FC_CODES[fcSpinner.getSelectedItemPosition()];
        RegisterDecoder.DataType type = RegisterDecoder.DataType.values()[typeSpinner.getSelectedItemPosition()];
        RegisterDecoder.ByteOrder order = RegisterDecoder.ByteOrder.values()[orderSpinner.getSelectedItemPosition()];
        run("Modbus FC" + fc, () -> modbusOnce(target, port, unit, fc, addr, qtyText, type, order));
    }

    private void togglePoll() {
        if (polling) {
            polling = false;
            pollButton.setText("폴링 시작");
            return;
        }
        int target = readTarget();
        if (target == 0) return;
        int port = readInt(portEdit, 502);
        int unit = readInt(unitEdit, 1);
        int addr = readInt(addrEdit, 0);
        String qtyText = qtyEdit.getText().toString().trim();
        int fc = FC_CODES[fcSpinner.getSelectedItemPosition()];
        if (fc > 4) {
            log("폴링은 읽기 기능(01~04)만 가능합니다");
            return;
        }
        RegisterDecoder.DataType type = RegisterDecoder.DataType.values()[typeSpinner.getSelectedItemPosition()];
        RegisterDecoder.ByteOrder order = RegisterDecoder.ByteOrder.values()[orderSpinner.getSelectedItemPosition()];
        polling = true;
        pollButton.setText("폴링 중지");
        worker.submit(() -> {
            int ok = 0, fail = 0;
            double sum = 0, max = 0;
            while (polling) {
                try {
                    ModbusResult r = modbusOnce(target, port, unit, fc, addr, qtyText, type, order);
                    if (r != null && r.ok()) {
                        ok++;
                        sum += r.rttMs;
                        max = Math.max(max, r.rttMs);
                    } else {
                        fail++;
                    }
                } catch (Exception e) {
                    fail++;
                    log("폴링 오류: " + e.getMessage());
                    dropTcp();
                }
                Thread.sleep(1000);
            }
            log(String.format(Locale.US, "폴링 종료: 성공 %d / 실패 %d, 평균 %.1f ms, 최대 %.1f ms",
                    ok, fail, ok > 0 ? sum / ok : 0, max));
            return null;
        });
    }

    /** worker 스레드에서 실행 */
    private ModbusResult modbusOnce(int target, int port, int unit, int fc, int addr, String qtyText,
                                    RegisterDecoder.DataType type, RegisterDecoder.ByteOrder order) throws Exception {
        requireStack();
        String key = Ip4.str(target) + ":" + port;
        if (tcp == null || !tcp.isOpen() || !key.equals(tcpKey)) {
            dropTcp();
            tcp = stack.connect(target, port, 2000);
            modbus = new ModbusTcpClient(tcp);
            modbus.setTimeoutMs(1500);
            tcpKey = key;
            log("Modbus 연결: " + tcp);
        }
        ModbusResult r;
        switch (fc) {
            case 3: r = modbus.readHoldingRegisters(unit, addr, parseInt(qtyText, 10)); break;
            case 4: r = modbus.readInputRegisters(unit, addr, parseInt(qtyText, 10)); break;
            case 1: r = modbus.readCoils(unit, addr, parseInt(qtyText, 16)); break;
            case 2: r = modbus.readDiscreteInputs(unit, addr, parseInt(qtyText, 16)); break;
            case 6: r = modbus.writeSingleRegister(unit, addr, parseInt(qtyText, 0)); break;
            case 5: r = modbus.writeSingleCoil(unit, addr, parseInt(qtyText, 0) != 0); break;
            case 16: {
                String[] parts = qtyText.split("[,\\s]+");
                int[] vals = new int[parts.length];
                for (int i = 0; i < parts.length; i++) vals[i] = Integer.parseInt(parts[i]);
                r = modbus.writeMultipleRegisters(unit, addr, vals);
                break;
            }
            default: throw new IllegalArgumentException("지원하지 않는 기능 코드");
        }
        StringBuilder sb = new StringBuilder(r.toString());
        if (r.ok() && r.registers != null) {
            sb.append("\n값 (").append(type).append(' ').append(order).append("):");
            for (int i = 0; i + type.registers <= r.registers.length; i += type.registers) {
                double v = RegisterDecoder.decode(r.registers, i, type, order);
                sb.append(String.format(Locale.US, "\n  [%d] %s", addr + i, fmt(v)));
            }
        } else if (r.ok() && r.bits != null) {
            sb.append("\n비트:");
            for (int i = 0; i < r.bits.length; i++) sb.append(String.format(Locale.US, "\n  [%d] %s", addr + i, r.bits[i] ? "ON" : "OFF"));
        }
        log(sb.toString());
        return r;
    }

    private void requireStack() {
        if (stack == null) throw new IllegalStateException("먼저 [어댑터 연결]을 누르세요");
    }

    private void dropTcp() {
        if (tcp != null) tcp.close();
        tcp = null;
        modbus = null;
        tcpKey = null;
    }

    private void closeStack() {
        dropTcp();
        if (stack != null) {
            try {
                stack.close();
            } catch (Exception ignored) {
            }
        }
        stack = null;
        frameIO = null;
        setText(adapterStatus, "어댑터: 연결 안 됨");
    }

    interface Task {
        void run() throws Exception;
    }

    private void run(String name, Task t) {
        worker.execute(() -> {
            try {
                t.run();
            } catch (Exception e) {
                log("✖ " + name + " 실패: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ input helpers

    private IpConfig readIpConfig() {
        try {
            return IpConfig.of(ipEdit.getText().toString(), readInt(prefixEdit, 24), gwEdit.getText().toString());
        } catch (IllegalArgumentException e) {
            log("✖ IP 설정 오류: " + e.getMessage());
            return null;
        }
    }

    private int readTarget() {
        try {
            return Ip4.parse(targetEdit.getText().toString());
        } catch (IllegalArgumentException e) {
            log("✖ 장비 IP 오류: " + e.getMessage());
            return 0;
        }
    }

    private static int readInt(EditText e, int def) {
        return parseInt(e.getText().toString(), def);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return String.format(Locale.US, "%.4f", v);
    }

    private void saveSettings() {
        prefs.edit()
                .putString("ip", ipEdit.getText().toString())
                .putString("prefix", prefixEdit.getText().toString())
                .putString("gw", gwEdit.getText().toString())
                .putString("target", targetEdit.getText().toString())
                .putString("port", portEdit.getText().toString())
                .putString("unit", unitEdit.getText().toString())
                .putString("addr", addrEdit.getText().toString())
                .putString("qty", qtyEdit.getText().toString())
                .apply();
    }

    // ------------------------------------------------------------------ UI

    private void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + msg + "\n";
        ui.post(() -> {
            if (logView.length() > 60000) logView.setText(logView.getText().subSequence(30000, logView.length()));
            logView.append(line);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void setText(TextView v, String s) {
        ui.post(() -> v.setText(s));
    }

    private View buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        adapterStatus = label("어댑터: 연결 안 됨", true);
        internetStatus = label("폰 인터넷: 확인 중", true);
        root.addView(adapterStatus);
        root.addView(internetStatus);
        root.addView(row(button("USB 정보", v -> showUsbInfo()), button("어댑터 연결", v -> requestAdapter()),
                button("해제", v -> worker.execute(this::closeStack))));

        root.addView(label("앱 IP 설정 (폰 네트워크와 별개)", true));
        ipEdit = edit("앱 IP", prefs.getString("ip", "192.168.10.50"), false);
        prefixEdit = edit("prefix", prefs.getString("prefix", "24"), true);
        gwEdit = edit("게이트웨이(선택)", prefs.getString("gw", ""), false);
        root.addView(row(ipEdit, prefixEdit, gwEdit));
        root.addView(row(button("IP 적용 · 충돌 검사", v -> applyIp())));

        root.addView(label("대상 장비", true));
        targetEdit = edit("장비 IP", prefs.getString("target", "192.168.10.1"), false);
        portEdit = edit("포트", prefs.getString("port", "502"), true);
        unitEdit = edit("Unit ID", prefs.getString("unit", "1"), true);
        root.addView(row(targetEdit, portEdit, unitEdit));
        root.addView(row(button("ARP", v -> doArp()), button("Ping", v -> doPing()), button("포트 확인", v -> doPortCheck())));

        root.addView(label("Modbus TCP", true));
        fcSpinner = spinner(FC_LABELS);
        root.addView(fcSpinner);
        addrEdit = edit("시작 주소(0-based)", prefs.getString("addr", "0"), true);
        qtyEdit = edit("개수 / 쓸 값(콤마)", prefs.getString("qty", "10"), false);
        root.addView(row(addrEdit, qtyEdit));
        String[] types = new String[RegisterDecoder.DataType.values().length];
        for (int i = 0; i < types.length; i++) types[i] = RegisterDecoder.DataType.values()[i].name();
        String[] orders = new String[RegisterDecoder.ByteOrder.values().length];
        for (int i = 0; i < orders.length; i++) orders[i] = RegisterDecoder.ByteOrder.values()[i].name();
        typeSpinner = spinner(types);
        typeSpinner.setSelection(1); // UINT16
        orderSpinner = spinner(orders);
        root.addView(row(typeSpinner, orderSpinner));
        pollButton = button("폴링 시작", v -> togglePoll());
        root.addView(row(button("실행", v -> doModbus()), pollButton,
                button("로그 지우기", v -> logView.setText(""))));

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(logScroll, lp); // 로그 영역만 스크롤, 나머지 입력부는 고정
        return root;
    }

    private TextView label(String s, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(6), 0, dp(2));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText edit(String hint, String value, boolean number) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        return s;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
