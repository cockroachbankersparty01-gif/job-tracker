package com.sarkarijobtracker.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sarkari Job Tracker.
 * Everything updates by itself:
 *  - Each time the app starts, it downloads the latest screens (index.html) from the GitHub repo,
 *    so design changes reach every phone without reinstalling. If offline, it uses the last
 *    downloaded copy, or the copy built into the APK.
 *  - The page then downloads the latest job data (data.json) on every open.
 *  - When the app comes back from the background it fetches fresh data again; after 30 minutes
 *    away it reloads everything.
 */
public class MainActivity extends Activity {

    private static final String BASE_URL =
            "https://raw.githubusercontent.com/cockroachbankersparty01-gif/job-tracker/main/";
    private static final long FULL_RELOAD_AFTER_MS = 30 * 60 * 1000L;

    private WebView web;
    private long pausedAt = 0;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#1b2340"));

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // keeps the candidate's profile between visits
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // "Official site" and result links open in the phone's browser
                Uri uri = request.getUrl();
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        loadLatest();
    }

    /** Download the newest screens in the background, then show them (or a saved copy if offline). */
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
        super.onPause();
        pausedAt = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pausedAt == 0) return; // first start is handled by onCreate
        long away = SystemClock.elapsedRealtime() - pausedAt;
        if (away > FULL_RELOAD_AFTER_MS) {
            loadLatest();                       // new screens + new data
        } else {
            web.evaluateJavascript("window.appResumed && window.appResumed()", null); // new data
        }
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
                    + "<h2>No internet connection</h2><p>Connect to the internet and reopen Job Tracker.</p></body></html>";
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

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
