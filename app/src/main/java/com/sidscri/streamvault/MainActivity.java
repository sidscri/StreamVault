package com.sidscri.streamvault;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
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
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.parseColor("#0a0a0f"));
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0a0a0f"));

        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setVisibility(View.GONE);
        fullscreenContainer.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        setupWebView();

        root.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(fullscreenContainer, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setUserAgentString(settings.getUserAgentString() + " StreamVault/3.0");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        // JavaScript → Native bridge
        webView.addJavascriptInterface(new StreamBridge(), "NativePlayer");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            // File chooser for import
            @Override
            public boolean onShowFileChooser(WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {

                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                    fallback.setType("*/*");
                    fallback.addCategory(Intent.CATEGORY_OPENABLE);
                    try {
                        startActivityForResult(
                            Intent.createChooser(fallback, "Choose file"),
                            FILE_CHOOSER_REQUEST);
                    } catch (Exception ex) {
                        fileUploadCallback = null;
                        return false;
                    }
                }
                return true;
            }

            // Fullscreen video
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(customView);
                fullscreenContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                hideSystemUI();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                customViewCallback.onCustomViewHidden();
                customView = null;
                customViewCallback = null;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                showSystemUI();
            }
        });

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    /**
     * JavaScript bridge — called from index.html to launch native ExoPlayer
     */
    public class StreamBridge {

        @JavascriptInterface
        public void play(String url, String title, String category) {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_URL, url);
                intent.putExtra(PlayerActivity.EXTRA_TITLE, title);
                intent.putExtra(PlayerActivity.EXTRA_CATEGORY, category);
                startActivity(intent);
            });
        }

        @JavascriptInterface
        public void playWithFailover(String failoverJson, String title, String category) {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_FAILOVER_JSON, failoverJson);
                intent.putExtra(PlayerActivity.EXTRA_TITLE, title);
                intent.putExtra(PlayerActivity.EXTRA_CATEGORY, category);
                startActivity(intent);
            });
        }

        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileUploadCallback != null) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String s = data.getDataString();
                if (s != null) results = new Uri[]{Uri.parse(s)};
            }
            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
        }
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                customView = null;
                customViewCallback = null;
            }
            return;
        }
        webView.evaluateJavascript(
            "(function(){var p=document.getElementById('player-screen');" +
            "if(p&&p.classList.contains('active')){document.getElementById('back-btn').click();return 'handled'}" +
            "return 'exit'})()",
            value -> { if (value != null && value.contains("exit")) finish(); });
    }

    @Override protected void onResume() { super.onResume(); webView.onResume(); }
    @Override protected void onPause() { webView.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }
}
