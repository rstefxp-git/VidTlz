package nc.marques;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;
import com.arthenica.ffmpegkit.StreamInformation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    static final int REQ_OPEN = 1, REQ_PERM = 2, REQ_ALL = 3;
    static final double SCRUB_MS = 300000; // largeur d'écran = 5 min

    interface P { String t(Statistics s); }
    interface D { void d(boolean ok); }

    FrameLayout root;
    TextureView tv;
    TextView hud, work;
    View gear;
    MediaPlayer mp;
    Surface surf;
    Uri uri;
    String name = "", base = "", path;
    int vw, vh;
    long dur;
    double fps = 25, pos;
    boolean ready, paused, resumePlay;
    final TreeSet<Long> marks = new TreeSet<>();
    SharedPreferences sp;
    final Handler h = new Handler(Looper.getMainLooper());

    boolean workOn, cancel;
    String workLabel = "", workInfo = "";
    long workT0, workEnd;
    int endTok, tickN;
    String flash = "";
    long flashUntil;

    float x0, y0;
    int mode, repDir;
    double base0;
    boolean repeating;
    long lastSeek;
    final Runnable rep = new Runnable() {
        public void run() {
            repeating = true;
            step(repDir);
            h.postDelayed(this, 80);
        }
    };
    final Runnable tick = new Runnable() {
        public void run() {
            updateHud();
            if (workOn) updateWork();
            if (++tickN % 13 == 0 && ready) savePos();
            h.postDelayed(this, 150);
        }
    };

    // ---------------------------------------------------------------- cycle
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("m", 0);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        tv = new TextureView(this);
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
                surf = new Surface(st);
                if (mp != null) {
                    try {
                        mp.setSurface(surf);
                        if (ready) {
                            mp.seekTo((long) Math.round(pos), MediaPlayer.SEEK_CLOSEST);
                            if (resumePlay) { resumePlay = false; paused = false; mp.start(); }
                        }
                    } catch (Exception e) { }
                }
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) { }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (mp != null) {
                    try {
                        if (ready && !paused) {
                            pos = mp.getCurrentPosition();
                            mp.pause();
                            paused = true;
                            resumePlay = true;
                        }
                        mp.setSurface(null);
                    } catch (Exception e) { }
                }
                if (surf != null) { surf.release(); surf = null; }
                return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });

        hud = new TextView(this);
        hud.setTextColor(0xCCFFFFFF);
        hud.setTextSize(12);
        hud.setBackgroundColor(0x66000000);
        hud.setPadding(dp(6), dp(2), dp(6), dp(2));
        root.addView(hud, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        work = new TextView(this);
        work.setTextColor(Color.WHITE);
        work.setTextSize(14);
        work.setBackgroundColor(0xCC000000);
        work.setPadding(dp(10), dp(8), dp(10), dp(8));
        work.setVisibility(View.GONE);
        work.setOnClickListener(v -> {
            if (workOn) { cancel = true; FFmpegKit.cancel(); workInfo = "annulation…"; }
        });
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        wl.leftMargin = dp(72);
        wl.rightMargin = dp(8);
        wl.bottomMargin = dp(12);
        root.addView(work, wl);

        gear = new GearView(this);
        gear.setOnClickListener(v -> menu());
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.START);
        gl.leftMargin = dp(8);
        gl.bottomMargin = dp(8);
        root.addView(gear, gl);

        setContentView(root);
        root.setOnTouchListener(this::touch);
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, orr, ob) -> fit());
        h.post(tick);

        Intent in = getIntent();
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            openVideo(in.getData(), 0, true);
            return;
        }
        boolean needAccess = !hasAll();
        String last = sp.getString("uri", null);
        if (last != null) {
            try {
                long p = sp.getLong("pos", 0);
                openVideo(Uri.parse(last), p, b == null);
            } catch (Exception e) { uri = null; }
        }
        if (needAccess) askAccess();
        else if (uri == null) pickVideo();
    }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null)
            openVideo(in.getData(), 0, true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePos();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        savePos();
        if (mp != null) { try { mp.release(); } catch (Exception e) { } mp = null; }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        super.onActivityResult(req, res, d);
        if (req == REQ_OPEN && res == RESULT_OK && d != null && d.getData() != null) {
            Uri u = d.getData();
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception e) { }
            openVideo(u, 0, true);
        } else if (req == REQ_ALL) {
            if (uri != null) { path = pathOf(uri); loadMarks(); }
            else pickVideo();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_PERM && uri == null) pickVideo();
    }

    // ---------------------------------------------------------------- accès fichiers
    boolean hasAll() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    void askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (sp.getBoolean("asked", false)) { if (uri == null) pickVideo(); return; }
            sp.edit().putBoolean("asked", true).apply();
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_ALL);
            } catch (Exception e) { if (uri == null) pickVideo(); }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }
    }

    void openAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_ALL);
            } catch (Exception e) {
                try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL); }
                catch (Exception e2) { toast("Réglage introuvable"); }
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }
    }

    void pickVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, REQ_OPEN); } catch (Exception e) { toast("Aucun sélecteur de fichiers"); }
    }

    String pathOf(Uri u) {
        try {
            if ("file".equals(u.getScheme())) return u.getPath();
            if (DocumentsContract.isDocumentUri(this, u)) {
                String auth = u.getAuthority();
                String id = DocumentsContract.getDocumentId(u);
                if ("com.android.externalstorage.documents".equals(auth)) {
                    String[] p = id.split(":", 2);
                    if (p.length < 2) return null;
                    if ("primary".equalsIgnoreCase(p[0])) return Environment.getExternalStorageDirectory() + "/" + p[1];
                    return "/storage/" + p[0] + "/" + p[1];
                }
                if ("com.android.providers.downloads.documents".equals(auth) && id.startsWith("raw:"))
                    return id.substring(4);
                if ("com.android.providers.media.documents".equals(auth)) {
                    String[] p = id.split(":", 2);
                    if (p.length == 2) {
                        String d = dataOf(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Long.parseLong(p[1])));
                        if (d != null) return d;
                    }
                }
            }
            return dataOf(u);
        } catch (Exception e) { }
        return null;
    }

    String dataOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{"_data"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String d = c.getString(0);
                if (d != null && d.startsWith("/")) return d;
            }
        } catch (Exception e) { }
        return null;
    }

    String nameOf(Uri u) {
        String n = null;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) n = c.getString(i);
            }
        } catch (Exception e) { }
        if (n == null) {
            n = u.getLastPathSegment();
            if (n == null) n = "video";
            int s = n.lastIndexOf('/');
            if (s >= 0) n = n.substring(s + 1);
        }
        return n;
    }

    /** Dossier de sortie : à côté de la vidéo si possible, sinon dossier de l'appli. */
    File videoDir() {
        if (path != null && hasAll()) {
            File p = new File(path).getParentFile();
            if (p != null && p.canWrite()) return p;
        }
        File d = getExternalFilesDir(null);
        return d != null ? d : getFilesDir();
    }

    String in() {
        if (path != null && new File(path).canRead()) return path;
        return FFmpegKitConfig.getSafParameterForRead(this, uri);
    }

    // ---------------------------------------------------------------- vidéo
    void release() {
        ready = false;
        if (mp != null) { try { mp.release(); } catch (Exception e) { } mp = null; }
        marks.clear();
        paused = false;
        resumePlay = false;
        pos = 0;
        dur = 0;
        vw = vh = 0;
    }

    void openVideo(Uri u, long start, boolean useMarks) {
        release();
        uri = u;
        name = nameOf(u);
        base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        path = pathOf(u);
        loadMarks();
        final long st = (useMarks && !marks.isEmpty()) ? marks.last() : start;
        try {
            mp = new MediaPlayer();
            mp.setDataSource(this, u);
            if (surf != null) mp.setSurface(surf);
            mp.setOnVideoSizeChangedListener((m, w, hh) -> { vw = w; vh = hh; fit(); });
            mp.setOnCompletionListener(m -> { pos = dur; paused = true; });
            mp.setOnErrorListener((m, w, x) -> { toast("Erreur de lecture"); return true; });
            mp.setOnPreparedListener(m -> {
                ready = true;
                dur = m.getDuration();
                probeFps();
                pos = st;
                paused = false;
                m.seekTo(st, MediaPlayer.SEEK_CLOSEST);
                m.start();
            });
            mp.prepareAsync();
            sp.edit().putString("uri", u.toString()).apply();
        } catch (Exception e) {
            toast("Ouverture impossible");
            uri = null;
        }
    }

    void probeFps() {
        fps = 25;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(this, uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) {
                    double v = 0;
                    try { v = f.getFloat(MediaFormat.KEY_FRAME_RATE); }
                    catch (Exception e) {
                        try { v = f.getInteger(MediaFormat.KEY_FRAME_RATE); } catch (Exception e2) { }
                    }
                    if (v > 1 && v <= 240) fps = v;
                    break;
                }
            }
        } catch (Exception e) {
        } finally {
            ex.release();
        }
    }

    void fit() {
        int W = root.getWidth(), H = root.getHeight();
        if (vw <= 0 || vh <= 0 || W <= 0 || H <= 0) return;
        float s = Math.min((float) W / vw, (float) H / vh);
        tv.setLayoutParams(new FrameLayout.LayoutParams(Math.round(vw * s), Math.round(vh * s), Gravity.CENTER));
    }

    long curPos() {
        if (!ready) return 0;
        return paused ? Math.round(pos) : mp.getCurrentPosition();
    }

    void savePos() {
        if (uri == null) return;
        SharedPreferences.Editor e = sp.edit().putString("uri", uri.toString());
        if (ready) e.putLong("pos", curPos());
        e.apply();
    }

    void togglePlay() {
        if (!ready) return;
        if (!paused && mp.isPlaying()) {
            pos = mp.getCurrentPosition();
            mp.pause();
            paused = true;
        } else {
            if (pos >= dur - 50) mp.seekTo(0, MediaPlayer.SEEK_CLOSEST);
            paused = false;
            mp.start();
        }
    }

    void step(int dir) {
        if (!ready) return;
        if (!paused) {
            pos = mp.getCurrentPosition();
            mp.pause();
            paused = true;
        }
        pos = Math.max(0, Math.min(dur, pos + dir * 1000.0 / fps));
        mp.seekTo((long) Math.round(pos), MediaPlayer.SEEK_CLOSEST);
    }

    void scrubTo(double t, boolean fin) {
        t = Math.max(0, Math.min(dur, t));
        if (paused) pos = t;
        mp.seekTo((long) t, fin ? MediaPlayer.SEEK_CLOSEST : MediaPlayer.SEEK_CLOSEST_SYNC);
    }

    // ---------------------------------------------------------------- gestes
    boolean touch(View v, MotionEvent e) {
        int W = root.getWidth();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                x0 = e.getX();
                y0 = e.getY();
                mode = 0;
                repeating = false;
                if (ready) {
                    base0 = curPos();
                    if (x0 < W / 3f) { repDir = -1; h.postDelayed(rep, 400); }
                    else if (x0 > W * 2f / 3f) { repDir = 1; h.postDelayed(rep, 400); }
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - x0, dy = e.getY() - y0;
                if (mode == 0 && (Math.abs(dx) > dp(12) || Math.abs(dy) > dp(12))) {
                    mode = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                    h.removeCallbacks(rep);
                }
                if (mode == 1 && ready) {
                    long now = e.getEventTime();
                    if (now - lastSeek > 100) { lastSeek = now; scrubTo(base0 + dx / W * SCRUB_MS, false); }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                h.removeCallbacks(rep);
                float dx = e.getX() - x0, dy = e.getY() - y0;
                if (mode == 1 && ready) scrubTo(base0 + dx / W * SCRUB_MS, true);
                else if (mode == 2) {
                    if (dy < -dp(60)) mark();
                    else if (dy > dp(60)) saveFrame();
                } else if (!repeating && ready) {
                    if (x0 < W / 3f) step(-1);
                    else if (x0 > W * 2f / 3f) step(1);
                    else togglePlay();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(rep);
                return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- marques
    static final Pattern TS = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{3})");

    static String fmt(long ms) {
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    File marksFile() { return new File(videoDir(), base + ".txt"); }

    void loadMarks() {
        List<File> fs = new ArrayList<>();
        if (path != null) {
            File p = new File(path).getParentFile();
            if (p != null) fs.add(new File(p, base + ".txt"));
        }
        File d = getExternalFilesDir(null);
        fs.add(new File(d != null ? d : getFilesDir(), base + ".txt"));
        for (File f : fs) {
            if (!f.isFile() || !f.canRead()) continue;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String l;
                while ((l = r.readLine()) != null) {
                    Matcher m = TS.matcher(l);
                    if (m.find()) {
                        marks.add(Long.parseLong(m.group(1)) * 3600000L + Long.parseLong(m.group(2)) * 60000L
                                + Long.parseLong(m.group(3)) * 1000L + Long.parseLong(m.group(4)));
                    }
                }
            } catch (Exception e) { }
        }
    }

    void saveMarks() {
        try (FileWriter w = new FileWriter(marksFile(), false)) {
            for (long m : marks) w.write(fmt(m) + "\n");
        } catch (Exception e) { toast("Écriture .txt impossible"); }
    }

    void mark() {
        if (!ready) return;
        long m = Math.round(curPos());
        marks.add(m);
        saveMarks();
        flash("★ " + fmt(m) + " (" + marks.size() + ")");
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    void flash(String s) { flash = s; flashUntil = System.currentTimeMillis() + 2000; }

    // ---------------------------------------------------------------- images
    volatile String lastErr = "";
    volatile File lastDir;

    File appDir() {
        File d = getExternalFilesDir(null);
        return d != null ? d : getFilesDir();
    }

    String outPath() { return lastDir == null ? "" : lastDir.getAbsolutePath(); }

    MediaMetadataRetriever retr() {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try { if (path != null && new File(path).canRead()) { r.setDataSource(path); return r; } } catch (Exception e) { }
        try { r.setDataSource(this, uri); return r; }
        catch (Exception e) { lastErr = "lecture impossible : " + e.getMessage(); }
        try { r.release(); } catch (Exception e) { }
        return null;
    }

    boolean grab(MediaMetadataRetriever r, long ms, File dir, Bitmap fb) {
        Bitmap b = null;
        if (r != null) {
            try { b = r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST); }
            catch (Exception e) { lastErr = String.valueOf(e.getMessage()); }
            if (b == null) {
                try { b = r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC); } catch (Exception e) { }
            }
        }
        if (b == null) b = fb;
        if (b == null) { lastErr = "image non décodée"; return false; }
        String fn = base + "_" + fmt(ms).replace(':', '-').replace('.', '_') + ".png";
        File[] ds = {dir, new File(appDir(), base + "_img")};
        for (File d : ds) {
            try {
                if (!d.isDirectory() && !d.mkdirs()) { lastErr = "dossier non créé : " + d; continue; }
                boolean okc;
                try (FileOutputStream o = new FileOutputStream(new File(d, fn))) {
                    okc = b.compress(Bitmap.CompressFormat.PNG, 100, o);
                }
                if (!okc) { lastErr = "compression PNG"; continue; }
                lastDir = d;
                return true;
            } catch (Exception e) { lastErr = String.valueOf(e.getMessage()); }
        }
        return false;
    }

    void saveFrame() {
        if (!ready || !begin("Image")) return;
        final long ms = Math.round(curPos());
        final File dir = new File(videoDir(), base + "_img");
        Bitmap s0 = null;
        try { if (tv.isAvailable()) s0 = tv.getBitmap(); } catch (Exception e) { }
        final Bitmap snap = s0;
        new Thread(() -> {
            MediaMetadataRetriever r = retr();
            final boolean ok = grab(r, ms, dir, snap);
            try { if (r != null) r.release(); } catch (Exception e) { }
            runOnUiThread(() -> endWork(ok ? "✔ 1/1 → " + outPath() : "échec : " + lastErr));
        }).start();
    }

    void exportMarks() {
        if (!ready) return;
        if (marks.isEmpty()) { toast("Aucune marque"); return; }
        if (!begin("Marques → PNG")) return;
        final Long[] ms = marks.toArray(new Long[0]);
        final File dir = new File(videoDir(), base + "_img");
        new Thread(() -> {
            MediaMetadataRetriever r = retr();
            int okc = 0;
            for (int i = 0; i < ms.length && !cancel; i++) {
                final int n = i + 1;
                runOnUiThread(() -> setInfo(n + " / " + ms.length));
                if (grab(r, ms[i], dir, null)) okc++;
            }
            try { if (r != null) r.release(); } catch (Exception e) { }
            final int fo = okc;
            runOnUiThread(() -> endWork((cancel ? "annulé " : "✔ ") + fo + "/" + ms.length
                    + (fo < ms.length ? " (" + lastErr + ")" : "") + " → " + outPath()));
        }).start();
    }

    // ---------------------------------------------------------------- témoin de travail
    boolean begin(String label) {
        if (workOn) { toast("Action en cours"); return false; }
        lastErr = "";
        lastDir = null;
        if (!hasAll()) toast("Accès fichiers absent : sortie dans le dossier de l'appli (⚙ → Accès fichiers)");
        workLabel = label;
        workInfo = "";
        workT0 = SystemClock.elapsedRealtime();
        workOn = true;
        cancel = false;
        endTok++;
        work.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateWork();
        return true;
    }

    void setInfo(String s) { workInfo = s; updateWork(); }

    void endWork(String msg) {
        workOn = false;
        workInfo = msg;
        workEnd = SystemClock.elapsedRealtime();
        updateWork();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final int tk = ++endTok;
        h.postDelayed(() -> { if (tk == endTok && !workOn) work.setVisibility(View.GONE); }, 8000);
    }

    void updateWork() {
        long el = ((workOn ? SystemClock.elapsedRealtime() : workEnd) - workT0) / 1000;
        work.setText(workLabel + " · " + workInfo + " · " + String.format(Locale.US, "%02d:%02d", el / 60, el % 60)
                + (workOn ? "  ✕" : ""));
    }

    void updateHud() {
        String s;
        if (!ready) s = uri == null ? "⚙ → Ouvrir une vidéo" : "…";
        else s = fmt(curPos()) + " / " + fmt(dur) + "  ★" + marks.size() + (paused ? "  ❚❚" : "");
        if (System.currentTimeMillis() < flashUntil) s += "   " + flash;
        hud.setText(s);
    }

    // ---------------------------------------------------------------- ffmpeg
    void run(String[] a, final P p, final D d) {
        FFmpegKit.executeWithArgumentsAsync(a,
                ses -> {
                    final boolean ok = ReturnCode.isSuccess(ses.getReturnCode());
                    lastErr = ok ? "" : tail(ses.getOutput());
                    runOnUiThread(() -> d.d(ok));
                },
                lg -> { },
                st -> {
                    final String t = p.t(st);
                    runOnUiThread(() -> setInfo(t));
                });
    }

    String pct(Statistics s) {
        return dur > 0 ? Math.min(99, (int) (s.getTime() * 100.0 / dur)) + "%" : "";
    }

    String res(boolean ok, String good) { return cancel ? "annulé" : ok ? good : "échec : " + lastErr; }

    static String tail(String o) {
        if (o == null) return "";
        String[] L = o.split("\\n");
        for (int i = L.length - 1; i >= 0; i--) {
            String t = L[i].trim();
            if (!t.isEmpty()) return t.length() > 140 ? t.substring(t.length() - 140) : t;
        }
        return "";
    }

    // --- Extracteur
    void extractor() {
        if (!ready || !begin("Extracteur")) return;
        setInfo("analyse…");
        final String inp = in();
        final File dir = new File(videoDir(), base + "_streams");
        dir.mkdirs();
        new Thread(() -> {
            MediaInformationSession ms = FFprobeKit.getMediaInformation(inp);
            MediaInformation mi = ms.getMediaInformation();
            if (mi == null) { runOnUiThread(() -> endWork("analyse impossible")); return; }
            final List<StreamInformation> L = new ArrayList<>();
            for (StreamInformation s : mi.getStreams()) {
                String t = s.getType();
                if ("video".equals(t) || "audio".equals(t) || "subtitle".equals(t)) L.add(s);
            }
            runOnUiThread(() -> nextStream(inp, dir, L, 0, 0));
        }).start();
    }

    void nextStream(final String inp, final File dir, final List<StreamInformation> L, final int k, final int okc) {
        if (cancel || k >= L.size()) {
            endWork((cancel ? "annulé " : "✔ ") + okc + "/" + L.size() + " flux → " + dir.getAbsolutePath());
            return;
        }
        StreamInformation s = L.get(k);
        String type = s.getType();
        String codec = s.getCodec() == null ? "" : s.getCodec();
        long idx = s.getIndex();
        String ext = "mkv";
        List<String> cc = new ArrayList<>();
        cc.add("-c");
        cc.add("copy");
        if ("audio".equals(type)) {
            switch (codec) {
                case "aac": ext = "m4a"; break;
                case "mp3": ext = "mp3"; break;
                case "opus": ext = "opus"; break;
                case "vorbis": ext = "ogg"; break;
                case "flac": ext = "flac"; break;
                case "ac3": ext = "ac3"; break;
                case "eac3": ext = "eac3"; break;
                case "dts": ext = "dts"; break;
                default: ext = "mka";
            }
        } else if ("subtitle".equals(type)) {
            switch (codec) {
                case "subrip": case "srt": ext = "srt"; break;
                case "ass": case "ssa": ext = "ass"; break;
                case "mov_text": case "webvtt": case "text":
                    ext = "mks"; break;
                case "hdmv_pgs_subtitle": ext = "sup"; break;
                default: ext = "mks";
            }
        }
        File out = new File(dir, String.format(Locale.US, "%02d_%s_%s.%s", idx, type, codec, ext));
        List<String> a = new ArrayList<>();
        a.add("-y"); a.add("-i"); a.add(inp); a.add("-map"); a.add("0:" + idx);
        a.addAll(cc);
        a.add(out.getAbsolutePath());
        final String lab = "flux " + (k + 1) + "/" + L.size() + " (" + type + " " + codec + ")";
        setInfo(lab);
        run(a.toArray(new String[0]), st -> lab + "  " + pct(st),
                ok -> nextStream(inp, dir, L, k + 1, okc + (ok ? 1 : 0)));
    }

    // --- All frames
    void frames() {
        if (!ready) return;
        new AlertDialog.Builder(this).setTitle("All frames → sous-dossier")
                .setItems(new String[]{"Passthrough (fps d'origine)", "Frame drop (fps personnalisé)"},
                        (d, w) -> { if (w == 0) doFrames(0); else askFps(); })
                .show();
    }

    void askFps() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText("5");
        new AlertDialog.Builder(this).setTitle("fps").setView(et)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        double f = Double.parseDouble(et.getText().toString().replace(',', '.'));
                        if (f > 0) doFrames(f);
                    } catch (Exception e) { }
                })
                .setNegativeButton("Annuler", null).show();
    }

    /** Extraction native (MediaMetadataRetriever) : le build ffmpeg n'a pas d'encodeur PNG/JPEG. */
    void doFrames(final double f) {
        if (!ready || !begin("All frames")) return;
        final double rate = f > 0 ? f : fps;
        final long durMs = dur;
        final File want = new File(videoDir(), base + "_frames");
        new Thread(() -> {
            MediaMetadataRetriever r = retr();
            if (r == null) { runOnUiThread(() -> endWork("échec : " + lastErr)); return; }
            File d = want;
            if (!d.isDirectory() && !d.mkdirs()) d = new File(appDir(), base + "_frames");
            d.mkdirs();
            lastDir = d;
            boolean byIndex = f <= 0 && Build.VERSION.SDK_INT >= 28;
            long total = Math.round(durMs / 1000.0 * rate);
            if (byIndex) {
                try {
                    int c = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
                    if (c > 0) total = c; else byIndex = false;
                } catch (Exception e) { byIndex = false; }
            }
            final long tot = total;
            int okc = 0;
            long lastUi = 0;
            for (int i = 0; i < tot && !cancel; i++) {
                Bitmap b = null;
                try {
                    b = byIndex ? r.getFrameAtIndex(i)
                            : r.getFrameAtTime(Math.round(i * 1e6 / rate), MediaMetadataRetriever.OPTION_CLOSEST);
                } catch (Exception e) { lastErr = String.valueOf(e.getMessage()); }
                if (b != null) {
                    try (FileOutputStream o = new FileOutputStream(new File(d, String.format(Locale.US, "f_%06d.png", i + 1)))) {
                        if (b.compress(Bitmap.CompressFormat.PNG, 100, o)) okc++;
                    } catch (Exception e) { lastErr = String.valueOf(e.getMessage()); }
                    b.recycle();
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastUi > 120) {
                    lastUi = now;
                    final String t = (i + 1) + " / " + tot + "  " + (tot > 0 ? (i + 1) * 100 / tot : 0) + "%";
                    runOnUiThread(() -> setInfo(t));
                }
            }
            try { r.release(); } catch (Exception e) { }
            final int fo = okc;
            final String where = d.getAbsolutePath();
            runOnUiThread(() -> endWork(cancel ? "annulé " + fo + " images → " + where
                    : fo > 0 ? "✔ " + fo + "/" + tot + " images → " + where : "échec : " + lastErr));
        }).start();
    }

    // --- Marques → chapitres
    void chapters() {
        if (!ready) return;
        if (marks.isEmpty()) { toast("Aucune marque"); return; }
        if (!begin("Marques → chapitres")) return;
        File meta = new File(getCacheDir(), "chap.txt");
        Long[] m = marks.toArray(new Long[0]);
        StringBuilder sb = new StringBuilder(";FFMETADATA1\n");
        for (int i = 0; i < m.length; i++) {
            long s = m[i];
            long e = i + 1 < m.length ? m[i + 1] : Math.max(dur, s + 1);
            sb.append("[CHAPTER]\nTIMEBASE=1/1000\nSTART=").append(s).append("\nEND=").append(e)
                    .append("\ntitle=Marque ").append(i + 1).append(" - ").append(fmt(s)).append("\n");
        }
        try (FileWriter w = new FileWriter(meta, false)) { w.write(sb.toString()); }
        catch (Exception e) { endWork("échec"); return; }
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "mkv";
        if (!ext.equals("mp4") && !ext.equals("m4v") && !ext.equals("mov") && !ext.equals("mkv")) ext = "mkv";
        final File out = new File(videoDir(), base + "_chap." + ext);
        String[] a = {"-y", "-i", in(), "-i", meta.getAbsolutePath(), "-map", "0", "-dn", "-ignore_unknown",
                "-map_metadata", "0", "-map_chapters", "1", "-c", "copy", out.getAbsolutePath()};
        final int n = m.length;
        setInfo(n + " chapitres");
        run(a, st -> n + " chapitres  " + pct(st),
                ok -> endWork(res(ok, "✔ " + n + " chapitres → " + out.getAbsolutePath())));
    }

    // ---------------------------------------------------------------- menu
    void menu() {
        new AlertDialog.Builder(this)
                .setItems(new String[]{
                        "Ouvrir une vidéo",
                        "Extracteur (flux du fichier)",
                        "All frames → sous-dossier",
                        "Marques → chapitres",
                        "Marques → images PNG",
                        "Accès fichiers : " + (hasAll() ? "OK" : "NON — régler")},
                        (d, w) -> {
                            switch (w) {
                                case 0: pickVideo(); break;
                                case 1: extractor(); break;
                                case 2: frames(); break;
                                case 3: chapters(); break;
                                case 4: exportMarks(); break;
                                case 5: openAccess(); break;
                            }
                        })
                .show();
    }

    // ---------------------------------------------------------------- utilitaires
    int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    static class GearView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        GearView(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight(), cx = w / 2, cy = hh / 2, r = Math.min(w, hh) * 0.22f;
            p.setColor(0xB0B0B0B0);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.55f);
            c.drawCircle(cx, cy, r, p);
            p.setStrokeWidth(r * 0.5f);
            p.setStrokeCap(Paint.Cap.BUTT);
            for (int i = 0; i < 8; i++) {
                c.save();
                c.rotate(i * 45, cx, cy);
                c.drawLine(cx, cy - r, cx, cy - r * 1.75f, p);
                c.restore();
            }
        }
    }
}
