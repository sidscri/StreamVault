package com.sidscri.streamvault;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
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
    private static final int PERM_REQUEST = 1002;
    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> fileUploadCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.parseColor("#0a0a0f"));
        }

        // Request storage permission for recording
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERM_REQUEST);
            }
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
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true); s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true); s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " StreamVault/4.0");
        s.setAllowUniversalAccessFromFileURLs(true);

        CookieManager cm = CookieManager.getInstance(); cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new StreamBridge(), "NativePlayer");

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { v.loadUrl(r.getUrl().toString()); return true; }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileUploadCallback != null) fileUploadCallback.onReceiveValue(null);
                fileUploadCallback = cb;
                try { startActivityForResult(p.createIntent(), FILE_CHOOSER_REQUEST); }
                catch (Exception e) { Intent f = new Intent(Intent.ACTION_GET_CONTENT); f.setType("*/*"); f.addCategory(Intent.CATEGORY_OPENABLE);
                    try { startActivityForResult(Intent.createChooser(f, "Choose file"), FILE_CHOOSER_REQUEST); } catch (Exception ex) { fileUploadCallback = null; return false; } }
                return true;
            }
            @Override public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v; customViewCallback = cb; fullscreenContainer.addView(customView);
                fullscreenContainer.setVisibility(View.VISIBLE); webView.setVisibility(View.GONE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
            }
            @Override public void onHideCustomView() {
                if (customView == null) return; fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE); webView.setVisibility(View.VISIBLE);
                customViewCallback.onCustomViewHidden(); customView = null; customViewCallback = null;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        });
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    public class StreamBridge {
        @JavascriptInterface public boolean isAvailable() { return true; }

        @JavascriptInterface
        public void play(String url, String title, String category) {
            runOnUiThread(() -> { Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_URL, url); i.putExtra(PlayerActivity.EXTRA_TITLE, title);
                i.putExtra(PlayerActivity.EXTRA_CATEGORY, category); startActivity(i); });
        }

        @JavascriptInterface
        public void playWithFailover(String json, String title, String cat, String nn) {
            playWithFailover(json, title, cat, nn, 15, true);
        }

        @JavascriptInterface
        public void playWithFailover(String json, String title, String cat, String nn, int foTimeout, boolean foAuto) {
            runOnUiThread(() -> { Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_FAILOVER_JSON, json);
                i.putExtra(PlayerActivity.EXTRA_TITLE, title);
                i.putExtra(PlayerActivity.EXTRA_CATEGORY, cat);
                i.putExtra(PlayerActivity.EXTRA_NOW_NEXT, nn);
                i.putExtra(PlayerActivity.EXTRA_FO_TIMEOUT, foTimeout);
                i.putExtra(PlayerActivity.EXTRA_FO_AUTO, foAuto);
                startActivity(i); });
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == FILE_CHOOSER_REQUEST && fileUploadCallback != null) {
            Uri[] r = null; if (res == RESULT_OK && data != null && data.getDataString() != null) r = new Uri[]{Uri.parse(data.getDataString())};
            fileUploadCallback.onReceiveValue(r); fileUploadCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (customView != null) { customViewCallback.onCustomViewHidden(); fullscreenContainer.removeView(customView); fullscreenContainer.setVisibility(View.GONE); webView.setVisibility(View.VISIBLE); customView = null; customViewCallback = null; return; }
        webView.evaluateJavascript("(function(){var p=document.getElementById('player-screen');if(p&&p.classList.contains('active')){document.getElementById('back-btn').click();return 'h'}return 'x'})()", v -> { if (v != null && v.contains("x")) finish(); });
    }

    @Override protected void onResume() { super.onResume(); webView.onResume(); }
    @Override protected void onPause() { webView.onPause(); super.onPause(); }
    @Override protected void onDestroy() { if (webView != null) { webView.stopLoading(); webView.destroy(); } super.onDestroy(); }
}
