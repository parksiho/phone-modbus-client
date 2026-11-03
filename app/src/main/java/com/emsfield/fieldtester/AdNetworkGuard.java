package com.emsfield.fieldtester;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 광고 소켓을 LTE/셀룰러 또는 Wi-Fi 로만 내보낸다.
 * <p>
 * 기본 네트워크가 그 조건(인터넷 + VALIDATED)을 만족하면 그대로 쓴다.
 * 기본 네트워크가 이더넷이면(OS 가 USB 어댑터를 먼저 잡은 경우) 셀룰러/Wi-Fi 를
 * 요청해 {@link ConnectivityManager#bindProcessToNetwork} 로 프로세스 소켓을 묶는다.
 * 조건에 맞는 네트워크가 없으면 광고를 요청하지 않는다.
 * <p>
 * 바인딩은 java.net 소켓에만 적용된다. 장비망은 {@link UsbCdcEcmFrameIO} 의 USB 벌크 전송이다.
 */
final class AdNetworkGuard {
    private static final String TAG = "AdNetworkGuard";
    private static final int REQUEST_TIMEOUT_MS = 20_000;
    private static final int MAX_BIND_FAILURES = 3;

    interface Listener {
        void onSuitable();

        void onUnsuitable();
    }

    private final ConnectivityManager cm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private boolean started;
    private boolean inEvaluate;
    private boolean again;
    private boolean reported;
    private boolean lastSuitable;
    private int bindFailures;

    /** 이더넷을 피하려고 우리가 묶은 네트워크. null 이면 프로세스 기본값을 쓴다. */
    private Network bound;
    /** requestNetwork 가 제안한 셀룰러/Wi-Fi. 검증 전이면 아직 묶지 않는다. */
    private Network offered;
    private ConnectivityManager.NetworkCallback requestCb;

    private final Runnable evaluateTask = this::evaluate;
    private final Runnable requestTimeout = () -> {
        if (!started || bound != null || offered != null) return;
        Log.i(TAG, "no cellular/wifi network appeared");
        cancelRequest();
    };

    private final ConnectivityManager.NetworkCallback defaultCb = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            schedule();
        }

        @Override
        public void onLost(Network network) {
            schedule();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            schedule();
        }
    };

    /** 기본 경로가 이더넷인 동안에도 셀룰러/Wi-Fi 가 생기는 것을 본다. */
    private final ConnectivityManager.NetworkCallback watchCb = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            // 이전 바인딩이 실패한 네트워크와 다른 망이 생기면 다시 시도한다.
            bindFailures = 0;
            schedule();
        }

        @Override
        public void onLost(Network network) {
            if (network.equals(offered)) offered = null;
            schedule();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            schedule();
        }
    };

    AdNetworkGuard(ConnectivityManager cm, Listener listener) {
        this.cm = cm;
        this.listener = listener;
    }

    void start() {
        if (started) return;
        started = true;
        cm.registerDefaultNetworkCallback(defaultCb, ui);
        cm.registerNetworkCallback(cellularOrWifi(false), watchCb, ui);
        schedule();
    }

    void stop() {
        started = false;
        ui.removeCallbacksAndMessages(null);
        cancelRequest();
        // 광고 뷰를 먼저 없앤 뒤에 풀어, 마지막 갱신이 이더넷으로 나가지 않게 한다.
        // 호출자가 이미 배너를 파괴한 상태다.
        releaseBind();
        unregister(defaultCb);
        unregister(watchCb);
    }

    private void schedule() {
        if (inEvaluate) {
            again = true;
            return;
        }
        ui.removeCallbacks(evaluateTask);
        ui.post(evaluateTask);
    }

    private void evaluate() {
        if (!started) return;
        inEvaluate = true;
        try {
            evaluateOnce();
        } finally {
            inEvaluate = false;
            if (again) {
                again = false;
                schedule();
            }
        }
    }

    private void evaluateOnce() {
        if (bound != null) {
            if (admissible(cm.getNetworkCapabilities(bound))) {
                setSuitable(true, "ads on bound cellular/wifi");
                return;
            }
            setSuitable(false, "bound cellular/wifi lost");
            releaseBind();
        }

        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        if (admissible(caps)) {
            cancelRequest();
            setSuitable(true, "ads on default " + describe(caps));
            return;
        }

        if (isEthernet(caps)) {
            if (offered != null && admissible(cm.getNetworkCapabilities(offered)) && bindOffered()) {
                setSuitable(true, "ads bound off ethernet onto " + describe(cm.getNetworkCapabilities(bound)));
                return;
            }
            setSuitable(false, "default is ethernet; waiting for cellular/wifi");
            requestAlternate();
            return;
        }

        cancelRequest();
        setSuitable(false, "no validated cellular/wifi (" + describe(caps) + ")");
    }

    /** 셀룰러 또는 Wi-Fi 이고, 인터넷이 있으며, VALIDATED 일 때만 광고 네트워크다. */
    static boolean admissible(NetworkCapabilities caps) {
        if (caps == null) return false;
        boolean cellOrWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        return cellOrWifi
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private static boolean isEthernet(NetworkCapabilities caps) {
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    private void requestAlternate() {
        if (requestCb != null || bindFailures >= MAX_BIND_FAILURES) return;
        requestCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                offered = network;
                ui.removeCallbacks(requestTimeout);
                schedule();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                offered = network;
                schedule();
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(offered)) offered = null;
                schedule();
            }
        };
        try {
            cm.requestNetwork(cellularOrWifi(true), requestCb, ui);
            ui.removeCallbacks(requestTimeout);
            ui.postDelayed(requestTimeout, REQUEST_TIMEOUT_MS);
        } catch (RuntimeException e) {
            requestCb = null;
            Log.w(TAG, "requestNetwork failed", e);
            setSuitable(false, "could not request cellular/wifi");
        }
    }

    /**
     * @param validated true 면 VALIDATED 인 네트워크만 받는다 (광고를 묶을 때).
     *                  전송 타입을 둘 이상 넣으면 그 중 하나면 된다.
     */
    private static NetworkRequest cellularOrWifi(boolean validated) {
        NetworkRequest.Builder b = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        if (validated) b.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        return b.build();
    }

    private boolean bindOffered() {
        Network network = offered;
        if (network == null || !admissible(cm.getNetworkCapabilities(network))) return false;
        try {
            if (!cm.bindProcessToNetwork(network)) {
                Log.w(TAG, "bindProcessToNetwork returned false");
                return false;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "bindProcessToNetwork failed", e);
            return false;
        }
        Network active = cm.getActiveNetwork();
        if (active == null || !active.equals(network)) {
            bindFailures++;
            Log.w(TAG, "process default did not switch off ethernet (" + bindFailures + ")");
            try {
                cm.bindProcessToNetwork(null);
            } catch (RuntimeException ignored) {
            }
            if (bindFailures >= MAX_BIND_FAILURES) {
                cancelRequest();
                offered = null;
            }
            return false;
        }
        bindFailures = 0;
        bound = network;
        ui.removeCallbacks(requestTimeout);
        return true;
    }

    private void releaseBind() {
        if (bound == null) return;
        try {
            cm.bindProcessToNetwork(null);
        } catch (RuntimeException e) {
            Log.w(TAG, "unbind failed", e);
        }
        bound = null;
    }

    private void cancelRequest() {
        ui.removeCallbacks(requestTimeout);
        if (requestCb != null) {
            unregister(requestCb);
            requestCb = null;
        }
        if (bound == null) offered = null;
    }

    private void unregister(ConnectivityManager.NetworkCallback cb) {
        try {
            cm.unregisterNetworkCallback(cb);
        } catch (RuntimeException ignored) {
            // 이미 해제됐거나 등록 전에 실패한 콜백.
        }
    }

    private void setSuitable(boolean ok, String reason) {
        if (reported && lastSuitable == ok) return;
        reported = true;
        lastSuitable = ok;
        Log.i(TAG, reason);
        try {
            if (ok) listener.onSuitable();
            else listener.onUnsuitable();
        } catch (RuntimeException e) {
            Log.w(TAG, "listener failed", e);
        }
    }

    private static String describe(NetworkCapabilities caps) {
        if (caps == null) return "none";
        StringBuilder sb = new StringBuilder();
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) sb.append("cellular ");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) sb.append("wifi ");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) sb.append("ethernet ");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) sb.append("vpn ");
        sb.append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "validated" : "not-validated");
        return sb.toString();
    }
}
