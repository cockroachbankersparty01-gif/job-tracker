package com.sarkarijobtracker.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Sarkari Job Tracker.
 * The app's screens (index.html) ship inside the APK. They are loaded with the GitHub
 * repository's raw-file address as their base, so the page's "data.json" request always
 * downloads the latest daily job data. New listings never need a new APK.
 */
public class MainActivity extends Activity {

    private static final String BASE_URL =
            "https://raw.githubusercontent.com/cockroachbankersparty01-gif/job-tracker/main/";

    private WebView web;

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

        loadApp();
    }

    private void loadApp() {
        String html;
        try (InputStream in = getAssets().open("index.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            html = out.toString("UTF-8");
        } catch (IOException e) {
            html = "<!DOCTYPE html><html><body style='font-family:sans-serif;padding:24px'>"
                    + "<h2>Something went wrong</h2><p>Please reinstall Job Tracker.</p></body></html>";
        }
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null);
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
