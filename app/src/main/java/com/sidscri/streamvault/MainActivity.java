package com.sidscri.streamvault;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 1001;
    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> fileUploadCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.parseColor("#0a0a0f"));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1002);
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0a0a0f"));
        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setVisibility(View.GONE);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        webView = new WebView(this);
        setupWebView();
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(fullscreenContainer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setDatabaseEnabled(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setUserAgentString(ws.getUserAgentString() + " StreamVault/4.3");
        ws.setAllowUniversalAccessFromFileURLs(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new NativeBridge(), "NativePlayer");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { v.loadUrl(r.getUrl().toString()); return true; }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileUploadCallback != null) fileUploadCallback.onReceiveValue(null);
                fileUploadCallback = cb;
                try { startActivityForResult(p.createIntent(), FILE_CHOOSER_REQUEST); }
                catch (Exception e) {
                    Intent f = new Intent(Intent.ACTION_GET_CONTENT); f.setType("*/*"); f.addCategory(Intent.CATEGORY_OPENABLE);
                    try { startActivityForResult(Intent.createChooser(f, "Choose file"), FILE_CHOOSER_REQUEST); } catch (Exception ex) { fileUploadCallback = null; return false; }
                }
                return true;
            }
            @Override public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v; customViewCallback = cb; fullscreenContainer.addView(customView);
                fullscreenContainer.setVisibility(View.VISIBLE); webView.setVisibility(View.GONE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
            }
            @Override public void onHideCustomView() {
                if (customView == null) return;
                fullscreenContainer.removeView(customView); fullscreenContainer.setVisibility(View.GONE); webView.setVisibility(View.VISIBLE);
                if (customViewCallback != null) customViewCallback.onCustomViewHidden();
                customView = null; customViewCallback = null;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        });
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    /**
     * JS bridge — EXACTLY 6 String params. Same signature as Build 11 that worked.
     * NO overloading. NO parameter count changes.
     */
    public class NativeBridge {
        @JavascriptInterface
        public boolean isAvailable() { return true; }

        @JavascriptInterface
        public void playStream(String failoverJson, String title, String category, String nowNext, String foTimeoutStr, String foAutoStr) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_FAILOVER_JSON, failoverJson != null ? failoverJson : "[]");
                i.putExtra(PlayerActivity.EXTRA_TITLE, title != null ? title : "Stream");
                i.putExtra(PlayerActivity.EXTRA_CATEGORY, category != null ? category : "");
                i.putExtra(PlayerActivity.EXTRA_NOW_NEXT, nowNext != null ? nowNext : "");
                int t = 15; try { t = Integer.parseInt(foTimeoutStr); } catch (Exception e) {}
                i.putExtra(PlayerActivity.EXTRA_FO_TIMEOUT, t);
                i.putExtra(PlayerActivity.EXTRA_FO_AUTO, !"false".equalsIgnoreCase(foAutoStr));
                startActivity(i);
            });
        }

        /** Save settings from JS to SharedPreferences (save path, buffer size) */
        @JavascriptInterface
        public void saveSetting(String key, String value) {
            SharedPreferences sp = getSharedPreferences("sv_prefs", MODE_PRIVATE);
            sp.edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public String getSetting(String key) {
            SharedPreferences sp = getSharedPreferences("sv_prefs", MODE_PRIVATE);
            return sp.getString(key, "");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == FILE_CHOOSER_REQUEST && fileUploadCallback != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getDataString() != null) r = new Uri[]{Uri.parse(data.getDataString())};
            fileUploadCallback.onReceiveValue(r); fileUploadCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (customView != null) {
            if (customViewCallback != null) customViewCallback.onCustomViewHidden();
            fullscreenContainer.removeView(customView); fullscreenContainer.setVisibility(View.GONE); webView.setVisibility(View.VISIBLE);
            customView = null; customViewCallback = null; return;
        }
        webView.evaluateJavascript("(function(){var p=document.getElementById('player-scr');if(p&&p.classList.contains('on')){document.getElementById('p-back').click();return 'h'}var b=document.getElementById('br-scr');if(b&&b.classList.contains('on')){document.getElementById('br-back').click();return 'h'}return 'x'})()",
            v -> { if (v != null && v.contains("x")) finish(); });
    }

    @Override protected void onResume() { super.onResume(); if (webView != null) webView.onResume(); }
    @Override protected void onPause() { if (webView != null) webView.onPause(); super.onPause(); }
    @Override protected void onDestroy() { if (webView != null) { webView.stopLoading(); webView.destroy(); } super.onDestroy(); }
}
