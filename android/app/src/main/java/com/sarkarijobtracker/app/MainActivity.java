package com.sarkarijobtracker.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Opens the Sarkari Job Tracker website inside the app.
 * All listings come from the website, so the app shows new jobs as soon as the
 * daily data update is published; the APK itself never needs updating for that.
 */
public class MainActivity extends Activity {

    private static final String SITE_HOST = "cockroachbankersparty01-gif.github.io";
    private static final String SITE_URL = "https://" + SITE_HOST + "/job-tracker/";

    private static final String OFFLINE_PAGE =
            "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>body{font-family:sans-serif;background:#f6f6f3;color:#1b2340;display:flex;align-items:center;"
            + "justify-content:center;height:100vh;margin:0;text-align:center;padding:24px;box-sizing:border-box}"
            + "button{background:#c2410c;color:#fff;border:0;border-radius:8px;padding:12px 22px;font-size:16px;margin-top:16px}"
            + "</style></head><body><div><h2>No internet connection</h2>"
            + "<p>Connect to mobile data or Wi-Fi to load the latest job listings.</p>"
            + "<button onclick=\"location.href='" + SITE_URL + "'\">Try again</button></div></body></html>";

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
                Uri uri = request.getUrl();
                if (SITE_HOST.equals(uri.getHost())) {
                    return false; // stay inside the app
                }
                // Official-site links open in the phone's browser
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadDataWithBaseURL(null, OFFLINE_PAGE, "text/html", "UTF-8", null);
                }
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(SITE_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
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
