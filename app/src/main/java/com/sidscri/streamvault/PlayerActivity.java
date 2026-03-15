package com.sidscri.streamvault;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import androidx.media3.common.Format;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_TITLE = "stream_title";
    public static final String EXTRA_CATEGORY = "stream_category";
    public static final String EXTRA_FAILOVER_JSON = "failover_json";
    public static final String EXTRA_NOW_NEXT = "now_next";
    public static final String EXTRA_FO_TIMEOUT = "fo_timeout";
    public static final String EXTRA_FO_AUTO = "fo_auto";

    // State
    private ExoPlayer player;
    private PlayerView playerView;
    private View loadingView;
    private View errorContainer;
    private View overlayTop, overlayCenter, overlayBottom;
    private TextView titleText, statusText, nowNextText, strengthText, errorText;
    private ImageButton playPauseBtn, lockBtn, recordBtn;
    private Handler handler;
    private Runnable hideOverlayRunnable;
    private Runnable failoverTimeoutRunnable;
    private Runnable strengthUpdater;
    private boolean overlayVisible = false;
    private boolean locked = false;
    private boolean networkAvailable = true;
    private boolean recording = false;
    private String lastSuccessUrl = null;
    private long playbackStartTime = 0;
    private long foTimeoutMs = 15000;
    private boolean foAuto = true;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Thread recordThread;

    private final List<Variant> variants = new ArrayList<>();
    private int currentIdx = 0;

    static class Variant {
        final String url, title, region, tag;
        Variant(String url, String title, String region, String tag) {
            this.url = url != null ? url : "";
            this.title = title != null ? title : "";
            this.region = region != null ? region : "";
            this.tag = tag != null ? tag : "";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        handler = new Handler(Looper.getMainLooper());
        hideOverlayRunnable = () -> setOverlayVisible(false);
        failoverTimeoutRunnable = () -> {};

        parseIntent();
        if (variants.isEmpty()) { finish(); return; }

        buildUI();
        registerNetworkCallback();
        startStrengthMonitor();
        playVariant(0);
        hideSystemUI();
    }

    // ─── Intent Parsing ───
    private void parseIntent() {
        // Read failover timeout
        foTimeoutMs = getIntent().getIntExtra(EXTRA_FO_TIMEOUT, 15) * 1000L;
        foAuto = getIntent().getBooleanExtra(EXTRA_FO_AUTO, true);

        // Parse failover JSON
        String json = getIntent().getStringExtra(EXTRA_FAILOVER_JSON);
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    variants.add(new Variant(
                        o.optString("url", ""),
                        o.optString("title", ""),
                        o.optString("region", ""),
                        o.optString("tag", "")
                    ));
                }
            } catch (Exception e) {
                // Failed to parse JSON — try single URL fallback
            }
        }

        // Fallback: single URL
        if (variants.isEmpty()) {
            String url = getIntent().getStringExtra(EXTRA_URL);
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            if (url != null && !url.isEmpty()) {
                variants.add(new Variant(url, title != null ? title : "Stream", "", ""));
            }
        }
    }

    // ─── UI ───
    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // ExoPlayer view
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setKeepScreenOn(true);
        root.addView(playerView, matchParent());

        // Loading spinner
        loadingView = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        root.addView(loadingView, centered(dp(38), dp(38)));

        // Error view
        errorContainer = buildErrorView();
        root.addView(errorContainer, centered(-2, -2));

        // Touch overlay
        View touchCatcher = new View(this);
        touchCatcher.setBackgroundColor(Color.TRANSPARENT);
        GestureDetector gd = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    toggleOverlay();
                    return true;
                }
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (player != null) {
                        if (player.isPlaying()) player.pause();
                        else player.play();
                        updatePlayPauseIcon();
                    }
                    return true;
                }
            });
        touchCatcher.setOnTouchListener((v, e) -> { gd.onTouchEvent(e); return true; });
        root.addView(touchCatcher, matchParent());

        // Overlays
        overlayTop = buildTopOverlay();
        root.addView(overlayTop, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        overlayCenter = buildCenterOverlay();
        root.addView(overlayCenter, centered(-2, -2));

        overlayBottom = buildBottomOverlay();
        root.addView(overlayBottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setOverlayVisible(false);
        setContentView(root);
    }

    private View buildTopOverlay() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(34), dp(8), dp(8));
        top.setBackgroundColor(Color.parseColor("#CC000000"));

        // Back button
        ImageButton backBtn = makeButton(android.R.drawable.ic_media_previous);
        backBtn.setOnClickListener(v -> finish());
        top.addView(backBtn);

        // Title area
        LinearLayout titleArea = new LinearLayout(this);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        titleArea.setPadding(dp(6), 0, 0, 0);

        titleText = new TextView(this);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(12);
        titleText.setSingleLine(true);
        titleText.setTypeface(null, Typeface.BOLD);
        titleArea.addView(titleText);

        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#80ffffff"));
        statusText.setTextSize(8);
        statusText.setSingleLine(true);
        titleArea.addView(statusText);

        nowNextText = new TextView(this);
        nowNextText.setTextColor(Color.parseColor("#6c5ce7"));
        nowNextText.setTextSize(8);
        nowNextText.setSingleLine(true);
        String nn = getIntent().getStringExtra(EXTRA_NOW_NEXT);
        if (nn != null && !nn.isEmpty()) nowNextText.setText(nn);
        titleArea.addView(nowNextText);

        strengthText = new TextView(this);
        strengthText.setTextColor(Color.parseColor("#60ffffff"));
        strengthText.setTextSize(7);
        strengthText.setSingleLine(true);
        titleArea.addView(strengthText);

        top.addView(titleArea, new LinearLayout.LayoutParams(0, -2, 1));

        // Record button
        recordBtn = makeButton(android.R.drawable.ic_btn_speak_now);
        recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
        recordBtn.setOnClickListener(v -> toggleRecording());
        top.addView(recordBtn);

        // Lock button
        lockBtn = makeButton(android.R.drawable.ic_lock_idle_lock);
        lockBtn.setColorFilter(Color.parseColor("#80ffffff"));
        lockBtn.setOnClickListener(v -> {
            locked = !locked;
            lockBtn.setColorFilter(locked ? Color.parseColor("#ffa502") : Color.parseColor("#80ffffff"));
            showMsg(locked ? "🔒 Locked to this source" : "🔓 Auto-failover enabled");
            scheduleHideOverlay();
        });
        top.addView(lockBtn);

        return top;
    }

    private View buildCenterOverlay() {
        LinearLayout center = new LinearLayout(this);
        center.setGravity(Gravity.CENTER);

        playPauseBtn = new ImageButton(this);
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        playPauseBtn.setColorFilter(Color.WHITE);
        playPauseBtn.setBackgroundColor(Color.parseColor("#6c5ce7"));
        playPauseBtn.setPadding(dp(15), dp(15), dp(15), dp(15));
        playPauseBtn.setOnClickListener(v -> {
            if (player != null) {
                if (player.isPlaying()) player.pause();
                else player.play();
                updatePlayPauseIcon();
                scheduleHideOverlay();
            }
        });
        center.addView(playPauseBtn);

        return center;
    }

    private View buildBottomOverlay() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp(10), dp(8), dp(10), dp(24));
        bottom.setBackgroundColor(Color.parseColor("#CC000000"));
        bottom.setGravity(Gravity.CENTER);

        TextView info = new TextView(this);
        info.setTextColor(Color.parseColor("#40ffffff"));
        info.setTextSize(7);
        info.setSingleLine(true);
        info.setGravity(Gravity.CENTER);
        info.setText("Source 1/" + variants.size());
        bottom.addView(info);

        return bottom;
    }

    private View buildErrorView() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setVisibility(View.GONE);

        errorText = new TextView(this);
        errorText.setTextColor(Color.parseColor("#aaaaaa"));
        errorText.setTextSize(12);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(dp(20), 0, dp(20), dp(10));
        container.addView(errorText);

        TextView retryBtn = new TextView(this);
        retryBtn.setText("Tap to Retry");
        retryBtn.setTextColor(Color.parseColor("#6c5ce7"));
        retryBtn.setTextSize(13);
        retryBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        retryBtn.setBackgroundColor(Color.parseColor("#1a1a2e"));
        retryBtn.setOnClickListener(v -> {
            errorContainer.setVisibility(View.GONE);
            loadingView.setVisibility(View.VISIBLE);
            playVariant(currentIdx);
        });
        container.addView(retryBtn);

        return container;
    }

    // ─── Playback ───
    private void playVariant(int idx) {
        if (idx < 0 || idx >= variants.size()) {
            // All exhausted — try last success
            if (lastSuccessUrl != null) {
                showMsg("Retrying last working source");
                for (int i = 0; i < variants.size(); i++) {
                    if (lastSuccessUrl.equals(variants.get(i).url)) {
                        playVariant(i);
                        return;
                    }
                }
            }
            showAllFailed();
            return;
        }

        currentIdx = idx;
        Variant v = variants.get(idx);
        releasePlayer();
        loadingView.setVisibility(View.VISIBLE);
        errorContainer.setVisibility(View.GONE);

        // Update display
        String display = v.title.isEmpty() ? "Source " + (idx + 1) : v.title;
        if (!v.tag.isEmpty()) display += " (" + v.tag + ")";
        titleText.setText(display);
        statusText.setText("Source " + (idx + 1) + "/" + variants.size()
            + (locked ? " · 🔒" : ""));
        strengthText.setText("");

        if (idx > 0) showMsg("Trying: " + display);

        // Create player
        DataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent("StreamVault/4.1 ExoPlayer")
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(12000)
            .setAllowCrossProtocolRedirects(true);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playbackStartTime = System.currentTimeMillis();

        // Try HLS first (most IPTV is HLS)
        MediaSource hlsSource = new HlsMediaSource.Factory(httpFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(Uri.parse(v.url)));

        final boolean[] hlsFailed = {false};

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    loadingView.setVisibility(View.GONE);
                    errorContainer.setVisibility(View.GONE);
                    lastSuccessUrl = v.url;
                    cancelFailoverTimeout();
                } else if (state == Player.STATE_BUFFERING) {
                    loadingView.setVisibility(View.VISIBLE);
                    if (!locked && foAuto) startFailoverTimeout();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                cancelFailoverTimeout();
                if (!hlsFailed[0]) {
                    // HLS failed — retry as progressive
                    hlsFailed[0] = true;
                    try {
                        player.stop();
                        MediaSource progressive = new ProgressiveMediaSource.Factory(httpFactory)
                            .createMediaSource(MediaItem.fromUri(Uri.parse(v.url)));
                        player.setMediaSource(progressive);
                        player.prepare();
                        player.setPlayWhenReady(true);
                    } catch (Exception e) {
                        tryNextVariant();
                    }
                    return;
                }
                // Both HLS and progressive failed
                if (!locked && foAuto && networkAvailable) {
                    tryNextVariant();
                } else if (!networkAvailable) {
                    showMsg("Network lost — waiting for reconnect");
                    loadingView.setVisibility(View.VISIBLE);
                } else {
                    showStreamFailed();
                }
            }
        });

        player.setMediaSource(hlsSource);
        player.setPlayWhenReady(true);
        player.prepare();

        showOverlayBriefly();
    }

    private void tryNextVariant() {
        if (locked) return;
        if (currentIdx + 1 < variants.size()) {
            playVariant(currentIdx + 1);
        } else if (lastSuccessUrl != null) {
            showMsg("All alternatives tried — back to last working");
            for (int i = 0; i < variants.size(); i++) {
                if (lastSuccessUrl.equals(variants.get(i).url)) {
                    playVariant(i);
                    return;
                }
            }
            showAllFailed();
        } else {
            showAllFailed();
        }
    }

    private void showAllFailed() {
        loadingView.setVisibility(View.GONE);
        errorContainer.setVisibility(View.VISIBLE);
        errorText.setText("All " + variants.size() + " source(s) failed");
    }

    private void showStreamFailed() {
        loadingView.setVisibility(View.GONE);
        errorContainer.setVisibility(View.VISIBLE);
        errorText.setText("Stream failed");
    }

    // ─── Failover Timeout ───
    private void startFailoverTimeout() {
        cancelFailoverTimeout();
        failoverTimeoutRunnable = () -> {
            if (player != null
                && player.getPlaybackState() == Player.STATE_BUFFERING
                && !locked && foAuto && networkAvailable
                && System.currentTimeMillis() - playbackStartTime > 8000) {
                showMsg("Buffering too long — trying next source");
                tryNextVariant();
            }
        };
        handler.postDelayed(failoverTimeoutRunnable, foTimeoutMs);
    }

    private void cancelFailoverTimeout() {
        if (failoverTimeoutRunnable != null) {
            handler.removeCallbacks(failoverTimeoutRunnable);
        }
    }

    // ─── Stream Strength Monitor ───
    private void startStrengthMonitor() {
        strengthUpdater = new Runnable() {
            @Override
            public void run() {
                if (player != null && player.getPlaybackState() == Player.STATE_READY) {
                    long bitrate = 0;
                    Format vf = player.getVideoFormat();
                    if (vf != null && vf.bitrate > 0) bitrate = vf.bitrate;

                    long buffMs = player.getBufferedPosition() - player.getCurrentPosition();
                    String bps = bitrate > 0 ? (bitrate / 1000) + "kbps" : "";
                    String buf = buffMs > 0 ? (buffMs / 1000) + "s buf" : "";

                    String label;
                    int color;
                    if (buffMs > 10000 && bitrate > 2000000) {
                        label = "●●●● Excellent";
                        color = Color.parseColor("#2ed573");
                    } else if (buffMs > 5000 && bitrate > 1000000) {
                        label = "●●●○ Good";
                        color = Color.parseColor("#2ed573");
                    } else if (buffMs > 2000) {
                        label = "●●○○ Fair";
                        color = Color.parseColor("#ffa502");
                    } else {
                        label = "●○○○ Weak";
                        color = Color.parseColor("#ff4757");
                    }

                    String display = label;
                    if (!bps.isEmpty()) display += " · " + bps;
                    if (!buf.isEmpty()) display += " · " + buf;

                    strengthText.setText(display);
                    strengthText.setTextColor(color);
                }
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(strengthUpdater, 3000);
    }

    // ─── Recording ───
    private void toggleRecording() {
        if (recording) stopRecording();
        else startRecording();
    }

    private void startRecording() {
        if (currentIdx >= variants.size()) return;
        recording = true;
        recordBtn.setColorFilter(Color.parseColor("#ff4757"));
        showMsg("⏺ Recording started");
        scheduleHideOverlay();

        final String url = variants.get(currentIdx).url;
        final String safeName = variants.get(currentIdx).title.replaceAll("[^a-zA-Z0-9]", "_");
        final String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

        recordThread = new Thread(() -> {
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File outFile = new File(dir, "SV_" + safeName + "_" + timestamp + ".ts");

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "StreamVault/4.1");
                conn.setConnectTimeout(10000);
                InputStream in = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(outFile);

                byte[] buffer = new byte[8192];
                int bytesRead;
                while (recording && (bytesRead = in.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }

                fos.close();
                in.close();
                handler.post(() -> showMsg("⏹ Saved: " + outFile.getName()));
            } catch (Exception e) {
                handler.post(() -> showMsg("Record error: " + e.getMessage()));
            }
            handler.post(() -> {
                recording = false;
                recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
            });
        });
        recordThread.start();
    }

    private void stopRecording() {
        recording = false;
        recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
        showMsg("⏹ Recording stopped");
    }

    // ─── Network Monitoring ───
    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        handler.post(() -> {
                            boolean wasDown = !networkAvailable;
                            networkAvailable = true;
                            if (wasDown && player != null
                                && player.getPlaybackState() == Player.STATE_IDLE) {
                                showMsg("Network restored — retrying");
                                playVariant(currentIdx);
                            }
                        });
                    }

                    @Override
                    public void onLost(Network network) {
                        handler.post(() -> {
                            networkAvailable = false;
                            cancelFailoverTimeout();
                            showMsg("Network lost — pausing failover");
                        });
                    }
                };
                cm.registerNetworkCallback(
                    new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback);
            } catch (Exception e) {
                // Ignore — network monitoring is optional
            }
        }
    }

    // ─── UI Helpers ───
    private void updatePlayPauseIcon() {
        if (player != null && player.isPlaying()) {
            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            playPauseBtn.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    private void toggleOverlay() {
        setOverlayVisible(!overlayVisible);
        if (overlayVisible) scheduleHideOverlay();
    }

    private void showOverlayBriefly() {
        setOverlayVisible(true);
        scheduleHideOverlay();
    }

    private void setOverlayVisible(boolean visible) {
        overlayVisible = visible;
        float alpha = visible ? 1f : 0f;
        overlayTop.animate().alpha(alpha).setDuration(200).start();
        overlayCenter.animate().alpha(alpha).setDuration(200).start();
        overlayBottom.animate().alpha(alpha).setDuration(200).start();
    }

    private void scheduleHideOverlay() {
        handler.removeCallbacks(hideOverlayRunnable);
        handler.postDelayed(hideOverlayRunnable, 4000);
    }

    private void showMsg(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
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

    private void releasePlayer() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private FrameLayout.LayoutParams centered(int w, int h) {
        return new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
    }

    private ImageButton makeButton(int drawableRes) {
        ImageButton btn = new ImageButton(this);
        btn.setImageResource(drawableRes);
        btn.setColorFilter(Color.WHITE);
        btn.setBackgroundColor(Color.TRANSPARENT);
        btn.setPadding(dp(8), dp(8), dp(8), dp(8));
        return btn;
    }

    // ─── Lifecycle ───
    @Override
    public void onBackPressed() {
        if (recording) stopRecording();
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (player != null) player.play();
    }

    @Override
    protected void onPause() {
        if (player != null) player.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (recording) stopRecording();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) { /* ignore */ }
        }
        super.onDestroy();
    }
}
