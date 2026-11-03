package com.emsfield.fieldtester;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.emsfield.fieldtester.ui.N;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;

/**
 * 메인 화면 하단의 고정 적응형 배너 하나.
 * <p>
 * 적합한 LTE/Wi-Fi 가 확인된 뒤에만 UMP 동의 → {@link MobileAds#initialize} → 로드 순서다.
 * 동의 실패, 오프라인, 로드 실패는 배너를 숨길 뿐 앱 동작을 바꾸지 않는다.
 * 전면·앱 오픈·보상형 광고는 만들지 않는다.
 */
final class BannerSlot {
    private static final String TAG = "BannerSlot";

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AdNetworkGuard guard;
    private final LinearLayout container;
    private final FrameLayout holder;

    private AdView adView;
    private ConsentInformation consentInfo;
    private Runnable visibilityListener;

    private boolean started;
    private boolean destroyed;
    private boolean suitable;
    private boolean hostResumed;
    private boolean onMain = true;
    private boolean busy;

    private int epoch;
    private boolean consentInFlight;
    private boolean consentFailed;
    private boolean adsAllowed;
    private boolean initStarted;
    private boolean initDone;
    private boolean loadInFlight;
    private boolean loadFailed;
    private boolean adLoaded;

    BannerSlot(Activity activity, android.net.ConnectivityManager cm) {
        this.activity = activity;
        guard = new AdNetworkGuard(cm, new AdNetworkGuard.Listener() {
            @Override
            public void onSuitable() {
                onNetworkSuitable();
            }

            @Override
            public void onUnsuitable() {
                onNetworkUnsuitable();
            }
        });

        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(N.BG);
        container.setVisibility(View.GONE);

        View divider = new View(activity);
        divider.setBackgroundColor(N.DIVIDER);
        container.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, N.dp(1))));

        holder = new FrameLayout(activity);
        holder.setBackgroundColor(N.BG);
        container.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    View view() {
        return container;
    }

    void setVisibilityListener(Runnable listener) {
        visibilityListener = listener;
    }

    /** 내비게이션 바 위로 배너를 올린다. 배너가 GONE 이면 레이아웃 높이에 영향 없다. */
    void setBottomInset(int px) {
        if (container.getPaddingBottom() != px) container.setPadding(0, 0, 0, px);
    }

    void start() {
        if (started || destroyed) return;
        started = true;
        try {
            guard.start();
        } catch (RuntimeException e) {
            Log.w(TAG, "network guard failed", e);
            hideSlot();
        }
    }

    void onHostResume() {
        hostResumed = true;
        if (destroyed) return;
        if (suitable && consentFailed && !consentInFlight && !adsAllowed) {
            consentFailed = false;
            startConsent();
        } else if (suitable && adsAllowed && initDone && adView == null) {
            loadFailed = false;
            ensureLoaded();
        }
        applyRunState();
    }

    void onHostPause() {
        hostResumed = false;
        applyRunState();
    }

    /** 폴링, Modbus 쓰기, Ping 테스트 동안 갱신을 멈춘다. 배너 자체는 메인 화면에 남겨 둔다. */
    void setBusy(boolean busy) {
        this.busy = busy;
        applyRunState();
    }

    void setOnMainScreen(boolean main) {
        if (onMain == main) {
            applyRunState();
            return;
        }
        onMain = main;
        applyRunState();
        applyVisibility();
    }

    void destroy() {
        if (destroyed) return;
        destroyed = true;
        epoch++;
        destroyAd();
        hideSlot();
        try {
            guard.stop();
        } catch (RuntimeException e) {
            Log.w(TAG, "guard stop", e);
        }
        ui.removeCallbacksAndMessages(null);
    }

    private void onNetworkSuitable() {
        if (destroyed) return;
        suitable = true;
        if (!adsAllowed) {
            if (!consentInFlight && !consentFailed) startConsent();
            return;
        }
        if (!initDone) {
            if (!initStarted) startInit();
            return;
        }
        ensureLoaded();
    }

    private void onNetworkUnsuitable() {
        if (destroyed) return;
        epoch++;
        suitable = false;
        consentInFlight = false;
        consentFailed = false;
        loadInFlight = false;
        loadFailed = false;
        destroyAd();
        hideSlot();
    }

    private void startConsent() {
        if (destroyed || !suitable || activity.isFinishing()) return;
        final int token = epoch;
        consentInFlight = true;
        try {
            ConsentRequestParameters params = new ConsentRequestParameters.Builder()
                    .setTagForUnderAgeOfConsent(false)
                    .build();
            consentInfo = UserMessagingPlatform.getConsentInformation(activity);
            consentInfo.requestConsentInfoUpdate(
                    activity,
                    params,
                    () -> ui.post(() -> onConsentInfo(token)),
                    error -> ui.post(() -> onConsentDenied(token, error)));
        } catch (RuntimeException e) {
            consentInFlight = false;
            consentFailed = true;
            Log.w(TAG, "consent update failed", e);
            hideSlot();
        }
    }

    private void onConsentInfo(int token) {
        if (!current(token)) return;
        try {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                    activity, error -> ui.post(() -> onFormFinished(token, error)));
        } catch (RuntimeException e) {
            consentInFlight = false;
            consentFailed = true;
            Log.w(TAG, "consent form failed", e);
            hideSlot();
        }
    }

    private void onConsentDenied(int token, FormError error) {
        if (!current(token)) return;
        consentInFlight = false;
        // 이전 세션의 동의가 남아 있으면 그 값으로 진행한다.
        if (canRequest()) {
            adsAllowed = true;
            startInit();
            return;
        }
        consentFailed = true;
        Log.i(TAG, "consent unavailable: " + message(error));
        hideSlot();
    }

    private void onFormFinished(int token, FormError error) {
        if (!current(token)) return;
        consentInFlight = false;
        if (!canRequest()) {
            consentFailed = true;
            Log.i(TAG, "ads not allowed after consent: " + message(error));
            hideSlot();
            return;
        }
        if (error != null) Log.i(TAG, "consent form: " + error.getMessage());
        adsAllowed = true;
        startInit();
    }

    private boolean canRequest() {
        try {
            return consentInfo != null && consentInfo.canRequestAds();
        } catch (RuntimeException e) {
            Log.w(TAG, "canRequestAds", e);
            return false;
        }
    }

    private void startInit() {
        if (destroyed || initStarted || !suitable || !adsAllowed) return;
        initStarted = true;
        android.content.Context app = activity.getApplicationContext();
        Thread thread = new Thread(() -> {
            try {
                MobileAds.initialize(app, status -> ui.post(this::onInitialized));
            } catch (Exception e) {
                ui.post(() -> {
                    initStarted = false;
                    Log.w(TAG, "MobileAds.initialize failed", e);
                    hideSlot();
                });
            }
        }, "ads-init");
        thread.setDaemon(true);
        thread.start();
    }

    private void onInitialized() {
        initDone = true;
        if (destroyed || !suitable || !adsAllowed) return;
        ensureLoaded();
    }

    private void ensureLoaded() {
        if (destroyed || !suitable || !adsAllowed || !initDone || activity.isFinishing()) return;
        if (adView != null || loadInFlight || loadFailed) {
            applyRunState();
            applyVisibility();
            return;
        }
        loadInFlight = true;
        try {
            AdView view = new AdView(activity);
            view.setAdUnitId(activity.getString(R.string.admob_banner_unit_id));
            view.setBackgroundColor(N.BG);
            view.setAdSize(anchoredSize(widthDp()));
            view.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded() {
                    if (destroyed || adView != view || !suitable) {
                        destroyQuietly(view);
                        return;
                    }
                    loadInFlight = false;
                    loadFailed = false;
                    adLoaded = true;
                    applyRunState();
                    applyVisibility();
                }

                @Override
                public void onAdFailedToLoad(LoadAdError error) {
                    Log.i(TAG, "banner failed: " + error.getCode() + " " + error.getMessage());
                    loadInFlight = false;
                    loadFailed = true;
                    destroyAd();
                    hideSlot();
                }
            });
            holder.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL));
            adView = view;
            applyRunState();
            view.loadAd(new AdRequest.Builder().build());
        } catch (RuntimeException e) {
            loadInFlight = false;
            loadFailed = true;
            Log.w(TAG, "banner load failed", e);
            destroyAd();
            hideSlot();
        }
    }

    /**
     * v25 는 더 큰 large anchored(동영상) 크기를 권한다.
     * 현장 화면을 가리지 않도록 짧은 적응형 배너를 유지한다.
     */
    @SuppressWarnings("deprecation")
    private AdSize anchoredSize(int widthDp) {
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp);
    }

    private int widthDp() {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        return Math.max(1, Math.round(dm.widthPixels / dm.density));
    }

    private void applyRunState() {
        if (adView == null) return;
        try {
            if (hostResumed && onMain && !busy) adView.resume();
            else adView.pause();
        } catch (RuntimeException e) {
            Log.w(TAG, "pause/resume failed", e);
        }
    }

    private void applyVisibility() {
        if (onMain && adLoaded && suitable) showSlot();
        else hideSlot();
    }

    private void showSlot() {
        if (container.getVisibility() == View.VISIBLE) return;
        container.setVisibility(View.VISIBLE);
        container.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (bottom - top <= 0) return;
                v.removeOnLayoutChangeListener(this);
                notifyVisibility();
            }
        });
        notifyVisibility();
    }

    private void hideSlot() {
        if (container.getVisibility() == View.GONE) return;
        container.setVisibility(View.GONE);
        notifyVisibility();
    }

    private void notifyVisibility() {
        if (visibilityListener == null) return;
        container.post(visibilityListener);
    }

    private void destroyAd() {
        adLoaded = false;
        AdView view = adView;
        adView = null;
        destroyQuietly(view);
        if (view != null) holder.removeView(view);
    }

    private static void destroyQuietly(AdView view) {
        if (view == null) return;
        try {
            view.setAdListener(null);
            view.destroy();
        } catch (RuntimeException e) {
            Log.w(TAG, "ad destroy", e);
        }
    }

    private boolean current(int token) {
        return !destroyed && token == epoch && suitable;
    }

    private static String message(FormError error) {
        return error == null ? "none" : error.getErrorCode() + " " + error.getMessage();
    }
}
