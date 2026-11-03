package com.emsfield.fieldtester;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.res.Configuration;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.emsfield.fieldtester.ui.N;
import com.emsfield.fieldtester.ui.Seg;
import com.emsfield.modbus.ModbusResult;
import com.emsfield.modbus.ModbusTcpClient;
import com.emsfield.modbus.RegisterDecoder;
import com.emsfield.modbus.RegisterDecoder.ByteOrder;
import com.emsfield.modbus.RegisterDecoder.DataType;
import com.emsfield.net.Hex;
import com.emsfield.net.Ip4;
import com.emsfield.net.IpConfig;
import com.emsfield.net.NetStack;
import com.emsfield.net.TcpConnection;

import java.math.BigDecimal;
import java.math.MathContext;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Modbus 클라이언트 앱 (Claude Design "Modbus Client" 핸드오프 적용).
 * <p>
 * 상태는 모두 UI 스레드에서만 바꾸고, 통신은 {@link #net} (Modbus·장비 확인) 과
 * {@link #pingExec} (Ping 화면) 스레드에서 실행한 뒤 결과를 UI 스레드로 돌려준다.
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.emsfield.fieldtester.USB_PERMISSION";

    // ================================================================== state (UI thread only)
    enum Ad { NONE, BUSY, UP }

    enum IpSt { IDLE, BUSY, OK, CONFLICT, BAD }

    static final class LogEntry {
        final String t, dir, ko, en;

        LogEntry(String t, String dir, String ko, String en) {
            this.t = t;
            this.dir = dir;
            this.ko = ko;
            this.en = en;
        }
    }

    static final class Check {
        final String text;
        final String key; // 번역 키 (text 대신)
        final boolean err;
        final boolean pending;

        Check(String text, String key, boolean err, boolean pending) {
            this.text = text;
            this.key = key;
            this.err = err;
            this.pending = pending;
        }
    }

    /** 요청 (formReq) */
    static final class Req {
        String fn, wval, type, order;
        int start, count;

        Req copy() {
            Req r = new Req();
            r.fn = fn;
            r.wval = wval;
            r.type = type;
            r.order = order;
            r.start = start;
            r.count = count;
            return r;
        }
    }

    static final class Item {
        final int addr;
        final int[] words; // 레지스터
        final int bit;     // 코일 (-1 = 레지스터)

        Item(int addr, int[] words, int bit) {
            this.addr = addr;
            this.words = words;
            this.bit = bit;
        }
    }

    static final class Result {
        String fn, type, order, at;
        double ms;
        List<Item> items = new ArrayList<>();
        Set<Integer> changed = new HashSet<>();
        Req req;
    }

    static final class PingRes {
        int seq, size, port, ttl;
        boolean ok, tcp, refused;
        double ms;
    }

    static final class Sheet {
        int addr;
        String type, order;
    }

    String lang = "ko";
    String screen = "main";
    boolean drawer;

    Ad adapter = Ad.NONE;
    String adapterSummary = "", adapterChip = "";
    IpSt ipState = IpSt.IDLE;
    String ip, prefix, gw, devIp, devPort, unit;
    String fn, start, count, wval = "", type, order, fmt = "HEX", logFilter = "all";
    int interval = 1000;
    final Map<String, Check> checks = new HashMap<>();
    Result result;
    int tx, rx, err;
    double msSum;
    boolean lastErr, polling;
    final ArrayList<LogEntry> log = new ArrayList<>();
    int logVersion, resultVersion, pingVersion;
    String netType = "", netState = "checking";
    final Map<String, Boolean> open = new HashMap<>();

    String pingTarget = "192.168.10.1", pingSize = "56", pingPort = "502";
    boolean pingUsePort, pingRunning;
    int pingCount = 4, pingIv = 1000;
    final ArrayList<PingRes> pingRes = new ArrayList<>();

    Sheet sheet;
    String sheetVal = "";

    private String toastKey, toastAction;
    private int pollGen, pingGen;

    // ================================================================== infra
    final Handler ui = new Handler(Looper.getMainLooper());
    final ScheduledExecutorService net = Executors.newSingleThreadScheduledExecutor();
    final ScheduledExecutorService pingExec = Executors.newSingleThreadScheduledExecutor();
    private UsbManager usb;
    private ConnectivityManager cm;
    private SharedPreferences prefs;

    // net 스레드 전용
    private volatile NetStack stack;
    private UsbCdcEcmFrameIO frameIO;
    private TcpConnection tcp;
    private ModbusTcpClient modbus;
    private String tcpKey;

    // views
    private FrameLayout root;
    private LinearLayout screenHost;
    private ScrollView mainScroll;
    private TextView titleView, subView;
    private MainScreen mainScreen;
    private PingScreen pingScreen;
    private BannerSlot bannerSlot;
    private int writeInFlight;
    private int navInset;
    /** OS 가 잡은 이더넷. 광고용 bindProcessToNetwork 와 무관하게 "어댑터를 OS 가 사용 중"을 유지한다. */
    private final Map<Network, String> osEthernet = new HashMap<>();
    private boolean ethernetWatchOn;
    private FrameLayout toastView, drawerView, sheetView;
    private final Runnable hideToast = () -> {
        toastKey = null;
        renderToast();
    };

    String t(String key) {
        return I18n.get(lang, key);
    }

    // ================================================================== lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        N.density = getResources().getDisplayMetrics().density;
        N.init(this);
        getWindow().setStatusBarColor(N.BG);
        getWindow().setNavigationBarColor(N.BG);
        allowSoftKeyboardWithHardwareKeyboard();
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        prefs = getSharedPreferences("fieldtester", MODE_PRIVATE);
        loadSettings();
        for (String k : new String[]{"adapter", "ip", "target", "req", "res", "log"}) open.put(k, true);
        addLog("INFO", "앱 시작. 어댑터를 꽂고 [USB 정보] 또는 [연결]을 누르세요.",
                "App started. Plug in the adapter and tap [USB info] or [Connect].");

        build();

        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, f);
        registerPhoneNetCallback();
        registerEthernetWatch();

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) usbInfo();
        if (bannerSlot != null) bannerSlot.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bannerSlot != null) bannerSlot.onHostResume();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        allowSoftKeyboardWithHardwareKeyboard();
    }

    /** USB 이더넷 어댑터가 키보드로 잡혀도 화면 키보드를 숨기지 않게 한다. */
    private void allowSoftKeyboardWithHardwareKeyboard() {
        try {
            android.provider.Settings.Secure.putInt(getContentResolver(), "show_ime_with_hard_keyboard", 1);
        } catch (SecurityException ignored) {
            // 권한은 debug 매니페스트에만 있다. adb 로 부여된 디버그 설치에서만 성공한다.
        }
    }

    @Override
    protected void onPause() {
        if (bannerSlot != null) bannerSlot.onHostPause();
        super.onPause();
        saveSettings();
    }

    @Override
    protected void onDestroy() {
        if (bannerSlot != null) {
            bannerSlot.destroy();
            bannerSlot = null;
        }
        stopPoll();
        pingStop();
        unregisterReceiver(usbReceiver);
        cm.unregisterNetworkCallback(netCallback);
        if (ethernetWatchOn) {
            try {
                cm.unregisterNetworkCallback(ethernetWatch);
            } catch (RuntimeException ignored) {
            }
        }
        net.execute(this::closeStack);
        net.shutdown();
        pingExec.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (sheet != null) {
            sheet = null;
            renderSheet();
        } else if (drawer) {
            drawer = false;
            renderDrawer();
        } else if (!"main".equals(screen)) {
            go("main");
        } else {
            super.onBackPressed();
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) {
                    doConnect(d);
                } else {
                    adapter = Ad.NONE;
                    addLog("ERR", "USB 사용 권한이 거부되었습니다", "USB permission denied");
                    render();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if (adapter != Ad.NONE) {
                    addLog("ERR", "USB 어댑터가 분리되었습니다", "USB adapter detached");
                    disconnect(false);
                }
            }
        }
    };

    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            String type = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "netWifi"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "netCell"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "netEth" : "netOther";
            String st = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "netOk" : "netChecking";
            ui.post(() -> {
                netType = type;
                netState = st;
                render();
            });
        }

        @Override
        public void onLost(Network network) {
            ui.post(() -> {
                netType = "";
                netState = "netNone";
                render();
            });
        }
    };

    /**
     * 폰 인터넷 표시는 프로세스 기본 네트워크를 본다.
     * 광고가 이더넷을 피하려고 프로세스를 묶으면 그 콜백은 LTE/Wi-Fi 를 가리키므로,
     * 이더넷 인터페이스가 남아 있는지는 {@link #ethernetWatch} 로 따로 본다.
     */
    private void registerPhoneNetCallback() {
        cm.registerDefaultNetworkCallback(netCallback);
    }

    private void registerEthernetWatch() {
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
        try {
            cm.registerNetworkCallback(req, ethernetWatch, ui);
            ethernetWatchOn = true;
        } catch (RuntimeException e) {
            android.util.Log.w("FieldNet", "ethernet watch failed", e);
        }
    }

    private final ConnectivityManager.NetworkCallback ethernetWatch = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            osEthernet.put(network, "netChecking");
            render();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            String st = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "netOk" : "netChecking";
            osEthernet.put(network, st);
            render();
        }

        @Override
        public void onLost(Network network) {
            osEthernet.remove(network);
            render();
        }
    };

    String phoneNetLine() {
        if (!osEthernet.isEmpty()) {
            String st = osEthernet.values().iterator().next();
            return t("phoneNet") + ": " + t("netEth") + " · " + t(st);
        }
        if ("netNone".equals(netState) || netType.isEmpty()) {
            return t("phoneNet") + ": " + ("netNone".equals(netState) ? t("netNone") : t("netChecking"));
        }
        return t("phoneNet") + ": " + t(netType) + " · " + t(netState);
    }

    // ================================================================== settings
    private void loadSettings() {
        lang = prefs.getString("lang", "ko");
        ip = prefs.getString("ip", "192.168.10.5");
        prefix = prefs.getString("prefix", "24");
        gw = prefs.getString("gw", "");
        devIp = prefs.getString("devIp", "192.168.10.1");
        devPort = prefs.getString("devPort", "502");
        unit = prefs.getString("unit", "1");
        fn = prefs.getString("fn", "03");
        start = prefs.getString("start", "0");
        count = prefs.getString("count", "10");
        type = prefs.getString("type", "UINT16");
        order = prefs.getString("order", "ABCD");
        interval = prefs.getInt("interval", 1000);
        pingTarget = devIp;
    }

    void saveSettings() {
        prefs.edit().putString("lang", lang).putString("ip", ip).putString("prefix", prefix).putString("gw", gw)
                .putString("devIp", devIp).putString("devPort", devPort).putString("unit", unit)
                .putString("fn", fn).putString("start", start).putString("count", count)
                .putString("type", type).putString("order", order).putInt("interval", interval).apply();
    }

    // ================================================================== log
    static String ts() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    void addLog(String dir, String ko, String en) {
        log.add(0, new LogEntry(ts(), dir, ko, en));
        while (log.size() > 400) log.remove(log.size() - 1);
        logVersion++;
    }

    void addLog(String dir, String both) {
        addLog(dir, both, both);
    }

    /** NetStack/드라이버 로그 (모든 스레드) */
    private final NetStack.Logger stackLog = msg -> ui.post(() -> {
        addLog(msg.startsWith("⚠") ? "ERR" : "INFO", msg);
        render();
    });

    void clearLog() {
        log.clear();
        tx = rx = err = 0;
        msSum = 0;
        lastErr = false;
        logVersion++;
        render();
    }

    // ================================================================== toast
    void toast(String key, String action) {
        toastKey = key;
        toastAction = action;
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, 3200);
        renderToast();
    }

    // ================================================================== USB adapter
    private UsbDevice findAdapter() {
        UsbDevice fallback = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (UsbAdapterInfo.hasEcm(d) || UsbAdapterInfo.knownChip(d) != null) return d;
            fallback = d;
        }
        return fallback;
    }

    void usbInfo() {
        if (usb.getDeviceList().isEmpty()) {
            addLog("ERR", "연결된 USB 장치가 없습니다 (OTG 지원, 케이블 연결 확인)",
                    "No USB device found (check OTG support and the cable)");
        } else {
            for (UsbDevice d : usb.getDeviceList().values()) {
                String chip = UsbAdapterInfo.knownChip(d);
                String head = String.format(Locale.US, "USB %04X:%04X %s · ECM %s",
                        d.getVendorId(), d.getProductId(), chip != null ? chip : "?",
                        UsbAdapterInfo.hasEcm(d) ? "OK" : "✕");
                addLog("INFO", head);
                addLog("INFO", UsbAdapterInfo.describe(d).trim());
            }
        }
        render();
    }

    void connectAdapter() {
        UsbDevice d = findAdapter();
        if (d == null) {
            addLog("ERR", "USB 이더넷 어댑터를 찾지 못했습니다", "No USB Ethernet adapter found");
            render();
            return;
        }
        adapter = Ad.BUSY;
        render();
        if (usb.hasPermission(d)) {
            doConnect(d);
            return;
        }
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
    }

    private void doConnect(UsbDevice d) {
        adapter = Ad.BUSY;
        render();
        IpConfig cfg = parseIpConfig();
        IpConfig initial = cfg != null ? cfg : IpConfig.of("169.254.10.50", 16, "");
        String chip = UsbAdapterInfo.knownChip(d);
        String summary = chipSummary(d, chip);
        String chipLine = String.format(Locale.US, "USB %04X:%04X · ECM", d.getVendorId(), d.getProductId());
        net.execute(() -> {
            closeStack();
            try {
                frameIO = UsbCdcEcmFrameIO.open(usb, d, stackLog);
                NetStack s = new NetStack(frameIO, initial, stackLog);
                s.start();
                stack = s;
                ui.post(() -> {
                    adapter = Ad.UP;
                    ipState = IpSt.IDLE;
                    adapterSummary = summary;
                    adapterChip = chipLine;
                    addLog("INFO", "어댑터 연결됨: " + summary, "Adapter connected: " + summary);
                    render();
                });
            } catch (Exception e) {
                closeStack();
                ui.post(() -> {
                    adapter = Ad.NONE;
                    addLog("ERR", "어댑터 연결 실패: " + e.getMessage(), "Adapter connection failed: " + e.getMessage());
                    render();
                });
            }
        });
    }

    /** "ASIX AX88179 (1Gbps)" → "AX88179 · 1 Gbps" */
    private static String chipSummary(UsbDevice d, String chip) {
        if (chip == null) return String.format(Locale.US, "USB %04X:%04X", d.getVendorId(), d.getProductId());
        String name = chip.replaceFirst("^(Realtek|ASIX)\\s+", "");
        String speed = "";
        int p = name.indexOf('(');
        if (p > 0) {
            speed = name.substring(p + 1, name.indexOf(')', p)).replaceAll("(\\d)([GM]bps)", "$1 $2");
            name = name.substring(0, p).trim();
        }
        return speed.isEmpty() ? name : name + " · " + speed;
    }

    void disconnect(boolean byUser) {
        stopPoll();
        pingStop();
        adapter = Ad.NONE;
        ipState = IpSt.IDLE;
        checks.clear();
        if (byUser) addLog("INFO", "어댑터 해제됨", "Adapter released");
        net.execute(this::closeStack);
        render();
    }

    /** net 스레드 */
    private void closeStack() {
        dropTcp();
        NetStack s = stack;
        stack = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        frameIO = null;
    }

    /** net 스레드 */
    private void dropTcp() {
        if (tcp != null) tcp.reset();
        tcp = null;
        modbus = null;
        tcpKey = null;
    }

    // ================================================================== app IP
    IpConfig parseIpConfig() {
        try {
            int pf = Integer.parseInt(prefix.trim());
            return IpConfig.of(ip, pf, gw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    void ipEdited() {
        if (ipState != IpSt.BUSY && ipState != IpSt.IDLE) {
            ipState = IpSt.IDLE;
            render();
        }
    }

    void applyIp() {
        IpConfig cfg = parseIpConfig();
        if (cfg == null) {
            ipState = IpSt.BAD;
            render();
            return;
        }
        if (adapter != Ad.UP) {
            toast("needAdapter", "adapter");
            return;
        }
        ipState = IpSt.BUSY;
        saveSettings();
        render();
        String ipStr = ip.trim(), pf = prefix.trim();
        net.execute(() -> {
            NetStack s = stack;
            if (s == null) return;
            dropTcp();
            s.setConfig(cfg);
            byte[] conflict = null;
            String error = null;
            try {
                conflict = s.probeAddressConflict(600);
            } catch (Exception e) {
                error = e.getMessage();
            }
            byte[] c = conflict;
            String er = error;
            ui.post(() -> {
                if (er != null) {
                    ipState = IpSt.IDLE;
                    addLog("ERR", "IP 적용 실패: " + er, "Failed to apply IP: " + er);
                } else if (c != null) {
                    ipState = IpSt.CONFLICT;
                    addLog("ERR", "IP 충돌: " + ipStr + " 응답 MAC " + Hex.mac(c), "IP conflict: " + ipStr + " answered by " + Hex.mac(c));
                } else {
                    ipState = IpSt.OK;
                    addLog("INFO", "USB " + ipStr + "/" + pf + " 적용 · ARP probe 응답 없음",
                            "USB " + ipStr + "/" + pf + " applied · no ARP probe reply");
                }
                render();
            });
        });
    }

    // ================================================================== target checks
    void runCheck(String k) {
        if (adapter != Ad.UP) {
            checks.put(k, new Check(null, "noAdapter", true, false));
            render();
            return;
        }
        int target;
        try {
            target = Ip4.parse(devIp);
        } catch (RuntimeException e) {
            checks.put(k, new Check("invalid", null, true, false));
            render();
            return;
        }
        int parsedPort = 0;
        if ("port".equals(k)) {
            try {
                parsedPort = Integer.parseInt(devPort.trim());
                if (parsedPort < 1 || parsedPort > 65535) throw new NumberFormatException();
            } catch (RuntimeException e) {
                checks.put(k, new Check("invalid", null, true, false));
                render();
                return;
            }
        }
        final int port = parsedPort;
        checks.put(k, new Check("…", null, false, true));
        render();
        String dev = devIp.trim();
        net.execute(() -> {
            NetStack s = stack;
            Check r;
            String logDir, logText;
            if (s == null) {
                r = new Check(null, "noAdapter", true, false);
                logDir = "ERR";
                logText = k.toUpperCase(Locale.US) + " " + dev + ": no adapter";
            } else if (k.equals("arp")) {
                try {
                    String mac = Hex.mac(s.arp(target, 1500));
                    r = new Check(mac, null, false, false);
                    logDir = "INFO";
                    logText = "ARP " + dev + " → " + mac;
                } catch (Exception e) {
                    r = new Check("timeout", null, true, false);
                    logDir = "ERR";
                    logText = "ARP " + dev + ": " + e.getMessage();
                }
            } else if (k.equals("ping")) {
                int ok = 0;
                double sum = 0;
                String lastErr = null;
                for (int i = 0; i < 4; i++) {
                    NetStack.PingResult p = s.ping(target, 1000);
                    if (p.ok) {
                        ok++;
                        sum += p.rttMs;
                    } else {
                        lastErr = p.error;
                    }
                }
                if (ok > 0) {
                    String avg = String.format(Locale.US, "%.1f", sum / ok);
                    r = new Check(avg + " ms · " + ok + "/4", null, false, false);
                    logDir = "INFO";
                    logText = "PING " + dev + ": " + ok + "/4, avg " + avg + " ms";
                } else {
                    r = new Check("timeout", null, true, false);
                    logDir = "ERR";
                    logText = "PING " + dev + ": 0/4 · " + lastErr;
                }
            } else {
                try {
                    TcpConnection c = s.connect(target, port, 2000);
                    c.reset();
                    r = new Check(port + " open", null, false, false);
                    logDir = "INFO";
                    logText = "TCP " + dev + ":" + port + " open";
                } catch (ConnectException e) {
                    r = new Check("closed", null, true, false);
                    logDir = "ERR";
                    logText = "TCP " + dev + ":" + port + " closed (RST)";
                } catch (Exception e) {
                    r = new Check("timeout", null, true, false);
                    logDir = "ERR";
                    logText = "TCP " + dev + ":" + port + ": " + e.getMessage();
                }
            }
            Check fr = r;
            String d = logDir, lt = logText;
            ui.post(() -> {
                checks.put(k, fr);
                addLog(d, lt);
                render();
            });
        });
    }

    // ================================================================== Modbus
    static boolean isRead(String fn) {
        return fn.equals("01") || fn.equals("02") || fn.equals("03") || fn.equals("04");
    }

    static boolean isReg(String fn) {
        return fn.equals("03") || fn.equals("04") || fn.equals("06") || fn.equals("10");
    }

    static int w(String type) {
        return type.contains("32") ? 2 : 1;
    }

    static DataType dt(String type) {
        return DataType.valueOf(type);
    }

    static ByteOrder bo(String order) {
        return ByteOrder.valueOf(order);
    }

    static int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    static String fnShort(String fn) {
        return "FC" + String.format(Locale.US, "%02d", Integer.parseInt(fn, 16));
    }

    Req formReq() {
        Req r = new Req();
        r.fn = fn;
        r.start = parseIntOr(start, 0);
        r.count = Math.max(1, parseIntOr(count, 1));
        r.wval = wval;
        r.type = type;
        r.order = order;
        return r;
    }

    boolean ready() {
        if (adapter != Ad.UP) {
            toast("needAdapter", "adapter");
            return false;
        }
        if (ipState != IpSt.OK) {
            toast("needIp", "ip");
            return false;
        }
        return true;
    }

    void onFnChanged(String newFn) {
        stopPoll();
        fn = newFn;
        switch (newFn) {
            case "05": wval = "ON"; break;
            case "0F": wval = "1,0,1"; break;
            case "10": wval = "100, 200"; break;
            case "06": wval = "100"; break;
            default: break;
        }
        render();
    }

    void execute() {
        Req req = formReq();
        Result prev = result;
        boolean rereadAfter = !isRead(req.fn) && prev != null && isRead(prev.fn);
        send(req, rereadAfter ? () -> send(prev.req, null, null) : null, null);
    }

    /**
     * @param onSuccess 정상 응답 후 (UI 스레드)
     * @param onDone    성공/실패와 관계없이 끝난 후 (UI 스레드, 폴링 예약용)
     */
    void send(Req req, Runnable onSuccess, Runnable onDone) {
        if (!ready()) {
            stopPoll();
            return;
        }
        final int target;
        final int port;
        final int unitId;
        try {
            target = Ip4.parse(devIp);
            port = Integer.parseInt(devPort.trim());
            unitId = Integer.parseInt(unit.trim());
        } catch (RuntimeException e) {
            addLog("ERR", "대상 장비 설정 오류: " + e.getMessage(), "Invalid target settings: " + e.getMessage());
            render();
            if (onDone != null) onDone.run();
            return;
        }
        final String key = Ip4.str(target) + ":" + port;
        final boolean pauseAd = !isRead(req.fn);
        if (pauseAd) {
            writeInFlight++;
            syncBanner();
        }
        net.execute(() -> {
            String errKo = null, errEn = null;
            ModbusResult r = null;
            try {
                NetStack s = stack;
                if (s == null) throw new IllegalStateException("adapter");
                if (tcp == null || !tcp.isOpen() || !key.equals(tcpKey)) {
                    dropTcp();
                    tcp = s.connect(target, port, 2000);
                    modbus = new ModbusTcpClient(tcp);
                    modbus.setTimeoutMs(1000);
                    modbus.setFrameListener(new ModbusTcpClient.FrameListener() {
                        @Override
                        public void onTx(byte[] adu) {
                            String hex = Hex.of(adu);
                            ui.post(() -> {
                                tx++;
                                addLog("TX", hex);
                                render();
                            });
                        }

                        @Override
                        public void onRx(byte[] adu) {
                        }
                    });
                    tcpKey = key;
                }
                r = call(modbus, unitId, req);
            } catch (NumberFormatException e) {
                errKo = "잘못된 값: " + req.wval;
                errEn = "Invalid value: " + req.wval;
            } catch (IllegalArgumentException e) {
                errKo = errEn = e.getMessage();
            } catch (SocketTimeoutException e) {
                errKo = "응답 없음 (타임아웃 1000 ms)";
                errEn = "No response (timeout 1000 ms)";
                dropTcp();
            } catch (ConnectException e) {
                errKo = "연결 거부: " + key + " (포트 확인)";
                errEn = "Connection refused: " + key + " (check port)";
                dropTcp();
            } catch (Exception e) {
                errKo = errEn = e.getMessage();
                dropTcp();
            }
            ModbusResult fr = r;
            String ek = errKo, ee = errEn;
            ui.post(() -> {
                if (pauseAd) {
                    writeInFlight = Math.max(0, writeInFlight - 1);
                    syncBanner();
                }
                if (fr == null) {
                    err++;
                    lastErr = true;
                    addLog("ERR", ek, ee);
                } else {
                    rx++;
                    msSum += fr.rttMs;
                    String ms = String.format(Locale.US, "%.1f", fr.rttMs);
                    addLog("RX", Hex.of(fr.response) + "  · " + ms + " ms");
                    if (!fr.ok()) {
                        err++;
                        lastErr = true;
                        String code = String.format(Locale.US, "%02d", fr.exceptionCode);
                        addLog("ERR", "예외 " + code + " · " + I18n.exception(fr.exceptionCode),
                                "Exception " + code + " · " + I18n.exception(fr.exceptionCode));
                    } else {
                        lastErr = false;
                        if (isRead(req.fn)) applyResult(req, fr);
                        if (onSuccess != null) onSuccess.run();
                    }
                }
                render();
                if (onDone != null) onDone.run();
            });
        });
    }

    /** net 스레드: 요청 실행 */
    private static ModbusResult call(ModbusTcpClient mb, int u, Req req) throws Exception {
        int a = req.start;
        int W = w(req.type);
        switch (req.fn) {
            case "01": return mb.readCoils(u, a, req.count);
            case "02": return mb.readDiscreteInputs(u, a, req.count);
            case "03": return mb.readHoldingRegisters(u, a, req.count * W);
            case "04": return mb.readInputRegisters(u, a, req.count * W);
            case "05": return mb.writeSingleCoil(u, a, isOn(req.wval));
            case "06": {
                String t = W == 2 ? "UINT16" : req.type;
                int[] words = RegisterDecoder.encode(Double.parseDouble(req.wval.trim()), dt(t), bo(req.order));
                return mb.writeSingleRegister(u, a, words[0]);
            }
            case "0F": {
                String[] parts = splitValues(req.wval);
                boolean[] bits = new boolean[parts.length];
                for (int i = 0; i < parts.length; i++) bits[i] = isOn(parts[i]);
                return mb.writeMultipleCoils(u, a, bits);
            }
            case "10": {
                String[] parts = splitValues(req.wval);
                int[] words = new int[parts.length * W];
                for (int i = 0; i < parts.length; i++) {
                    int[] wv = RegisterDecoder.encode(Double.parseDouble(parts[i]), dt(req.type), bo(req.order));
                    System.arraycopy(wv, 0, words, i * W, W);
                }
                return mb.writeMultipleRegisters(u, a, words);
            }
            default: throw new IllegalArgumentException("Function " + req.fn);
        }
    }

    static String[] splitValues(String s) {
        String[] p = s.trim().split("[,\\s]+");
        if (p.length == 1 && p[0].isEmpty()) throw new IllegalArgumentException("값을 입력하세요 / Enter values");
        return p;
    }

    static boolean isOn(String s) {
        return s.trim().matches("(?i)1|on|true");
    }

    private void applyResult(Req req, ModbusResult r) {
        Result prev = result;
        Result res = new Result();
        res.fn = req.fn;
        res.type = req.type;
        res.order = req.order;
        res.ms = r.rttMs;
        res.at = ts();
        res.req = req.copy();
        if (r.registers != null) {
            int W = w(req.type);
            boolean comparable = prev != null && prev.fn.equals(req.fn) && prev.type.equals(req.type);
            for (int i = 0; i < req.count; i++) {
                int ad = req.start + i * W;
                int[] ws = new int[W];
                System.arraycopy(r.registers, i * W, ws, 0, W);
                res.items.add(new Item(ad, ws, -1));
                if (comparable) {
                    for (Item old : prev.items) {
                        if (old.addr == ad && !java.util.Arrays.equals(old.words, ws)) res.changed.add(ad);
                    }
                }
            }
        } else if (r.bits != null) {
            boolean comparable = prev != null && prev.fn.equals(req.fn);
            for (int i = 0; i < r.bits.length; i++) {
                int b = r.bits[i] ? 1 : 0;
                int ad = req.start + i;
                res.items.add(new Item(ad, null, b));
                if (comparable) {
                    for (Item old : prev.items) if (old.addr == ad && old.bit != b) res.changed.add(ad);
                }
            }
        }
        result = res;
        resultVersion++;
    }

    void togglePoll() {
        if (polling) {
            stopPoll();
            render();
        } else if (ready()) {
            startPoll();
        }
    }

    void startPoll() {
        int gen = ++pollGen;
        Req req = formReq();
        polling = true;
        syncBanner();
        render();
        pollTick(gen, req);
    }

    private void pollTick(int gen, Req req) {
        if (gen != pollGen || !polling) return;
        send(req, null, () -> {
            if (gen == pollGen && polling) ui.postDelayed(() -> pollTick(gen, req), interval);
        });
    }

    void stopPoll() {
        pollGen++;
        polling = false;
        syncBanner();
    }

    void setInterval(int ms) {
        interval = ms;
        if (polling) startPoll();
        else render();
    }

    /** 응답 행 탭: FC03 → 쓰기 시트, FC01 → 코일 반전 */
    void tapRow(Item it) {
        Result r = result;
        if (r == null) return;
        if (r.fn.equals("03")) {
            sheet = new Sheet();
            sheet.addr = it.addr;
            sheet.type = r.type;
            sheet.order = r.order;
            sheetVal = fmtVal(RegisterDecoder.decode(it.words, 0, dt(r.type), bo(r.order)), r.type);
            renderSheet();
        } else if (r.fn.equals("01")) {
            Req q = new Req();
            q.fn = "05";
            q.start = it.addr;
            q.count = 1;
            q.wval = it.bit == 1 ? "OFF" : "ON";
            q.type = "UINT16";
            q.order = "ABCD";
            Req again = r.req;
            send(q, () -> send(again, null, null), null);
        }
    }

    String sheetPreview() {
        if (sheet == null) return "";
        try {
            int[] ws = RegisterDecoder.encode(Double.parseDouble(sheetVal.trim()), dt(sheet.type), bo(sheet.order));
            StringBuilder sb = new StringBuilder(w(sheet.type) == 2 ? "FC16 →" : "FC06 →");
            for (int x : ws) sb.append(String.format(Locale.US, " 0x%04X", x));
            return sb.toString();
        } catch (RuntimeException e) {
            return "—";
        }
    }

    void submitSheet() {
        Sheet sh = sheet;
        if (sh == null) return;
        Req q = new Req();
        q.fn = w(sh.type) == 2 ? "10" : "06";
        q.start = sh.addr;
        q.count = 1;
        q.wval = sheetVal;
        q.type = sh.type;
        q.order = sh.order;
        sheet = null;
        renderSheet();
        Result r = result;
        send(q, r != null ? () -> send(r.req, null, null) : null, null);
    }

    static String fmtVal(double v, String type) {
        if (type.equals("FLOAT32")) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf((float) v);
            return new BigDecimal(v).round(new MathContext(7)).stripTrailingZeros().toPlainString();
        }
        return String.valueOf((long) v);
    }

    /** 포트 칸이 비어 있으면 ICMP. 포트가 있을 때만 TCP 핑. */
    boolean pingTcp() {
        return pingUsePort && pingPort != null && !pingPort.trim().isEmpty();
    }

    // ================================================================== Ping screen
    void pingStart() {
        if (adapter != Ad.UP) {
            toast("needAdapter", null);
            return;
        }
        final int target;
        try {
            target = Ip4.parse(pingTarget);
        } catch (RuntimeException e) {
            addLog("ERR", "잘못된 IP: " + pingTarget, "Invalid IP: " + pingTarget);
            render();
            return;
        }
        final int size = Math.max(0, Math.min(NetStack.MAX_PING_PAYLOAD, parseIntOr(pingSize, 56)));
        final String portText = pingPort == null ? "" : pingPort.trim();
        final boolean tcpMode = pingUsePort && !portText.isEmpty();
        final int port;
        if (tcpMode) {
            port = parseIntOr(portText, -1);
            if (port < 1 || port > 65535) {
                addLog("ERR", "잘못된 포트: " + portText, "Invalid port: " + portText);
                render();
                return;
            }
        } else {
            port = 0;
        }
        final int max = pingCount, iv = pingIv;
        final int gen = ++pingGen;
        pingRes.clear();
        pingRunning = true;
        syncBanner();
        pingVersion++;
        render();
        pingExec.execute(new Runnable() {
            int seq;

            @Override
            public void run() {
                if (gen != pingGen) return;
                NetStack s = stack;
                PingRes r = new PingRes();
                r.seq = ++seq;
                r.size = size;
                r.tcp = tcpMode;
                r.port = port;
                if (s != null) {
                    if (tcpMode) {
                        long t0 = System.nanoTime();
                        try {
                            TcpConnection c = s.connect(target, port, 1000);
                            r.ms = (System.nanoTime() - t0) / 1e6;
                            r.ok = true;
                            c.reset();
                        } catch (ConnectException e) {
                            r.refused = true;
                            r.ms = (System.nanoTime() - t0) / 1e6;
                        } catch (Exception e) {
                            r.ms = 0;
                        }
                    } else {
                        NetStack.PingResult p = s.ping(target, 1000, size);
                        r.ok = p.ok;
                        r.ms = p.rttMs;
                        r.ttl = p.ttl;
                    }
                }
                Runnable self = this;
                ui.post(() -> {
                    if (gen != pingGen) return;
                    pingRes.add(r);
                    while (pingRes.size() > 500) pingRes.remove(0);
                    pingVersion++;
                    if (max > 0 && r.seq >= max) {
                        pingStop();
                    } else {
                        pingExec.schedule(self, iv, TimeUnit.MILLISECONDS);
                    }
                    render();
                });
            }
        });
    }

    void pingStop() {
        pingGen++;
        if (!pingRunning) return;
        pingRunning = false;
        syncBanner();
        int ok = 0;
        double sum = 0;
        for (PingRes r : pingRes) {
            if (r.ok) {
                ok++;
                sum += r.ms;
            }
        }
        if (!pingRes.isEmpty()) {
            PingRes first = pingRes.get(0);
            String head = (first.tcp ? "TCPING " : "PING ") + pingTarget.trim() + (first.tcp ? ":" + first.port : "")
                    + ": " + ok + "/" + pingRes.size();
            if (ok > 0) head += String.format(Locale.US, ", avg %.2f ms", sum / ok);
            addLog(ok > 0 ? "INFO" : "ERR", head);
        }
        pingVersion++;
        render();
    }

    // ================================================================== navigation
    void go(String to) {
        if (to.equals("ping") && !pingRunning && pingRes.isEmpty()) pingTarget = devIp;
        screen = to;
        syncBanner();
        drawer = false;
        showScreen();
        renderDrawer();
        render();
    }

    void setLang(String l) {
        if (l.equals(lang)) return;
        lang = l;
        saveSettings();
        build(); // 모든 라벨을 다시 만든다 (입력값은 상태에 보존)
    }

    void toggleSection(String k) {
        open.put(k, !Boolean.TRUE.equals(open.get(k)));
        render();
    }

    // ================================================================== view building
    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(N.BG);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(buildTopBar());
        screenHost = new LinearLayout(this);
        screenHost.setOrientation(LinearLayout.VERTICAL);
        column.addView(screenHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        attachBanner(column);
        root.addView(column);

        mainScreen = new MainScreen(this);
        pingScreen = new PingScreen(this);
        mainScroll = scroll(mainScreen.build(), 0);
        showScreen();

        toastView = new FrameLayout(this);
        drawerView = new FrameLayout(this);
        sheetView = new FrameLayout(this);
        root.addView(toastView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(drawerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(sheetView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        watchInsets(root);
        setContentView(root);
        render();
        renderToast();
        renderDrawer();
        renderSheet();
    }

    /** 상태바 아래로 내용을 내리고, 배너는 내비게이션 바 위에 둔다. */
    private void watchInsets(View rootView) {
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = statusBarTop(insets);
            navInset = navBarBottom(insets);
            if (v.getPaddingTop() != top) v.setPadding(0, top, 0, v.getPaddingBottom());
            if (bannerSlot != null) bannerSlot.setBottomInset(navInset);
            return insets;
        });
        rootView.requestApplyInsets();
    }

    private static int navBarBottom(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    /** 언어 변경으로 뷰를 다시 만들어도 같은 배너 슬롯을 붙인다. */
    private void attachBanner(LinearLayout column) {
        if (bannerSlot == null) bannerSlot = new BannerSlot(this, cm);
        View banner = bannerSlot.view();
        if (banner.getParent() instanceof ViewGroup) ((ViewGroup) banner.getParent()).removeView(banner);
        column.addView(banner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bannerSlot.setVisibilityListener(() -> {
            if (toastKey != null) renderToast();
        });
        bannerSlot.setBottomInset(navInset);
        syncBanner();
    }

    private void syncBanner() {
        if (bannerSlot == null) return;
        bannerSlot.setBusy(polling || pingRunning || writeInFlight > 0);
        bannerSlot.setOnMainScreen("main".equals(screen));
    }

    private static int statusBarTop(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
            return bars.top;
        }
        return insets.getSystemWindowInsetTop();
    }

    private ScrollView scroll(View content, int topPadDp) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false); // .scr::-webkit-scrollbar{width:0}
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setFillViewport(true);
        content.setPadding(N.dp(16), N.dp(topPadDp), N.dp(16), N.dp(24));
        sv.addView(content);
        return sv;
    }

    private void showScreen() {
        screenHost.removeAllViews();
        View v = "ping".equals(screen) ? scroll(pingScreen.build(), 4) : mainScroll;
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        screenHost.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View buildTopBar() {
        LinearLayout bar = N.row(this, 6);
        bar.setPadding(N.dp(8), N.dp(6), N.dp(16), N.dp(10));
        TextView menu = N.icon(this, N.I_LIST, 22, N.NEUTRAL_200);
        menu.setBackground(pressable(N.alpha(N.ACCENT, 0.18f), N.RADIUS_MD));
        menu.setOnClickListener(v -> {
            drawer = true;
            renderDrawer();
        });
        menu.setContentDescription("Menu");
        bar.addView(menu, new LinearLayout.LayoutParams(N.dp(44), N.dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titleView = N.mediumText(this, "", 19, N.TEXT);
        titleView.setLetterSpacing(-0.01f);
        titles.addView(titleView);
        subView = N.ellipsize(N.tnum(N.text(this, "", 11, N.NEUTRAL_500)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = N.dp(3);
        titles.addView(subView, sp);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Seg langs = new Seg(this, new String[]{"KO", "EN"}, 12, 9, 4, false);
        langs.setSelected("en".equals(lang) ? 1 : 0);
        langs.onPick(i -> setLang(i == 1 ? "en" : "ko"));
        bar.addView(langs);
        return bar;
    }

    static android.graphics.drawable.Drawable pressable(int pressColor, float radiusDp) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, N.rect(pressColor, 0, radiusDp));
        s.addState(new int[0], N.rect(0, 0, radiusDp));
        return s;
    }

    /** 상태 → 화면 반영 (UI 스레드) */
    void render() {
        if (root == null) return;
        boolean isPing = "ping".equals(screen);
        titleView.setText(isPing ? t("mPing") : t("title"));
        subView.setText(adapter == Ad.UP
                ? (ipState == IpSt.OK ? ip.trim() : "—") + " → " + devIp.trim() + ":" + devPort.trim() + " · Unit " + unit.trim()
                : t("adNone"));
        if (isPing) pingScreen.refresh();
        else mainScreen.refresh();
        if (drawer) renderDrawer();
    }

    void scrollMainTop() {
        if (mainScroll != null) mainScroll.smoothScrollTo(0, 0);
    }

    // ------------------------------------------------------------------ toast
    private void renderToast() {
        toastView.removeAllViews();
        if (toastKey == null) return;
        LinearLayout box = N.row(this, 10);
        box.setPadding(N.dp(12), N.dp(10), N.dp(12), N.dp(10));
        box.setBackground(N.rect(N.NEUTRAL_800, N.NEUTRAL_700, N.RADIUS_MD));
        box.setElevation(N.dp(6));
        box.addView(N.text(this, t(toastKey), 13, N.TEXT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (toastAction != null) {
            String action = toastAction;
            box.addView(N.button(this, N.Btn.GHOST, null, t("setup")).onClick(v -> {
                toastKey = null;
                renderToast();
                if (!"main".equals(screen)) go("main");
                open.put(action, true);
                render();
                scrollMainTop();
            }));
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int lift = N.dp(40);
        if (bannerSlot != null && bannerSlot.view().getVisibility() == View.VISIBLE) {
            int h = bannerSlot.view().getHeight();
            if (h > 0) lift = h + N.dp(12);
        }
        lp.setMargins(N.dp(16), 0, N.dp(16), lift);
        toastView.addView(box, lp);
    }

    // ------------------------------------------------------------------ drawer
    private void renderDrawer() {
        drawerView.removeAllViews();
        if (!drawer) return;
        View scrim = new View(this);
        scrim.setBackgroundColor(N.SCRIM);
        scrim.setOnClickListener(v -> {
            drawer = false;
            renderDrawer();
        });
        drawerView.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = N.col(this, 4);
        panel.setPadding(N.dp(12), N.dp(48), N.dp(12), N.dp(16));
        android.graphics.drawable.GradientDrawable bg = N.rect(N.SURFACE, N.NEUTRAL_500, 0);
        float r = N.dp(N.RADIUS_LG);
        bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        panel.setBackground(bg);
        panel.setElevation(N.dp(16));
        panel.setClickable(true);

        LinearLayout head = N.col(this, 2);
        head.setPadding(N.dp(12), 0, N.dp(12), N.dp(14));
        head.addView(N.mediumText(this, "Modbus Tool", 17, N.TEXT));
        String sub = adapter == Ad.UP ? "USB · " + (ipState == IpSt.OK ? ip.trim() + "/" + prefix.trim() : t("ipNotSet")) : t("adNone");
        head.addView(N.monoText(this, sub, 11, N.NEUTRAL_500));
        panel.addView(head);

        String[][] menu = {{"main", t("mMain"), N.I_PLUGS, polling ? t("polling") : ""},
                {"ping", t("mPing"), N.I_PULSE, pingRunning ? t("running") : ""}};
        for (String[] m : menu) {
            boolean on = m[0].equals(screen);
            LinearLayout item = N.row(this, 12);
            item.setPadding(N.dp(12), 0, N.dp(12), 0);
            item.setMinimumHeight(N.dp(48));
            item.setBackground(on ? N.rect(N.ACCENT_900, 0, N.RADIUS_MD) : pressable(N.TEXT_7, N.RADIUS_MD));
            int color = on ? N.ACCENT_300 : N.NEUTRAL_200;
            item.addView(N.icon(this, m[2], 20, color));
            item.addView(N.mediumText(this, m[1], 14, color), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (!m[3].isEmpty()) item.addView(N.tag(this, m[3]));
            String target = m[0];
            item.setOnClickListener(v -> go(target));
            panel.addView(item, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, N.dp(48)));
        }
        drawerView.addView(panel, new FrameLayout.LayoutParams(N.dp(280), ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ------------------------------------------------------------------ write sheet
    private void renderSheet() {
        sheetView.removeAllViews();
        if (sheet == null) return;
        View scrim = new View(this);
        scrim.setBackgroundColor(N.SCRIM);
        scrim.setOnClickListener(v -> {
            sheet = null;
            renderSheet();
        });
        sheetView.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout p = N.col(this, 12);
        p.setPadding(N.dp(16), N.dp(10), N.dp(16), N.dp(28));
        android.graphics.drawable.GradientDrawable bg = N.rect(N.SURFACE, N.NEUTRAL_500, 0);
        float r = N.dp(N.RADIUS_LG);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        p.setBackground(bg);
        p.setElevation(N.dp(16));
        p.setClickable(true);

        View handle = new View(this);
        handle.setBackground(N.rect(N.NEUTRAL_700, 0, 2));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(N.dp(36), N.dp(4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        p.addView(handle, hl);

        LinearLayout titles = N.col(this, 3);
        titles.addView(N.mediumText(this, t("writeReg"), 17, N.TEXT));
        String wtype = sheet.type + (w(sheet.type) == 2 ? " " + sheet.order : "");
        titles.addView(N.tnum(N.text(this, t("addr") + " " + sheet.addr + " · " + (40001 + sheet.addr) + " · " + wtype, 12, N.NEUTRAL_500)));
        p.addView(titles);

        TextView preview = N.monoText(this, sheetPreview(), 11, N.NEUTRAL_500);
        EditText in = N.input(this, sheetVal, false);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        in.setTextSize(16);
        in.setMinHeight(N.dp(44));
        in.addTextChangedListener(new Watch(s -> {
            sheetVal = s;
            preview.setText(sheetPreview());
        }));
        p.addView(N.field(this, t("newVal") + " (" + sheet.type + ")", in));
        p.addView(preview);

        N.Button cancel = N.button(this, N.Btn.SECONDARY, null, t("cancel")).onClick(v -> {
            sheet = null;
            renderSheet();
        });
        N.Button send = N.button(this, N.Btn.PRIMARY, null, t("send") + " · " + (w(sheet.type) == 2 ? "FC16" : "FC06"))
                .onClick(v -> submitSheet());
        LinearLayout btns = N.equal(this, 8, cancel, send);
        p.addView(btns);
        cancel.getLayoutParams().height = N.dp(44);
        send.getLayoutParams().height = N.dp(44);

        sheetView.addView(p, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        in.requestFocus();
    }

    /** 간단한 TextWatcher */
    static final class Watch implements android.text.TextWatcher {
        interface On {
            void changed(String s);
        }

        private final On on;

        Watch(On on) {
            this.on = on;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
            on.changed(s.toString());
        }
    }
}
