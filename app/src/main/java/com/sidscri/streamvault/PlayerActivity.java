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

    private ExoPlayer player;
    private PlayerView playerView;
    private View loadingView, errorContainer, overlayTop, overlayCenter, overlayBottom;
    private TextView titleText, statusText, errorText, nowNextText, strengthText;
    private ImageButton playPauseBtn, lockBtn, recordBtn;
    private Handler handler;
    private Runnable hideOverlayRunnable, failoverTimeout, strengthUpdater;
    private boolean overlayVisible = false, locked = false, networkAvailable = true, recording = false;
    private String lastSuccessUrl = null;
    private long playbackStartTime = 0, foTimeoutMs = 15000, bufferGraceMs = 8000;
    private boolean foAuto = true;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Thread recordThread;

    private List<StreamVariant> variants = new ArrayList<>();
    private int currentVariantIdx = 0;

    static class StreamVariant {
        String url, title, region, tag;
        StreamVariant(String u, String t, String r, String tg) { url=u; title=t; region=r; tag=tg; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT); getWindow().setNavigationBarColor(Color.BLACK);
        }
        handler = new Handler(Looper.getMainLooper());
        hideOverlayRunnable = () -> setOverlayVisible(false);
        failoverTimeout = () -> {};
        parseIntent(); buildUI(); registerNetworkCallback();
        startStrengthMonitor();
        playVariant(0); hideSystemUI();
    }

    private void parseIntent() {
        foTimeoutMs = getIntent().getIntExtra(EXTRA_FO_TIMEOUT, 15) * 1000L;
        foAuto = getIntent().getBooleanExtra(EXTRA_FO_AUTO, true);
        String json = getIntent().getStringExtra(EXTRA_FAILOVER_JSON);
        if (json != null) {
            try { JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i);
                    variants.add(new StreamVariant(o.getString("url"), o.optString("title",""), o.optString("region",""), o.optString("tag","")));
            }} catch (Exception e) {}
        }
        if (variants.isEmpty()) { String u = getIntent().getStringExtra(EXTRA_URL); if (u != null) variants.add(new StreamVariant(u, getIntent().getStringExtra(EXTRA_TITLE), "", "")); }
        if (variants.isEmpty()) finish();
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this); playerView.setUseController(false); root.addView(playerView, mp());
        loadingView = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge); root.addView(loadingView, ctr(dp(36), dp(36)));
        errorContainer = buildErr(); root.addView(errorContainer, ctr(-2, -2));
        View touch = new View(this); touch.setBackgroundColor(Color.TRANSPARENT);
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleOverlay(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { if (player != null) { if (player.isPlaying()) player.pause(); else player.play(); updPP(); } return true; }
        });
        touch.setOnTouchListener((v, e) -> { gd.onTouchEvent(e); return true; });
        root.addView(touch, mp());
        overlayTop = buildTop(); root.addView(overlayTop, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        overlayCenter = buildCenter(); root.addView(overlayCenter, ctr(-2, -2));
        overlayBottom = buildBot(); root.addView(overlayBottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setOverlayVisible(false); setContentView(root);
    }

    private View buildTop() {
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.HORIZONTAL); t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(8), dp(34), dp(8), dp(8)); t.setBackgroundColor(Color.parseColor("#CC000000"));
        ImageButton back = btn(android.R.drawable.ic_media_previous); back.setOnClickListener(v -> finish()); t.addView(back);
        LinearLayout ta = new LinearLayout(this); ta.setOrientation(LinearLayout.VERTICAL); ta.setPadding(dp(5), 0, 0, 0);
        titleText = new TextView(this); titleText.setTextColor(Color.WHITE); titleText.setTextSize(12); titleText.setSingleLine(true); titleText.setTypeface(null, Typeface.BOLD); ta.addView(titleText);
        statusText = new TextView(this); statusText.setTextColor(Color.parseColor("#80ffffff")); statusText.setTextSize(8); statusText.setSingleLine(true); ta.addView(statusText);
        nowNextText = new TextView(this); nowNextText.setTextColor(Color.parseColor("#6c5ce7")); nowNextText.setTextSize(8); nowNextText.setSingleLine(true);
        String nn = getIntent().getStringExtra(EXTRA_NOW_NEXT); if (nn != null && !nn.isEmpty()) nowNextText.setText(nn); ta.addView(nowNextText);
        // Stream strength
        strengthText = new TextView(this); strengthText.setTextColor(Color.parseColor("#60ffffff")); strengthText.setTextSize(7); strengthText.setSingleLine(true); ta.addView(strengthText);
        t.addView(ta, new LinearLayout.LayoutParams(0, -2, 1));
        // Record button
        recordBtn = btn(android.R.drawable.ic_btn_speak_now); recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
        recordBtn.setOnClickListener(v -> toggleRecording()); t.addView(recordBtn);
        // Lock button
        lockBtn = btn(android.R.drawable.ic_lock_idle_lock); lockBtn.setColorFilter(Color.parseColor("#80ffffff"));
        lockBtn.setOnClickListener(v -> { locked = !locked; lockBtn.setColorFilter(locked ? Color.parseColor("#ffa502") : Color.parseColor("#80ffffff")); msg(locked ? "🔒 Locked" : "🔓 Unlocked"); schedHide(); });
        t.addView(lockBtn);
        return t;
    }

    private View buildCenter() {
        LinearLayout c = new LinearLayout(this); c.setGravity(Gravity.CENTER);
        playPauseBtn = new ImageButton(this); playPauseBtn.setImageResource(android.R.drawable.ic_media_pause); playPauseBtn.setColorFilter(Color.WHITE);
        playPauseBtn.setBackgroundColor(Color.parseColor("#6c5ce7")); playPauseBtn.setPadding(dp(14), dp(14), dp(14), dp(14));
        playPauseBtn.setOnClickListener(v -> { if (player != null) { if (player.isPlaying()) player.pause(); else player.play(); updPP(); schedHide(); } });
        c.addView(playPauseBtn); return c;
    }

    private View buildBot() {
        LinearLayout b = new LinearLayout(this); b.setPadding(dp(10), dp(8), dp(10), dp(22)); b.setBackgroundColor(Color.parseColor("#CC000000")); b.setGravity(Gravity.CENTER);
        TextView info = new TextView(this); info.setTextColor(Color.parseColor("#40ffffff")); info.setTextSize(7); info.setSingleLine(true); info.setGravity(Gravity.CENTER);
        info.setText("Source 1/" + variants.size()); b.addView(info); return b;
    }

    private View buildErr() {
        LinearLayout e = new LinearLayout(this); e.setOrientation(LinearLayout.VERTICAL); e.setGravity(Gravity.CENTER); e.setVisibility(View.GONE);
        errorText = new TextView(this); errorText.setTextColor(Color.parseColor("#aaa")); errorText.setTextSize(11); errorText.setGravity(Gravity.CENTER); errorText.setPadding(dp(16), 0, dp(16), dp(8)); e.addView(errorText);
        TextView retry = new TextView(this); retry.setText("Retry"); retry.setTextColor(Color.parseColor("#6c5ce7")); retry.setTextSize(12);
        retry.setPadding(dp(14), dp(7), dp(14), dp(7)); retry.setBackgroundColor(Color.parseColor("#1a1a2e"));
        retry.setOnClickListener(v -> { errorContainer.setVisibility(View.GONE); loadingView.setVisibility(View.VISIBLE); playVariant(currentVariantIdx); }); e.addView(retry);
        return e;
    }

    // Stream strength monitor — polls ExoPlayer bandwidth + buffer
    private void startStrengthMonitor() {
        strengthUpdater = new Runnable() {
            @Override public void run() {
                if (player != null && player.getPlaybackState() == Player.STATE_READY) {
                    long bitrate = 0;
                    Format vf = player.getVideoFormat();
                    if (vf != null && vf.bitrate > 0) bitrate = vf.bitrate;
                    long buffMs = player.getBufferedPosition() - player.getCurrentPosition();
                    String bps = bitrate > 0 ? (bitrate / 1000) + " kbps" : "";
                    String buf = buffMs > 0 ? (buffMs / 1000) + "s buf" : "";
                    String strength;
                    if (buffMs > 10000 && bitrate > 2000000) strength = "●●●● Excellent";
                    else if (buffMs > 5000 && bitrate > 1000000) strength = "●●●○ Good";
                    else if (buffMs > 2000) strength = "●●○○ Fair";
                    else strength = "●○○○ Weak";
                    strengthText.setText(strength + (bps.isEmpty() ? "" : " · " + bps) + (buf.isEmpty() ? "" : " · " + buf));
                    strengthText.setTextColor(buffMs > 5000 ? Color.parseColor("#2ed573") : buffMs > 2000 ? Color.parseColor("#ffa502") : Color.parseColor("#ff4757"));
                }
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(strengthUpdater, 3000);
    }

    // Recording — simple stream download to file
    private void toggleRecording() {
        if (recording) { stopRecording(); }
        else { startRecording(); }
    }

    private void startRecording() {
        if (currentVariantIdx >= variants.size()) return;
        recording = true;
        recordBtn.setColorFilter(Color.parseColor("#ff4757"));
        msg("⏺ Recording started");
        schedHide();
        final String url = variants.get(currentVariantIdx).url;
        final String title = variants.get(currentVariantIdx).title.replaceAll("[^a-zA-Z0-9]", "_");
        final String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        recordThread = new Thread(() -> {
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File out = new File(dir, "SV_" + title + "_" + ts + ".ts");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "StreamVault/4.0");
                conn.setConnectTimeout(10000);
                InputStream in = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while (recording && (n = in.read(buf)) != -1) { fos.write(buf, 0, n); }
                fos.close(); in.close();
                handler.post(() -> msg("⏹ Saved: " + out.getName()));
            } catch (Exception e) {
                handler.post(() -> msg("Record error: " + e.getMessage()));
            }
            handler.post(() -> { recording = false; recordBtn.setColorFilter(Color.parseColor("#80ffffff")); });
        });
        recordThread.start();
    }

    private void stopRecording() {
        recording = false;
        recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
        msg("⏹ Recording stopped");
    }

    private void playVariant(int idx) {
        if (idx < 0 || idx >= variants.size()) {
            if (lastSuccessUrl != null) { msg("Retrying last working"); for (int i = 0; i < variants.size(); i++) if (variants.get(i).url.equals(lastSuccessUrl)) { playVariant(i); return; } }
            loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("All " + variants.size() + " failed"); return;
        }
        currentVariantIdx = idx; StreamVariant sv = variants.get(idx); releasePlayer();
        loadingView.setVisibility(View.VISIBLE); errorContainer.setVisibility(View.GONE);
        String disp = sv.title.isEmpty() ? "Source " + (idx + 1) : sv.title; if (!sv.tag.isEmpty()) disp += " (" + sv.tag + ")";
        titleText.setText(disp); statusText.setText("Source " + (idx + 1) + "/" + variants.size() + (locked ? " · 🔒" : "")); strengthText.setText("");
        if (idx > 0) msg("Trying: " + disp);

        DataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent("StreamVault/4.0 ExoPlayer").setConnectTimeoutMs(12000).setReadTimeoutMs(12000).setAllowCrossProtocolRedirects(true);
        player = new ExoPlayer.Builder(this).build(); playerView.setPlayer(player); playbackStartTime = System.currentTimeMillis();
        MediaSource hls = new HlsMediaSource.Factory(http).setAllowChunklessPreparation(true).createMediaSource(MediaItem.fromUri(Uri.parse(sv.url)));
        final boolean[] hlsFail = {false};
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.GONE); lastSuccessUrl = sv.url; cancelFT(); }
                else if (state == Player.STATE_BUFFERING) { loadingView.setVisibility(View.VISIBLE); if (!locked && foAuto) startFT(); }
            }
            @Override public void onPlayerError(PlaybackException error) {
                cancelFT();
                if (!hlsFail[0]) { hlsFail[0] = true; if (player != null) { player.stop(); player.setMediaSource(new ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(Uri.parse(sv.url)))); player.prepare(); player.setPlayWhenReady(true); } return; }
                if (!locked && foAuto && networkAvailable) tryNext(); else if (!networkAvailable) { msg("Network lost"); loadingView.setVisibility(View.VISIBLE); }
                else { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("Failed"); }
            }
        });
        player.setMediaSource(hls); player.setPlayWhenReady(true); player.prepare(); showBrief();
    }

    private void tryNext() { if (locked) return; if (currentVariantIdx + 1 < variants.size()) playVariant(currentVariantIdx + 1); else if (lastSuccessUrl != null) { msg("Back to last working"); for (int i = 0; i < variants.size(); i++) if (variants.get(i).url.equals(lastSuccessUrl)) { playVariant(i); return; } } else { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("All failed"); } }
    private void startFT() { cancelFT(); failoverTimeout = () -> { if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING && !locked && foAuto && networkAvailable && System.currentTimeMillis() - playbackStartTime > bufferGraceMs) { msg("Buffering too long"); tryNext(); } }; handler.postDelayed(failoverTimeout, foTimeoutMs); }
    private void cancelFT() { handler.removeCallbacks(failoverTimeout); }

    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { handler.post(() -> { boolean was = !networkAvailable; networkAvailable = true; if (was && player != null && player.getPlaybackState() == Player.STATE_IDLE) { msg("Network back"); playVariant(currentVariantIdx); } }); }
                @Override public void onLost(Network n) { handler.post(() -> { networkAvailable = false; cancelFT(); msg("Network lost"); }); }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback);
        }
    }

    private void updPP() { playPauseBtn.setImageResource(player != null && player.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play); }
    private void toggleOverlay() { setOverlayVisible(!overlayVisible); if (overlayVisible) schedHide(); }
    private void showBrief() { setOverlayVisible(true); schedHide(); }
    private void setOverlayVisible(boolean v) { overlayVisible = v; float a = v ? 1f : 0f; overlayTop.animate().alpha(a).setDuration(180).start(); overlayCenter.animate().alpha(a).setDuration(180).start(); overlayBottom.animate().alpha(a).setDuration(180).start(); }
    private void schedHide() { handler.removeCallbacks(hideOverlayRunnable); handler.postDelayed(hideOverlayRunnable, 4000); }
    private void msg(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show()); }
    private void hideSystemUI() { getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY); }
    private void releasePlayer() { if (player != null) { player.stop(); player.release(); player = null; } }
    private int dp(int d) { return (int) (d * getResources().getDisplayMetrics().density); }
    private FrameLayout.LayoutParams mp() { return new FrameLayout.LayoutParams(-1, -1); }
    private FrameLayout.LayoutParams ctr(int w, int h) { return new FrameLayout.LayoutParams(w, h, Gravity.CENTER); }
    private ImageButton btn(int r) { ImageButton b = new ImageButton(this); b.setImageResource(r); b.setColorFilter(Color.WHITE); b.setBackgroundColor(Color.TRANSPARENT); b.setPadding(dp(7), dp(7), dp(7), dp(7)); return b; }

    @Override public void onBackPressed() { if (recording) stopRecording(); finish(); }
    @Override protected void onResume() { super.onResume(); hideSystemUI(); if (player != null) player.play(); }
    @Override protected void onPause() { if (player != null) player.pause(); super.onPause(); }
    @Override protected void onDestroy() { if (recording) stopRecording(); handler.removeCallbacksAndMessages(null); releasePlayer();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) { try { ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback); } catch (Exception e) {} } super.onDestroy(); }
}
