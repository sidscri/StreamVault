package com.sidscri.streamvault;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
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
    private TextView titleText, statusText, nowNextText, strengthText, errorText;
    private ImageButton playPauseBtn, lockBtn, recordBtn;
    private Handler handler;
    private Runnable hideOverlayRunnable, failoverTimeoutRunnable, strengthUpdater;
    private boolean overlayVisible = false, locked = false, networkAvailable = true;
    private volatile boolean recording = false;
    private String lastSuccessUrl = null;
    private long playbackStartTime = 0, foTimeoutMs = 15000, recordStartMs = 0;
    private boolean foAuto = true;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Thread recordThread;
    private int bufferSec = 30; // default 30s buffer

    private final List<Variant> variants = new ArrayList<>();
    private int currentIdx = 0;

    static class Variant {
        final String url, title, region, tag;
        Variant(String u, String t, String r, String tg) {
            this.url = u != null ? u : ""; this.title = t != null ? t : "";
            this.region = r != null ? r : ""; this.tag = tg != null ? tg : "";
        }
    }

    @Override
    protected void onCreate(Bundle b) {
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

        // Load buffer setting
        try {
            SharedPreferences sp = getSharedPreferences("sv_prefs", MODE_PRIVATE);
            bufferSec = Integer.parseInt(sp.getString("bufferSec", "30"));
            if (bufferSec < 5) bufferSec = 5;
            if (bufferSec > 120) bufferSec = 120;
        } catch (Exception e) { bufferSec = 30; }

        parseIntent();
        if (variants.isEmpty()) { finish(); return; }
        buildUI();
        registerNetworkCallback();
        startStrengthMonitor();
        playVariant(0);
        hideSystemUI();
    }

    private void parseIntent() {
        foTimeoutMs = getIntent().getIntExtra(EXTRA_FO_TIMEOUT, 15) * 1000L;
        foAuto = getIntent().getBooleanExtra(EXTRA_FO_AUTO, true);
        String json = getIntent().getStringExtra(EXTRA_FAILOVER_JSON);
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    variants.add(new Variant(o.optString("url",""), o.optString("title",""), o.optString("region",""), o.optString("tag","")));
                }
            } catch (Exception e) {}
        }
        if (variants.isEmpty()) {
            String u = getIntent().getStringExtra(EXTRA_URL);
            if (u != null && !u.isEmpty()) {
                String t = getIntent().getStringExtra(EXTRA_TITLE);
                variants.add(new Variant(u, t != null ? t : "Stream", "", ""));
            }
        }
    }

    // ─── UI (same as Build 11) ───
    private void buildUI() {
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
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
        titleText = tv(Color.WHITE, 12, true); ta.addView(titleText);
        statusText = tv(Color.parseColor("#80ffffff"), 8, false); ta.addView(statusText);
        nowNextText = tv(Color.parseColor("#6c5ce7"), 8, false);
        String nn = getIntent().getStringExtra(EXTRA_NOW_NEXT); if (nn != null && !nn.isEmpty()) nowNextText.setText(nn); ta.addView(nowNextText);
        strengthText = tv(Color.parseColor("#60ffffff"), 7, false); ta.addView(strengthText);
        t.addView(ta, new LinearLayout.LayoutParams(0, -2, 1));
        recordBtn = mkBtn(android.R.drawable.ic_btn_speak_now); recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
        recordBtn.setOnClickListener(v -> toggleRecording()); t.addView(recordBtn);
        lockBtn = mkBtn(android.R.drawable.ic_lock_idle_lock); lockBtn.setColorFilter(Color.parseColor("#80ffffff"));
        lockBtn.setOnClickListener(v -> { locked = !locked; lockBtn.setColorFilter(locked ? Color.parseColor("#ffa502") : Color.parseColor("#80ffffff")); msg(locked ? "🔒 Locked" : "🔓 Unlocked"); schedHide(); });
        t.addView(lockBtn); return t;
    }

    private View buildCenter() {
        LinearLayout c = new LinearLayout(this); c.setGravity(Gravity.CENTER);
        playPauseBtn = new ImageButton(this); playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        playPauseBtn.setColorFilter(Color.WHITE); playPauseBtn.setBackgroundColor(Color.parseColor("#6c5ce7"));
        playPauseBtn.setPadding(dp(15), dp(15), dp(15), dp(15));
        playPauseBtn.setOnClickListener(v -> { if (player != null) { if (player.isPlaying()) player.pause(); else player.play(); updPP(); schedHide(); } });
        c.addView(playPauseBtn); return c;
    }

    private View buildBot() {
        LinearLayout b = new LinearLayout(this); b.setPadding(dp(10), dp(8), dp(10), dp(24));
        b.setBackgroundColor(Color.parseColor("#CC000000")); b.setGravity(Gravity.CENTER);
        TextView info = tv(Color.parseColor("#40ffffff"), 7, false); info.setGravity(Gravity.CENTER);
        info.setText("Src 1/" + variants.size() + " · Buf " + bufferSec + "s"); b.addView(info); return b;
    }

    private View buildErrView() {
        LinearLayout e = new LinearLayout(this); e.setOrientation(LinearLayout.VERTICAL); e.setGravity(Gravity.CENTER); e.setVisibility(View.GONE);
        errorText = tv(Color.parseColor("#aaa"), 12, false); errorText.setGravity(Gravity.CENTER); errorText.setPadding(dp(20), 0, dp(20), dp(10)); e.addView(errorText);
        TextView retry = new TextView(this); retry.setText("Tap to Retry"); retry.setTextColor(Color.parseColor("#6c5ce7")); retry.setTextSize(13);
        retry.setPadding(dp(16), dp(8), dp(16), dp(8)); retry.setBackgroundColor(Color.parseColor("#1a1a2e"));
        retry.setOnClickListener(v -> { errorContainer.setVisibility(View.GONE); loadingView.setVisibility(View.VISIBLE); playVariant(currentIdx); });
        e.addView(retry); return e;
    }

    // ─── Playback with configurable buffer ───
    private void playVariant(int idx) {
        if (idx < 0 || idx >= variants.size()) {
            if (lastSuccessUrl != null) { for (int i = 0; i < variants.size(); i++) if (lastSuccessUrl.equals(variants.get(i).url)) { playVariant(i); return; } }
            loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("All " + variants.size() + " failed"); return;
        }
        currentIdx = idx; Variant v = variants.get(idx); releasePlayer();
        loadingView.setVisibility(View.VISIBLE); errorContainer.setVisibility(View.GONE);
        String disp = v.title.isEmpty() ? "Source " + (idx + 1) : v.title; if (!v.tag.isEmpty()) disp += " (" + v.tag + ")";
        titleText.setText(disp); statusText.setText("Src " + (idx+1) + "/" + variants.size() + (locked ? " 🔒" : "")); strengthText.setText("");
        if (idx > 0) msg("Trying: " + disp);

        DataSource.Factory hf = new DefaultHttpDataSource.Factory()
            .setUserAgent("StreamVault/4.3 ExoPlayer").setConnectTimeoutMs(12000).setReadTimeoutMs(12000).setAllowCrossProtocolRedirects(true);

        // Configurable buffer
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferSec * 1000,          // min buffer
                bufferSec * 2 * 1000,      // max buffer
                2500,                       // playback start buffer
                5000                        // rebuffer
            ).build();

        player = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        playerView.setPlayer(player); playbackStartTime = System.currentTimeMillis();
        MediaSource hls = new HlsMediaSource.Factory(hf).setAllowChunklessPreparation(true).createMediaSource(MediaItem.fromUri(Uri.parse(v.url)));
        final boolean[] hlsFail = {false};
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.GONE); lastSuccessUrl = v.url; cancelFT(); }
                else if (state == Player.STATE_BUFFERING) { loadingView.setVisibility(View.VISIBLE); if (!locked && foAuto) startFT(); }
            }
            @Override public void onPlayerError(PlaybackException error) {
                cancelFT();
                if (!hlsFail[0]) { hlsFail[0] = true; try { player.stop(); player.setMediaSource(new ProgressiveMediaSource.Factory(hf).createMediaSource(MediaItem.fromUri(Uri.parse(v.url)))); player.prepare(); player.setPlayWhenReady(true); } catch (Exception ex) { tryNext(); } return; }
                if (!locked && foAuto && networkAvailable) tryNext(); else if (!networkAvailable) { msg("Network lost"); loadingView.setVisibility(View.VISIBLE); }
                else { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("Failed"); }
            }
        });
        player.setMediaSource(hls); player.setPlayWhenReady(true); player.prepare(); showBrief();
    }

    private void tryNext() { if (locked) return; if (currentIdx+1 < variants.size()) playVariant(currentIdx+1); else if (lastSuccessUrl != null) { for (int i=0;i<variants.size();i++) if (lastSuccessUrl.equals(variants.get(i).url)){playVariant(i);return;} showFail(); } else showFail(); }
    private void showFail() { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("All failed"); }
    private void startFT() { cancelFT(); failoverTimeoutRunnable = () -> { if (player!=null&&player.getPlaybackState()==Player.STATE_BUFFERING&&!locked&&foAuto&&networkAvailable&&System.currentTimeMillis()-playbackStartTime>8000){msg("Buffering…switching");tryNext();}}; handler.postDelayed(failoverTimeoutRunnable,foTimeoutMs); }
    private void cancelFT() { if (failoverTimeoutRunnable!=null) handler.removeCallbacks(failoverTimeoutRunnable); }

    // ─── Stream Strength ───
    private void startStrengthMonitor() {
        strengthUpdater = new Runnable() {
            @Override public void run() {
                if (player != null && player.getPlaybackState() == Player.STATE_READY) {
                    long br = 0; Format vf = player.getVideoFormat(); if (vf != null && vf.bitrate > 0) br = vf.bitrate;
                    long buf = player.getBufferedPosition() - player.getCurrentPosition();
                    String label; int color;
                    if (buf > 10000 && br > 2000000) { label="●●●●"; color=Color.parseColor("#2ed573"); }
                    else if (buf > 5000) { label="●●●○"; color=Color.parseColor("#2ed573"); }
                    else if (buf > 2000) { label="●●○○"; color=Color.parseColor("#ffa502"); }
                    else { label="●○○○"; color=Color.parseColor("#ff4757"); }
                    String d = label + (br>0?" "+br/1000+"k":"") + " " + buf/1000 + "s";
                    if (recording) { long el=(System.currentTimeMillis()-recordStartMs)/1000; d+=" ⏺"+String.format(Locale.US,"%02d:%02d",el/60,el%60); }
                    strengthText.setText(d); strengthText.setTextColor(color);
                }
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(strengthUpdater, 3000);
    }

    // ─── HLS Recording (downloads actual .ts segments) ───
    private void toggleRecording() { if (recording) stopRecording(); else startRecording(); }

    private void startRecording() {
        if (currentIdx >= variants.size()) return;
        recording = true; recordStartMs = System.currentTimeMillis();
        recordBtn.setColorFilter(Color.parseColor("#ff4757")); msg("⏺ Recording"); schedHide();
        final String streamUrl = variants.get(currentIdx).url;
        final String safeName = variants.get(currentIdx).title.replaceAll("[^a-zA-Z0-9_-]", "_");
        final String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

        // Get save path from prefs
        String sp = "";
        try { sp = getSharedPreferences("sv_prefs", MODE_PRIVATE).getString("savePath", ""); } catch (Exception e) {}
        final String savePath = sp;

        recordThread = new Thread(() -> {
            FileOutputStream fos = null;
            long totalBytes = 0;
            try {
                File dir = (savePath != null && !savePath.isEmpty()) ? new File(savePath)
                    : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File outFile = new File(dir, "SV_" + safeName + "_" + ts + ".ts");
                fos = new FileOutputStream(outFile);

                // Probe: is this HLS or raw?
                HttpURLConnection probe = (HttpURLConnection) new URL(streamUrl).openConnection();
                probe.setRequestProperty("User-Agent", "StreamVault/4.3"); probe.setConnectTimeout(10000);
                InputStream probeIn = probe.getInputStream();
                byte[] peek = new byte[512]; int peekLen = probeIn.read(peek);
                String peekStr = peekLen > 0 ? new String(peek, 0, peekLen) : "";
                boolean isHLS = peekStr.contains("#EXTM3U") || peekStr.contains("#EXT-X-") || streamUrl.contains(".m3u8");

                if (!isHLS) {
                    // Raw stream — just pipe it
                    fos.write(peek, 0, Math.max(peekLen, 0)); totalBytes += Math.max(peekLen, 0);
                    byte[] buf = new byte[16384]; int n;
                    while (recording && (n = probeIn.read(buf)) != -1) { fos.write(buf, 0, n); totalBytes += n; }
                    probeIn.close(); fos.close();
                } else {
                    probeIn.close(); probe.disconnect();
                    // HLS — download segments in a loop
                    String baseUrl = streamUrl.substring(0, streamUrl.lastIndexOf('/') + 1);
                    Set<String> done = new HashSet<>();
                    while (recording) {
                        try {
                            List<String> segs = fetchSegmentUrls(streamUrl, baseUrl);
                            for (String segUrl : segs) {
                                if (!recording) break;
                                if (done.contains(segUrl)) continue;
                                done.add(segUrl);
                                try {
                                    HttpURLConnection sc = (HttpURLConnection) new URL(segUrl).openConnection();
                                    sc.setRequestProperty("User-Agent", "StreamVault/4.3"); sc.setConnectTimeout(8000); sc.setReadTimeout(15000);
                                    InputStream si = sc.getInputStream(); byte[] buf = new byte[16384]; int n;
                                    while (recording && (n = si.read(buf)) != -1) { fos.write(buf, 0, n); totalBytes += n; }
                                    si.close(); sc.disconnect();
                                } catch (Exception se) { /* skip bad segment */ }
                            }
                            if (recording) Thread.sleep(3000);
                        } catch (Exception le) { if (recording) Thread.sleep(2000); }
                    }
                    fos.close();
                }
                final long fb = totalBytes; final String fn = outFile.getName();
                handler.post(() -> msg("⏹ " + fn + " (" + (fb > 1048576 ? fb/1048576+"MB" : fb/1024+"KB") + ")"));
            } catch (Exception e) {
                if (fos != null) try { fos.close(); } catch (Exception x) {}
                handler.post(() -> msg("Rec error: " + e.getMessage()));
            }
            handler.post(() -> { recording = false; recordStartMs = 0; recordBtn.setColorFilter(Color.parseColor("#80ffffff")); });
        });
        recordThread.start();
    }

    private List<String> fetchSegmentUrls(String playlistUrl, String baseUrl) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(playlistUrl).openConnection();
        c.setRequestProperty("User-Agent", "StreamVault/4.3"); c.setConnectTimeout(8000);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        List<String> segs = new ArrayList<>(); String mediaUrl = null; String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (!line.startsWith("#") && !line.isEmpty()) {
                if (line.endsWith(".m3u8") || line.contains(".m3u8?")) { mediaUrl = resolve(baseUrl, line); }
                else { segs.add(resolve(baseUrl, line)); }
            }
        }
        r.close(); c.disconnect();
        // If master playlist, fetch media playlist
        if (segs.isEmpty() && mediaUrl != null) {
            String mBase = mediaUrl.substring(0, mediaUrl.lastIndexOf('/') + 1);
            HttpURLConnection mc = (HttpURLConnection) new URL(mediaUrl).openConnection();
            mc.setRequestProperty("User-Agent", "StreamVault/4.3");
            BufferedReader mr = new BufferedReader(new InputStreamReader(mc.getInputStream()));
            while ((line = mr.readLine()) != null) { line = line.trim(); if (!line.startsWith("#") && !line.isEmpty()) segs.add(resolve(mBase, line)); }
            mr.close(); mc.disconnect();
        }
        return segs;
    }

    private String resolve(String base, String rel) {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel;
        if (rel.startsWith("/")) { try { URL u = new URL(base); return u.getProtocol()+"://"+u.getHost()+(u.getPort()>0?":"+u.getPort():"")+rel; } catch (Exception e) {} }
        return base + rel;
    }

    private void stopRecording() { recording = false; recordBtn.setColorFilter(Color.parseColor("#80ffffff")); msg("⏹ Stopping…"); }

    // ─── Network ───
    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network n) { handler.post(() -> { boolean was=!networkAvailable; networkAvailable=true; if(was&&player!=null&&player.getPlaybackState()==Player.STATE_IDLE){msg("Network back");playVariant(currentIdx);}}); }
                    @Override public void onLost(Network n) { handler.post(() -> { networkAvailable=false; cancelFT(); msg("Network lost"); }); }
                };
                cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback);
            } catch (Exception e) {}
        }
    }

    // ─── Helpers ───
    private void updPP() { playPauseBtn.setImageResource(player!=null&&player.isPlaying()?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play); }
    private void toggleOverlay() { setOverlayVisible(!overlayVisible); if(overlayVisible)schedHide(); }
    private void showBrief() { setOverlayVisible(true); schedHide(); }
    private void setOverlayVisible(boolean v) { overlayVisible=v; float a=v?1f:0f; overlayTop.animate().alpha(a).setDuration(200).start(); overlayCenter.animate().alpha(a).setDuration(200).start(); overlayBottom.animate().alpha(a).setDuration(200).start(); }
    private void schedHide() { handler.removeCallbacks(hideOverlayRunnable); handler.postDelayed(hideOverlayRunnable,4000); }
    private void msg(String m) { runOnUiThread(()->Toast.makeText(this,m,Toast.LENGTH_SHORT).show()); }
    private void hideSystemUI() { getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY); }
    private void releasePlayer() { if(player!=null){player.stop();player.release();player=null;} }
    private int dp(int v) { return(int)(v*getResources().getDisplayMetrics().density); }
    private FrameLayout.LayoutParams mp() { return new FrameLayout.LayoutParams(-1,-1); }
    private FrameLayout.LayoutParams ctr(int w,int h) { return new FrameLayout.LayoutParams(w,h,Gravity.CENTER); }
    private ImageButton mkBtn(int r) { ImageButton b=new ImageButton(this);b.setImageResource(r);b.setColorFilter(Color.WHITE);b.setBackgroundColor(Color.TRANSPARENT);b.setPadding(dp(8),dp(8),dp(8),dp(8));return b; }
    private TextView tv(int color, int size, boolean bold) { TextView t=new TextView(this);t.setTextColor(color);t.setTextSize(size);t.setSingleLine(true);if(bold)t.setTypeface(null,Typeface.BOLD);return t; }

    @Override public void onBackPressed() { if(recording)stopRecording(); finish(); }
    @Override protected void onResume() { super.onResume(); hideSystemUI(); if(player!=null)player.play(); }
    @Override protected void onPause() { if(player!=null)player.pause(); super.onPause(); }
    @Override protected void onDestroy() { if(recording)recording=false; handler.removeCallbacksAndMessages(null); releasePlayer();
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N&&networkCallback!=null){try{((ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);}catch(Exception e){}} super.onDestroy(); }
}
