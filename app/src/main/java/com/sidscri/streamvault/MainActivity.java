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
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.parseColor("#0a0a0f"));
        }

        // Storage permission for recording (Android 9 and below)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1002);
            }
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0a0a0f"));

        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setVisibility(View.GONE);
        fullscreenContainer.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        setupWebView();

        root.addView(webView,
            new FrameLayout.LayoutParams(-1, -1));
        root.addView(fullscreenContainer,
            new FrameLayout.LayoutParams(-1, -1));

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
        ws.setUserAgentString(ws.getUserAgentString() + " StreamVault/4.1");
        // Allow cross-origin fetch for EPG XML files
        ws.setAllowUniversalAccessFromFileURLs(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        // JavaScript → Native bridge
        webView.addJavascriptInterface(new NativeBridge(), "NativePlayer");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // File chooser — ALWAYS use custom intent to avoid Android
            // greying out .m3u files due to unknown MIME type
            @Override
            public boolean onShowFileChooser(WebView webView,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams) {

                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                try {
                    // Don't use fileChooserParams.createIntent() — it filters
                    // by MIME type and greys out .m3u files on many devices
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    // Also try ACTION_OPEN_DOCUMENT as fallback
                    Intent chooser = Intent.createChooser(intent, "Choose file");
                    startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
                return true;
            }

            // Fullscreen video
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(customView);
                fullscreenContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                }
                customView = null;
                customViewCallback = null;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        });

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    /**
     * JavaScript bridge. IMPORTANT: No method overloading!
     * Android WebView does NOT support overloaded @JavascriptInterface methods.
     */
    public class NativeBridge {

        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        @JavascriptInterface
        public void saveSetting(String key, String value) {
            try {
                getSharedPreferences("streamvault", MODE_PRIVATE)
                    .edit()
                    .putString(key != null ? key : "", value != null ? value : "")
                    .apply();
            } catch (Exception e) {
                // no-op to keep bridge safe
            }
        }

        /**
         * Single entry point for playback. ALL parameters are Strings
         * to avoid WebView type conversion issues.
         */
        @JavascriptInterface
        public void playStream(String failoverJson, String title,
            String category, String nowNext,
            String foTimeoutStr, String foAutoStr, String savePathStr) {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(MainActivity.this, PlayerActivity.class);

                    String safeJson = failoverJson != null ? failoverJson : "[]";
                    SharedPreferences runtimePrefs = getSharedPreferences("streamvault_runtime", MODE_PRIVATE);
                    runtimePrefs.edit().putString("pending_failover_json", safeJson).apply();
                    // Keep the Intent payload tiny to avoid TransactionTooLarge crashes.
                    intent.putExtra(PlayerActivity.EXTRA_FAILOVER_JSON, "");

                    intent.putExtra(PlayerActivity.EXTRA_TITLE,
                        title != null ? title : "Stream");
                    intent.putExtra(PlayerActivity.EXTRA_CATEGORY,
                        category != null ? category : "");
                    intent.putExtra(PlayerActivity.EXTRA_NOW_NEXT,
                        nowNext != null ? nowNext : "");

                    int timeout = 15;
                    try { timeout = Integer.parseInt(foTimeoutStr); } catch (Exception e) {}
                    intent.putExtra(PlayerActivity.EXTRA_FO_TIMEOUT, timeout);

                    boolean autoFo = !"false".equalsIgnoreCase(foAutoStr);
                    intent.putExtra(PlayerActivity.EXTRA_FO_AUTO, autoFo);

                    if (savePathStr != null && !savePathStr.isEmpty()) {
                        intent.putExtra(PlayerActivity.EXTRA_SAVE_PATH, savePathStr);
                    }

                    startActivity(intent);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "Stream open failed: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileUploadCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
            }
            fullscreenContainer.removeView(customView);
            fullscreenContainer.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            customView = null;
            customViewCallback = null;
            return;
        }

        webView.evaluateJavascript(
            "(function(){" +
            "var p=document.getElementById('player-scr');" +
            "if(p&&p.classList.contains('on')){" +
            "document.getElementById('p-back').click();" +
            "return 'handled'}" +
            "var b=document.getElementById('br-scr');" +
            "if(b&&b.classList.contains('on')){" +
            "document.getElementById('br-back').click();" +
            "return 'handled'}" +
            "return 'exit'})()",
            value -> {
                if (value != null && value.contains("exit")) {
                    finish();
                }
            }
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
