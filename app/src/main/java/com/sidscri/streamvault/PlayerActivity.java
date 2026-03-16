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
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_TITLE = "stream_title";
    public static final String EXTRA_CATEGORY = "stream_category";
    public static final String EXTRA_FAILOVER_JSON = "failover_json";
    public static final String EXTRA_NOW_NEXT = "now_next";
    public static final String EXTRA_FO_TIMEOUT = "fo_timeout";
    public static final String EXTRA_FO_AUTO = "fo_auto";
    public static final String EXTRA_SAVE_PATH = "save_path";

    private ExoPlayer player;
    private PlayerView playerView;
    private View loadingView, errorContainer, overlayTop, overlayCenter, overlayBottom;
    private TextView titleText, statusText, nowNextText, strengthText, errorText, recStatusText;
    private ImageButton playPauseBtn, lockBtn, recordBtn;
    private Handler handler;
    private Runnable hideOverlayRunnable, failoverTimeoutRunnable, strengthUpdater;
    private boolean overlayVisible = false, locked = false, networkAvailable = true;
    private volatile boolean recording = false;
    private String lastSuccessUrl = null, savePath = null;
    private long playbackStartTime = 0, foTimeoutMs = 15000, recordingStartTime = 0;
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
    protected void onCreate(Bundle b) {
        try {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
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
        } catch (Throwable t) {
            Toast.makeText(this, "Player start failed: " + t.getClass().getSimpleName() + (t.getMessage() != null ? " - " + t.getMessage() : ""), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void parseIntent() {
        foTimeoutMs = getIntent().getIntExtra(EXTRA_FO_TIMEOUT, 15) * 1000L;
        foAuto = getIntent().getBooleanExtra(EXTRA_FO_AUTO, true);
        savePath = getIntent().getStringExtra(EXTRA_SAVE_PATH);
        String json = getIntent().getStringExtra(EXTRA_FAILOVER_JSON);
        if (json == null || json.isEmpty()) {
            try {
                json = getSharedPreferences("streamvault_runtime", MODE_PRIVATE)
                    .getString("pending_failover_json", "[]");
            } catch (Exception e) {
                json = "[]";
            }
        }
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String normalized = normalizeUrl(o.optString("url", ""));
                    if (!normalized.isEmpty()) variants.add(new Variant(normalized, o.optString("title",""), o.optString("region",""), o.optString("tag","")));
                }
            } catch (Exception e) {}
        }
        if (variants.isEmpty()) {
            String u = getIntent().getStringExtra(EXTRA_URL);
            if (u != null && !u.isEmpty()) {
                String t = getIntent().getStringExtra(EXTRA_TITLE);
                String normalized = normalizeUrl(u);
                if (!normalized.isEmpty()) variants.add(new Variant(normalized, t != null ? t : "Stream", "", ""));
            }
        }
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this); playerView.setUseController(false); playerView.setKeepScreenOn(true);
        root.addView(playerView, mp());
        loadingView = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        root.addView(loadingView, ctr(dp(38), dp(38)));
        errorContainer = buildErrView(); root.addView(errorContainer, ctr(-2, -2));
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
        ImageButton bk = mkBtn(android.R.drawable.ic_media_previous); bk.setOnClickListener(v -> finish()); t.addView(bk);
        LinearLayout ta = new LinearLayout(this); ta.setOrientation(LinearLayout.VERTICAL); ta.setPadding(dp(6), 0, 0, 0);
        titleText = new TextView(this); titleText.setTextColor(Color.WHITE); titleText.setTextSize(12); titleText.setSingleLine(true); titleText.setTypeface(null, Typeface.BOLD); ta.addView(titleText);
        statusText = new TextView(this); statusText.setTextColor(Color.parseColor("#80ffffff")); statusText.setTextSize(8); statusText.setSingleLine(true); ta.addView(statusText);
        nowNextText = new TextView(this); nowNextText.setTextColor(Color.parseColor("#6c5ce7")); nowNextText.setTextSize(8); nowNextText.setSingleLine(true);
        String nn = getIntent().getStringExtra(EXTRA_NOW_NEXT); if (nn != null && !nn.isEmpty()) nowNextText.setText(nn); ta.addView(nowNextText);
        strengthText = new TextView(this); strengthText.setTextColor(Color.parseColor("#60ffffff")); strengthText.setTextSize(7); strengthText.setSingleLine(true); ta.addView(strengthText);
        // Recording status line
        recStatusText = new TextView(this); recStatusText.setTextColor(Color.parseColor("#ff4757")); recStatusText.setTextSize(7); recStatusText.setSingleLine(true); ta.addView(recStatusText);
        t.addView(ta, new LinearLayout.LayoutParams(0, -2, 1));
        recordBtn = mkBtn(android.R.drawable.ic_btn_speak_now); recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
        recordBtn.setOnClickListener(v -> toggleRecording()); t.addView(recordBtn);
        lockBtn = mkBtn(android.R.drawable.ic_lock_idle_lock); lockBtn.setColorFilter(Color.parseColor("#80ffffff"));
        lockBtn.setOnClickListener(v -> { locked = !locked; lockBtn.setColorFilter(locked ? Color.parseColor("#ffa502") : Color.parseColor("#80ffffff")); msg(locked ? "🔒 Locked" : "🔓 Unlocked"); schedHide(); });
        t.addView(lockBtn);
        return t;
    }

    private View buildCenter() {
        LinearLayout c = new LinearLayout(this); c.setGravity(Gravity.CENTER);
        playPauseBtn = new ImageButton(this); playPauseBtn.setImageResource(android.R.drawable.ic_media_pause); playPauseBtn.setColorFilter(Color.WHITE);
        playPauseBtn.setBackgroundColor(Color.parseColor("#6c5ce7")); playPauseBtn.setPadding(dp(15), dp(15), dp(15), dp(15));
        playPauseBtn.setOnClickListener(v -> { if (player != null) { if (player.isPlaying()) player.pause(); else player.play(); updPP(); schedHide(); } });
        c.addView(playPauseBtn); return c;
    }

    private View buildBot() {
        LinearLayout b = new LinearLayout(this); b.setPadding(dp(10), dp(8), dp(10), dp(24)); b.setBackgroundColor(Color.parseColor("#CC000000")); b.setGravity(Gravity.CENTER);
        TextView info = new TextView(this); info.setTextColor(Color.parseColor("#40ffffff")); info.setTextSize(7); info.setSingleLine(true); info.setGravity(Gravity.CENTER);
        info.setText("Source 1/" + variants.size()); b.addView(info); return b;
    }

    private View buildErrView() {
        LinearLayout e = new LinearLayout(this); e.setOrientation(LinearLayout.VERTICAL); e.setGravity(Gravity.CENTER); e.setVisibility(View.GONE);
        errorText = new TextView(this); errorText.setTextColor(Color.parseColor("#aaa")); errorText.setTextSize(12); errorText.setGravity(Gravity.CENTER); errorText.setPadding(dp(20), 0, dp(20), dp(10)); e.addView(errorText);
        TextView retry = new TextView(this); retry.setText("Tap to Retry"); retry.setTextColor(Color.parseColor("#6c5ce7")); retry.setTextSize(13);
        retry.setPadding(dp(16), dp(8), dp(16), dp(8)); retry.setBackgroundColor(Color.parseColor("#1a1a2e"));
        retry.setOnClickListener(v -> { errorContainer.setVisibility(View.GONE); loadingView.setVisibility(View.VISIBLE); playVariant(currentIdx); }); e.addView(retry);
        return e;
    }

    // ─── Playback ───
    private String normalizeUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        s = s.replace("&amp;", "&");
        if (s.endsWith("@top")) s = s.substring(0, s.length() - 4);
        try {
            Uri parsed = Uri.parse(s);
            String host = parsed.getHost();
            if (host != null && host.contains("webplayer.online")) {
                String inner = parsed.getQueryParameter("url");
                if (inner != null && !inner.trim().isEmpty()) s = inner.trim();
            }
        } catch (Exception ignored) {}
        return s;
    }

    private boolean isSupportedUrl(String raw) {
        try {
            Uri uri = Uri.parse(normalizeUrl(raw));
            String scheme = uri.getScheme();
            return scheme != null && (
                "http".equalsIgnoreCase(scheme) ||
                "https".equalsIgnoreCase(scheme) ||
                "file".equalsIgnoreCase(scheme) ||
                "content".equalsIgnoreCase(scheme) ||
                "rtsp".equalsIgnoreCase(scheme)
            );
        } catch (Exception e) {
            return false;
        }
    }

    private MediaSource buildMediaSource(DefaultHttpDataSource.Factory hf, String rawUrl) {
        String url = normalizeUrl(rawUrl);
        Uri uri = Uri.parse(url);
        MediaItem.Builder item = new MediaItem.Builder().setUri(uri);
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains(".m3u8") || lower.contains("format=m3u8") || lower.contains("type=hls")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        return new DefaultMediaSourceFactory(hf).createMediaSource(item.build());
    }

    private void playVariant(int idx) {
        if (idx < 0 || idx >= variants.size()) {
            if (lastSuccessUrl != null) { msg("Retrying last working"); for (int i = 0; i < variants.size(); i++) if (lastSuccessUrl.equals(variants.get(i).url)) { playVariant(i); return; } }
            showFail(); return;
        }
        currentIdx = idx; Variant v = variants.get(idx);
        String normalizedUrl = normalizeUrl(v.url);
        if (!isSupportedUrl(normalizedUrl)) {
            if (!locked && foAuto) { tryNext(); return; }
            loadingView.setVisibility(View.GONE);
            errorContainer.setVisibility(View.VISIBLE);
            errorText.setText("Bad stream URL");
            msg("Unsupported stream URL");
            return;
        }
        titleText.setText(v.title.isEmpty() ? "Stream" : v.title);
        String meta = "Variant " + (idx + 1) + "/" + variants.size(); if (!v.region.isEmpty()) meta += " · " + v.region; if (!v.tag.isEmpty()) meta += " · " + v.tag; statusText.setText(meta);
        loadingView.setVisibility(View.VISIBLE); errorContainer.setVisibility(View.GONE); cancelFT(); releasePlayer();
        DefaultHttpDataSource.Factory hf = new DefaultHttpDataSource.Factory().setUserAgent("StreamVault/4.3 ExoPlayer").setConnectTimeoutMs(12000).setReadTimeoutMs(12000).setAllowCrossProtocolRedirects(true);
        player = new ExoPlayer.Builder(this).build(); playerView.setPlayer(player); playbackStartTime = System.currentTimeMillis();
        final boolean[] retried = {false};
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.GONE); lastSuccessUrl = normalizedUrl; cancelFT(); }
                else if (state == Player.STATE_BUFFERING) { loadingView.setVisibility(View.VISIBLE); if (!locked && foAuto) startFT(); }
            }
            @Override public void onPlayerError(PlaybackException error) {
                cancelFT();
                if (!retried[0]) {
                    retried[0] = true;
                    try {
                        player.stop();
                        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(normalizedUrl)).setMimeType(MimeTypes.APPLICATION_M3U8);
                        player.setMediaSource(new DefaultMediaSourceFactory(hf).createMediaSource(item.build()));
                        player.prepare();
                        player.setPlayWhenReady(true);
                        return;
                    } catch (Exception ex) {
                        // fall through to failover
                    }
                }
                if (!locked && foAuto && networkAvailable) tryNext(); else if (!networkAvailable) { msg("Network lost"); loadingView.setVisibility(View.VISIBLE); }
                else { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("Failed"); }
            }
        });
        try {
            player.setMediaSource(buildMediaSource(hf, normalizedUrl));
            player.setPlayWhenReady(true);
            player.prepare();
            showBrief();
        } catch (IllegalArgumentException iae) {
            if (!locked && foAuto) { tryNext(); return; }
            loadingView.setVisibility(View.GONE);
            errorContainer.setVisibility(View.VISIBLE);
            errorText.setText("Bad stream URL");
            msg("Player input rejected");
        }
    }

    private void tryNext() { if (locked) return; if (currentIdx + 1 < variants.size()) playVariant(currentIdx + 1); else if (lastSuccessUrl != null) { for (int i = 0; i < variants.size(); i++) if (lastSuccessUrl.equals(variants.get(i).url)) { playVariant(i); return; } showFail(); } else showFail(); }
    private void showFail() { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("All failed"); }
    private void startFT() { cancelFT(); failoverTimeoutRunnable = () -> { if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING && !locked && foAuto && networkAvailable && System.currentTimeMillis() - playbackStartTime > 8000) { msg("Buffering…switching"); tryNext(); } }; handler.postDelayed(failoverTimeoutRunnable, foTimeoutMs); }
    private void cancelFT() { if (failoverTimeoutRunnable != null) handler.removeCallbacks(failoverTimeoutRunnable); }

    // ─── Stream Strength ───
    private void startStrengthMonitor() {
        strengthUpdater = new Runnable() {
            @Override public void run() {
                if (player != null && player.getPlaybackState() == Player.STATE_READY) {
                    long br = 0; Format vf = player.getVideoFormat(); if (vf != null && vf.bitrate > 0) br = vf.bitrate;
                    long buf = player.getBufferedPosition() - player.getCurrentPosition();
                    String label; int color;
                    if (buf > 10000 && br > 2000000) { label = "●●●● Excellent"; color = Color.parseColor("#2ed573"); }
                    else if (buf > 5000) { label = "●●●○ Good"; color = Color.parseColor("#2ed573"); }
                    else if (buf > 2000) { label = "●●○○ Fair"; color = Color.parseColor("#ffa502"); }
                    else { label = "●○○○ Weak"; color = Color.parseColor("#ff4757"); }
                    String d = label + (br > 0 ? " · " + (br/1000) + "k" : "") + (buf > 0 ? " · " + (buf/1000) + "s" : "");
                    strengthText.setText(d); strengthText.setTextColor(color);
                }
                // Update recording timer
                if (recording && recordingStartTime > 0) {
                    long elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000;
                    long mins = elapsed / 60; long secs = elapsed % 60;
                    recStatusText.setText("⏺ REC " + String.format(Locale.US, "%02d:%02d", mins, secs));
                } else {
                    recStatusText.setText("");
                }
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(strengthUpdater, 3000);
    }

    // ═══ HLS RECORDING — downloads actual video segments ═══
    private void toggleRecording() { if (recording) stopRecording(); else startRecording(); }

    private void startRecording() {
        if (currentIdx >= variants.size()) return;
        recording = true;
        recordingStartTime = System.currentTimeMillis();
        recordBtn.setColorFilter(Color.parseColor("#ff4757"));
        msg("⏺ Recording started");
        schedHide();

        final String streamUrl = variants.get(currentIdx).url;
        final String safeName = variants.get(currentIdx).title.replaceAll("[^a-zA-Z0-9_-]", "_");
        final String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

        recordThread = new Thread(() -> {
            FileOutputStream fos = null;
            try {
                // Determine save directory
                File dir;
                if (savePath != null && !savePath.isEmpty()) {
                    dir = new File(savePath);
                } else {
                    dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                }
                if (!dir.exists()) dir.mkdirs();
                File outFile = new File(dir, "SV_" + safeName + "_" + ts + ".ts");
                fos = new FileOutputStream(outFile);
                long totalBytes = 0;

                // First, check if URL returns an HLS manifest or raw stream
                HttpURLConnection probe = (HttpURLConnection) new URL(streamUrl).openConnection();
                probe.setRequestProperty("User-Agent", "StreamVault/4.2");
                probe.setConnectTimeout(10000);
                probe.setReadTimeout(10000);

                String contentType = probe.getContentType();
                boolean isHLS = (contentType != null && (contentType.contains("mpegurl") || contentType.contains("m3u")))
                    || streamUrl.contains(".m3u8");

                if (!isHLS) {
                    // Check first bytes
                    InputStream probeIn = probe.getInputStream();
                    byte[] peek = new byte[256];
                    int peekLen = probeIn.read(peek);
                    String peekStr = new String(peek, 0, Math.max(peekLen, 0));
                    if (peekStr.contains("#EXTM3U") || peekStr.contains("#EXT-X-")) {
                        isHLS = true;
                    } else {
                        // It's a raw stream — just download directly
                        fos.write(peek, 0, peekLen);
                        totalBytes += peekLen;
                        byte[] buf = new byte[16384];
                        int n;
                        while (recording && (n = probeIn.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                            totalBytes += n;
                        }
                        probeIn.close();
                        fos.close();
                        final long finalBytes = totalBytes;
                        handler.post(() -> msg("⏹ Saved: " + outFile.getName() + " (" + (finalBytes / 1024) + " KB)"));
                        return;
                    }
                    probeIn.close();
                }
                probe.disconnect();

                // HLS recording: download segments
                Set<String> downloadedSegments = new HashSet<>();
                String baseUrl = streamUrl.substring(0, streamUrl.lastIndexOf('/') + 1);

                while (recording) {
                    try {
                        // Fetch the playlist
                        HttpURLConnection m3uConn = (HttpURLConnection) new URL(streamUrl).openConnection();
                        m3uConn.setRequestProperty("User-Agent", "StreamVault/4.2");
                        m3uConn.setConnectTimeout(8000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(m3uConn.getInputStream()));
                        List<String> segmentUrls = new ArrayList<>();
                        String line;
                        String mediaPlaylistUrl = null;

                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            // Check if this is a master playlist (contains other playlists)
                            if (line.endsWith(".m3u8") && !line.startsWith("#")) {
                                mediaPlaylistUrl = resolveUrl(baseUrl, line);
                            }
                            // Collect .ts segment URLs
                            if (!line.startsWith("#") && (line.endsWith(".ts") || line.contains(".ts?") || line.contains("/seg") || (!line.startsWith("#") && !line.isEmpty() && !line.contains(".m3u")))) {
                                if (!line.startsWith("#") && !line.isEmpty() && !line.endsWith(".m3u8")) {
                                    segmentUrls.add(resolveUrl(baseUrl, line));
                                }
                            }
                        }
                        reader.close();
                        m3uConn.disconnect();

                        // If master playlist, fetch the first media playlist
                        if (segmentUrls.isEmpty() && mediaPlaylistUrl != null) {
                            String mpBase = mediaPlaylistUrl.substring(0, mediaPlaylistUrl.lastIndexOf('/') + 1);
                            HttpURLConnection mpConn = (HttpURLConnection) new URL(mediaPlaylistUrl).openConnection();
                            mpConn.setRequestProperty("User-Agent", "StreamVault/4.2");
                            BufferedReader mpReader = new BufferedReader(new InputStreamReader(mpConn.getInputStream()));
                            while ((line = mpReader.readLine()) != null) {
                                line = line.trim();
                                if (!line.startsWith("#") && !line.isEmpty()) {
                                    segmentUrls.add(resolveUrl(mpBase, line));
                                }
                            }
                            mpReader.close();
                            mpConn.disconnect();
                        }

                        // Download new segments
                        for (String segUrl : segmentUrls) {
                            if (!recording) break;
                            if (downloadedSegments.contains(segUrl)) continue;
                            downloadedSegments.add(segUrl);

                            try {
                                HttpURLConnection segConn = (HttpURLConnection) new URL(segUrl).openConnection();
                                segConn.setRequestProperty("User-Agent", "StreamVault/4.2");
                                segConn.setConnectTimeout(8000);
                                segConn.setReadTimeout(15000);
                                InputStream segIn = segConn.getInputStream();
                                byte[] buf = new byte[16384];
                                int n;
                                while (recording && (n = segIn.read(buf)) != -1) {
                                    fos.write(buf, 0, n);
                                    totalBytes += n;
                                }
                                segIn.close();
                                segConn.disconnect();
                            } catch (Exception segE) {
                                // Skip bad segment, continue
                            }
                        }

                        // Wait before re-fetching playlist (HLS typically updates every 2-6 seconds)
                        if (recording) Thread.sleep(3000);

                    } catch (Exception loopE) {
                        if (recording) Thread.sleep(2000);
                    }
                }

                fos.close();
                final long finalBytes = totalBytes;
                final String fileName = outFile.getName();
                handler.post(() -> msg("⏹ Saved: " + fileName + " (" + (finalBytes / 1048576) + " MB)"));

            } catch (Exception e) {
                if (fos != null) try { fos.close(); } catch (Exception x) {}
                handler.post(() -> msg("Record error: " + e.getMessage()));
            }
            handler.post(() -> { recording = false; recordingStartTime = 0; recordBtn.setColorFilter(Color.parseColor("#80ffffff")); recStatusText.setText(""); });
        });
        recordThread.start();
    }

    private String resolveUrl(String base, String relative) {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative;
        if (relative.startsWith("/")) {
            try { URL u = new URL(base); return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + relative; }
            catch (Exception e) { return base + relative; }
        }
        return base + relative;
    }

    private void stopRecording() {
        recording = false;
        recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
        msg("⏹ Stopping recording…");
    }

    // ─── Network ───
    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network n) { handler.post(() -> { boolean was = !networkAvailable; networkAvailable = true; if (was && player != null && player.getPlaybackState() == Player.STATE_IDLE) { msg("Network back"); playVariant(currentIdx); } }); }
                    @Override public void onLost(Network n) { handler.post(() -> { networkAvailable = false; cancelFT(); msg("Network lost"); }); }
                };
                cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback);
            } catch (Exception e) {}
        }
    }

    // ─── UI ───
    private void updPP() { playPauseBtn.setImageResource(player != null && player.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play); }
    private void toggleOverlay() { setOverlayVisible(!overlayVisible); if (overlayVisible) schedHide(); }
    private void showBrief() { setOverlayVisible(true); schedHide(); }
    private void setOverlayVisible(boolean v) { overlayVisible = v; float a = v ? 1f : 0f; overlayTop.animate().alpha(a).setDuration(200).start(); overlayCenter.animate().alpha(a).setDuration(200).start(); overlayBottom.animate().alpha(a).setDuration(200).start(); }
    private void schedHide() { handler.removeCallbacks(hideOverlayRunnable); handler.postDelayed(hideOverlayRunnable, 4000); }
    private void msg(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show()); }
    private void hideSystemUI() { getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY); }
    private void releasePlayer() { if (player != null) { player.stop(); player.release(); player = null; } }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
    private FrameLayout.LayoutParams mp() { return new FrameLayout.LayoutParams(-1,-1); }
    private FrameLayout.LayoutParams ctr(int w, int h) { return new FrameLayout.LayoutParams(w,h,Gravity.CENTER); }
    private ImageButton mkBtn(int r) { ImageButton b = new ImageButton(this); b.setImageResource(r); b.setColorFilter(Color.WHITE); b.setBackgroundColor(Color.TRANSPARENT); b.setPadding(dp(8),dp(8),dp(8),dp(8)); return b; }

    @Override public void onBackPressed() { if (recording) stopRecording(); finish(); }
    @Override protected void onResume() { super.onResume(); hideSystemUI(); if (player != null) player.play(); }
    @Override protected void onPause() { if (player != null) player.pause(); super.onPause(); }
    @Override protected void onDestroy() { if (recording) { recording = false; } handler.removeCallbacksAndMessages(null); releasePlayer();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) { try { ((ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback); } catch (Exception e) {} } super.onDestroy(); }
}
