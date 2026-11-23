package com.sarkarijobtracker.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JOB Alert.
 *  - Each start downloads the latest screens (index.html) from the GitHub repo; offline it uses the
 *    last downloaded copy or the copy built into the APK. The page loads fresh data on every open.
 *  - Back from the background: fresh data; after 30 minutes away, a full reload.
 *  - A Google AdMob banner sits below the page. Consent is requested first where the law requires
 *    it (Google User Messaging Platform), and the page can reopen the ad-privacy choices.
 */
public class MainActivity extends Activity {

    private static final String BASE_URL = "https://raw.githubusercontent.com/cockroachbankersparty01-gif/job-tracker/main/";
    private static final int BRAND = Color.parseColor("#FFC81A");
    private static final boolean DARK_ICONS = true;
    private static final long FULL_RELOAD_AFTER_MS = 30 * 60 * 1000L;

    private WebView web;
    private FrameLayout adBox;
    private AdView adView;
    private ConsentInformation consent;
    private final AtomicBoolean adsStarted = new AtomicBoolean(false);
    private long pausedAt = 0;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BRAND); // shows behind the status bar

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adBox = new FrameLayout(this);
        adBox.setBackgroundColor(Color.WHITE);
        root.addView(adBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        setUpSystemBars(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // keeps the user's profile between visits
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // official sites, privacy policy etc. open in the phone's browser
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        loadLatest();
        requestConsentThenAds();
    }

    /** Android 15+ draws apps edge to edge; pad the content so nothing hides behind the system bars. */
    private void setUpSystemBars(View root) {
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(b.left, b.top, b.right, b.bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            getWindow().setStatusBarColor(BRAND);
        }
        if (DARK_ICONS) {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) c.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            } else {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }
    }

    /* ---------------- ads ---------------- */

    private void requestConsentThenAds() {
        consent = UserMessagingPlatform.getConsentInformation(this);
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
        consent.requestConsentInfoUpdate(this, params,
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(this, formError -> {
                    if (consent.canRequestAds()) startAds();
                }),
                requestError -> {
                    if (consent.canRequestAds()) startAds();
                });
        if (consent.canRequestAds()) startAds(); // consent already known from an earlier session
    }

    private void startAds() {
        if (!adsStarted.compareAndSet(false, true)) return;
        new Thread(() -> {
            MobileAds.initialize(this, status -> { });
            main.post(this::loadBanner);
        }).start();
    }

    private void loadBanner() {
        if (isFinishing()) return;
        float density = getResources().getDisplayMetrics().density;
        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels / density);
        adView = new AdView(this);
        adView.setAdUnitId(getString(R.string.admob_banner_id));
        adView.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, widthDp));
        adBox.removeAllViews();
        adBox.addView(adView);
        adView.loadAd(new AdRequest.Builder().build());
    }

    /** Lets the page show an "Ad privacy settings" link where the law requires one. */
    private class Bridge {
        @JavascriptInterface
        public boolean privacyOptionsRequired() {
            return consent != null && consent.getPrivacyOptionsRequirementStatus()
                    == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
        }

        @JavascriptInterface
        public void showPrivacyOptions() {
            main.post(() -> UserMessagingPlatform.showPrivacyOptionsForm(MainActivity.this, e -> { }));
        }
    }

    /* ---------------- page loading ---------------- */

    private void loadLatest() {
        new Thread(() -> {
            String html = download(BASE_URL + "index.html?t=" + System.currentTimeMillis());
            if (html != null && html.contains("<html")) {
                save(html);
            } else {
                html = readSaved();
                if (html == null) html = readAsset();
            }
            final String page = html;
            main.post(() -> web.loadDataWithBaseURL(BASE_URL, page, "text/html", "UTF-8", null));
        }).start();
    }

    @Override
    protected void onPause() {
        if (adView != null) adView.pause();
        super.onPause();
        pausedAt = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adView != null) adView.resume();
        if (pausedAt == 0) return; // first start is handled by onCreate
        long away = SystemClock.elapsedRealtime() - pausedAt;
        if (away > FULL_RELOAD_AFTER_MS) {
            loadLatest();
        } else {
            web.evaluateJavascript("window.appResumed && window.appResumed()", null);
        }
    }

    @Override
    protected void onDestroy() {
        if (adView != null) adView.destroy();
        super.onDestroy();
    }

    private String download(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private File savedFile() {
        return new File(getFilesDir(), "index.html");
    }

    private void save(String html) {
        try (FileOutputStream out = new FileOutputStream(savedFile())) {
            out.write(html.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private String readSaved() {
        File f = savedFile();
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    private String readAsset() {
        try (InputStream in = getAssets().open("index.html")) {
            return readAll(in);
        } catch (IOException e) {
            return "<!DOCTYPE html><html><body style='font-family:sans-serif;padding:24px'>"
                    + "<h2>No internet connection</h2><p>Connect to the internet and reopen JOB Alert.</p></body></html>";
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
