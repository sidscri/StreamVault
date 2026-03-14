package com.sidscri.streamvault;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_TITLE = "stream_title";
    public static final String EXTRA_CATEGORY = "stream_category";
    public static final String EXTRA_FAILOVER_JSON = "failover_json";

    // Failover config
    private static final long FAILOVER_TIMEOUT_MS = 15000; // 15s before trying next
    private static final long BUFFER_GRACE_MS = 8000; // 8s buffering grace period

    private ExoPlayer player;
    private PlayerView playerView;
    private View loadingView;
    private TextView titleText, statusText, errorText;
    private View errorContainer, overlayTop, overlayCenter, overlayBottom;
    private ImageButton playPauseBtn, lockBtn;
    private Handler handler;
    private Runnable hideOverlayRunnable;
    private boolean overlayVisible = false;

    // Failover state
    private List<StreamVariant> variants = new ArrayList<>();
    private int currentVariantIdx = 0;
    private boolean locked = false;
    private boolean networkAvailable = true;
    private String lastSuccessUrl = null;
    private long playbackStartTime = 0;
    private Runnable failoverTimeout;
    private ConnectivityManager.NetworkCallback networkCallback;

    static class StreamVariant {
        String url, title, region, tag;
        StreamVariant(String url, String title, String region, String tag) {
            this.url = url; this.title = title; this.region = region; this.tag = tag;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        handler = new Handler(Looper.getMainLooper());
        hideOverlayRunnable = () -> setOverlayVisible(false);
        failoverTimeout = () -> {};

        parseIntent();
        buildUI();
        registerNetworkCallback();
        playVariant(0);
        hideSystemUI();
    }

    private void parseIntent() {
        String json = getIntent().getStringExtra(EXTRA_FAILOVER_JSON);
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    variants.add(new StreamVariant(
                        o.getString("url"),
                        o.optString("title", ""),
                        o.optString("region", ""),
                        o.optString("tag", "")
                    ));
                }
            } catch (Exception e) { /* fallback below */ }
        }
        // Fallback: single URL from old-style intent
        if (variants.isEmpty()) {
            String url = getIntent().getStringExtra(EXTRA_URL);
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            if (url != null) variants.add(new StreamVariant(url, title != null ? title : "Stream", "", ""));
        }
        if (variants.isEmpty()) { finish(); }
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setKeepScreenOn(true);
        root.addView(playerView, matchParent());

        // Loading
        loadingView = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        root.addView(loadingView, centered(dpToPx(44), dpToPx(44)));

        // Error
        errorContainer = buildErrorView();
        root.addView(errorContainer, centered(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        // Touch catcher
        View touch = new View(this);
        touch.setBackgroundColor(Color.TRANSPARENT);
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleOverlay(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (player != null) { if (player.isPlaying()) player.pause(); else player.play(); updatePlayPause(); }
                return true;
            }
        });
        touch.setOnTouchListener((v, e) -> { gd.onTouchEvent(e); return true; });
        root.addView(touch, matchParent());

        // Overlays
        overlayTop = buildTopOverlay();
        root.addView(overlayTop, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        overlayCenter = buildCenterOverlay();
        root.addView(overlayCenter, centered(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        overlayBottom = buildBottomOverlay();
        root.addView(overlayBottom, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setOverlayVisible(false);
        setContentView(root);
    }

    private View buildTopOverlay() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dpToPx(12), dpToPx(40), dpToPx(12), dpToPx(12));
        top.setBackgroundColor(Color.parseColor("#CC000000"));

        // Back
        ImageButton back = makeBtn(android.R.drawable.ic_media_previous, Color.TRANSPARENT);
        back.setOnClickListener(v -> finish());
        top.addView(back);

        // Title area
        LinearLayout ta = new LinearLayout(this);
        ta.setOrientation(LinearLayout.VERTICAL);
        ta.setPadding(dpToPx(8), 0, 0, 0);

        titleText = new TextView(this);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(14);
        titleText.setSingleLine(true);
        ta.addView(titleText);

        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#80ffffff"));
        statusText.setTextSize(10);
        statusText.setSingleLine(true);
        ta.addView(statusText);

        top.addView(ta, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        // Lock button
        lockBtn = makeBtn(android.R.drawable.ic_lock_idle_lock, Color.TRANSPARENT);
        lockBtn.setColorFilter(Color.parseColor("#80ffffff"));
        lockBtn.setOnClickListener(v -> {
            locked = !locked;
            lockBtn.setColorFilter(locked ? Color.parseColor("#ffa502") : Color.parseColor("#80ffffff"));
            showToast(locked ? "🔒 Locked to this version" : "🔓 Auto-failover enabled");
            scheduleHideOverlay();
        });
        top.addView(lockBtn);

        return top;
    }

    private View buildCenterOverlay() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER);

        playPauseBtn = new ImageButton(this);
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        playPauseBtn.setColorFilter(Color.WHITE);
        playPauseBtn.setBackgroundColor(Color.parseColor("#6c5ce7"));
        playPauseBtn.setPadding(dpToPx(18), dpToPx(18), dpToPx(18), dpToPx(18));
        playPauseBtn.setOnClickListener(v -> {
            if (player != null) { if (player.isPlaying()) player.pause(); else player.play(); updatePlayPause(); scheduleHideOverlay(); }
        });
        c.addView(playPauseBtn);
        return c;
    }

    private View buildBottomOverlay() {
        LinearLayout b = new LinearLayout(this);
        b.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(30));
        b.setBackgroundColor(Color.parseColor("#CC000000"));
        b.setGravity(Gravity.CENTER);

        TextView info = new TextView(this);
        info.setTextColor(Color.parseColor("#40ffffff"));
        info.setTextSize(9);
        info.setSingleLine(true);
        info.setGravity(Gravity.CENTER);
        if (!variants.isEmpty()) info.setText("Source " + (currentVariantIdx + 1) + "/" + variants.size());
        b.addView(info);
        return b;
    }

    private View buildErrorView() {
        LinearLayout e = new LinearLayout(this);
        e.setOrientation(LinearLayout.VERTICAL);
        e.setGravity(Gravity.CENTER);
        e.setVisibility(View.GONE);

        errorText = new TextView(this);
        errorText.setTextColor(Color.parseColor("#aaaaaa"));
        errorText.setTextSize(13);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(dpToPx(24), 0, dpToPx(24), dpToPx(12));
        e.addView(errorText);

        TextView retry = new TextView(this);
        retry.setText("Retry");
        retry.setTextColor(Color.parseColor("#6c5ce7"));
        retry.setTextSize(14);
        retry.setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10));
        retry.setBackgroundColor(Color.parseColor("#1a1a2e"));
        retry.setOnClickListener(v -> { errorContainer.setVisibility(View.GONE); loadingView.setVisibility(View.VISIBLE); playVariant(currentVariantIdx); });
        e.addView(retry);
        return e;
    }

    // ── Playback with failover ──
    private void playVariant(int idx) {
        if (idx < 0 || idx >= variants.size()) {
            // All variants exhausted — try lastSuccessUrl if we have one
            if (lastSuccessUrl != null) {
                showToast("All alternatives failed — retrying last working source");
                for (int i = 0; i < variants.size(); i++) {
                    if (variants.get(i).url.equals(lastSuccessUrl)) { playVariant(i); return; }
                }
            }
            showAllFailed();
            return;
        }

        currentVariantIdx = idx;
        StreamVariant sv = variants.get(idx);
        releasePlayer();
        loadingView.setVisibility(View.VISIBLE);
        errorContainer.setVisibility(View.GONE);

        // Update UI
        String display = sv.title.isEmpty() ? "Source " + (idx + 1) : sv.title;
        if (!sv.tag.isEmpty()) display += " (" + sv.tag + ")";
        titleText.setText(display);
        statusText.setText("Source " + (idx + 1) + "/" + variants.size() + (locked ? " · 🔒" : ""));

        if (idx > 0) {
            String tagInfo = sv.tag.isEmpty() ? "" : " (" + sv.tag + ")";
            showToast("Trying: " + sv.title + tagInfo);
        }

        DataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent("StreamVault/3.2 ExoPlayer")
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(12000)
            .setAllowCrossProtocolRedirects(true);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playbackStartTime = System.currentTimeMillis();

        // Try HLS first
        MediaSource hlsSource = new HlsMediaSource.Factory(http)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(Uri.parse(sv.url)));

        final boolean[] hlsFailed = {false};

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    loadingView.setVisibility(View.GONE);
                    errorContainer.setVisibility(View.GONE);
                    lastSuccessUrl = sv.url;
                    cancelFailoverTimeout();
                } else if (state == Player.STATE_BUFFERING) {
                    loadingView.setVisibility(View.VISIBLE);
                    // Start failover timer if buffering too long
                    if (!locked) startFailoverTimeout();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                cancelFailoverTimeout();
                if (!hlsFailed[0]) {
                    hlsFailed[0] = true;
                    retryAsProgressive(http, Uri.parse(sv.url), sv);
                    return;
                }
                // Stream truly failed
                if (!locked && !isNetworkIssue()) {
                    tryNextVariant();
                } else if (isNetworkIssue()) {
                    showToast("Network issue — waiting for reconnect…");
                    loadingView.setVisibility(View.VISIBLE);
                } else {
                    showAllFailed();
                }
            }
        });

        player.setMediaSource(hlsSource);
        player.setPlayWhenReady(true);
        player.prepare();

        showOverlayBriefly();
    }

    private void retryAsProgressive(DataSource.Factory http, Uri uri, StreamVariant sv) {
        if (player != null) {
            player.stop();
            MediaSource src = new ProgressiveMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(uri));
            player.setMediaSource(src);
            player.prepare();
            player.setPlayWhenReady(true);
        }
    }

    private void tryNextVariant() {
        if (locked) return;
        if (currentVariantIdx + 1 < variants.size()) {
            playVariant(currentVariantIdx + 1);
        } else if (lastSuccessUrl != null) {
            showToast("All alternatives tried — back to last working");
            for (int i = 0; i < variants.size(); i++) {
                if (variants.get(i).url.equals(lastSuccessUrl)) { playVariant(i); return; }
            }
        } else {
            showAllFailed();
        }
    }

    private void startFailoverTimeout() {
        cancelFailoverTimeout();
        failoverTimeout = () -> {
            if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING && !locked && networkAvailable) {
                long elapsed = System.currentTimeMillis() - playbackStartTime;
                if (elapsed > BUFFER_GRACE_MS) {
                    showToast("Buffering too long — trying next source");
                    tryNextVariant();
                }
            }
        };
        handler.postDelayed(failoverTimeout, FAILOVER_TIMEOUT_MS);
    }

    private void cancelFailoverTimeout() {
        handler.removeCallbacks(failoverTimeout);
    }

    private void showAllFailed() {
        loadingView.setVisibility(View.GONE);
        errorContainer.setVisibility(View.VISIBLE);
        errorText.setText("All " + variants.size() + " source(s) failed");
    }

    private boolean isNetworkIssue() { return !networkAvailable; }

    // ── Network monitoring ──
    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    handler.post(() -> {
                        boolean wasDown = !networkAvailable;
                        networkAvailable = true;
                        if (wasDown && player != null && player.getPlaybackState() == Player.STATE_IDLE) {
                            showToast("Network restored — retrying");
                            playVariant(currentVariantIdx);
                        }
                    });
                }
                @Override public void onLost(Network n) {
                    handler.post(() -> {
                        networkAvailable = false;
                        cancelFailoverTimeout(); // Don't failover on network loss
                        showToast("Network lost — pausing failover");
                    });
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback);
        }
    }

    // ── UI helpers ──
    private void updatePlayPause() {
        playPauseBtn.setImageResource(player != null && player.isPlaying()
            ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    private void toggleOverlay() { setOverlayVisible(!overlayVisible); if (overlayVisible) scheduleHideOverlay(); }
    private void showOverlayBriefly() { setOverlayVisible(true); scheduleHideOverlay(); }
    private void setOverlayVisible(boolean v) {
        overlayVisible = v; float a = v ? 1f : 0f; int d = 200;
        overlayTop.animate().alpha(a).setDuration(d).start();
        overlayCenter.animate().alpha(a).setDuration(d).start();
        overlayBottom.animate().alpha(a).setDuration(d).start();
    }
    private void scheduleHideOverlay() { handler.removeCallbacks(hideOverlayRunnable); handler.postDelayed(hideOverlayRunnable, 4000); }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void releasePlayer() { if (player != null) { player.stop(); player.release(); player = null; } }
    private int dpToPx(int dp) { return (int) (dp * getResources().getDisplayMetrics().density); }
    private FrameLayout.LayoutParams matchParent() { return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT); }
    private FrameLayout.LayoutParams centered(int w, int h) { return new FrameLayout.LayoutParams(w, h, Gravity.CENTER); }
    private ImageButton makeBtn(int res, int bg) {
        ImageButton b = new ImageButton(this); b.setImageResource(res); b.setColorFilter(Color.WHITE);
        b.setBackgroundColor(bg); b.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10)); return b;
    }

    @Override public void onBackPressed() { finish(); }
    @Override protected void onResume() { super.onResume(); hideSystemUI(); if (player != null) player.play(); }
    @Override protected void onPause() { if (player != null) player.pause(); super.onPause(); }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            try { ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback); } catch (Exception e) {}
        }
        super.onDestroy();
    }
}
