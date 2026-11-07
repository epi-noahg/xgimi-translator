package fr.traducteur.xgimi;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service d'accessibilité qui traduit les écrans XGIMI en français.
 *
 * Deux modes :
 *  - TEXTE : l'écran expose ses textes (fenêtres classiques) -> lecture directe.
 *  - OCR   : l'écran est dessiné comme une image (Paramètres XGIMI, "CellView")
 *            -> capture d'écran, reconnaissance du chinois (ML Kit, hors ligne),
 *               puis traduction avec le dictionnaire.
 */
public class TranslateService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String TAG = "TraducteurXGIMI";
    static final String PREFS = "prefs";
    static final String KEY_ON = "on";
    static final String KEY_COLORS = "colors";
    /** Le traducteur a déjà été activé une fois : on peut le relancer automatiquement. */
    static final String KEY_WANTED = "wanted";
    /** Langue d'affichage : "fr" ou "en". */
    static final String KEY_LANG = "lang";

    static String language(android.content.Context c) {
        String l = c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LANG, "fr");
        return "en".equals(l) ? "en" : "fr";
    }

    private static volatile TranslateService instance;

    /** Vrai si le service tourne dans ce processus. */
    static boolean isAlive() {
        return instance != null;
    }

    private static final int MAX_NODES = 2500;

    private Dictionary dict;
    private String loadedLang = "fr";

    private Dictionary loadDictionary(String lang) {
        try (InputStream in = getAssets().open("dictionnaire_zh_" + lang + ".tsv")) {
            Dictionary d = Dictionary.load(in);
            Journal.log("dictionnaire " + lang + " : " + d.size() + " entrées");
            return d;
        } catch (Throwable e) {
            Journal.log("dictionnaire illisible", e);
            return new Dictionary();
        }
    }

    /** Change de langue : recharge le dictionnaire en arrière-plan et oublie les traductions affichées. */
    private void switchLanguage(final String lang) {
        loadedLang = lang;
        new Thread(() -> {
            final Dictionary d = loadDictionary(lang);
            handler.post(() -> {
                if (!lang.equals(loadedLang)) return;
                dict = d;
                screenCache.clear();
                colorCache.clear();
                lastLabelsKey = "";
                if (overlay != null) overlay.clear();
                setOcrMode(false);
                scheduleRefresh();
            });
        }, "langue").start();
    }
    private OverlayView overlay;
    private WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean enabled = true;
    private boolean useScreenshotColors = true;

    // ------------------------------------------------------------------ captures d'écran

    /** Reçoit une capture ; elle ne doit être utilisée que pendant l'appel (elle est libérée après). */
    private interface ShotCallback {
        void onShot(Bitmap bmp);
    }

    private final ArrayList<ShotCallback> shotQueue = new ArrayList<>();
    private boolean shotInFlight = false;
    private long lastShotAt = 0;
    private int shotFailures = 0;
    private boolean shotsUnavailable = false;
    private final Runnable pumpShotsRunnable = this::pumpShots;

    // ------------------------------------------------------------------ mode TEXTE

    private final Map<String, int[]> colorCache = new LinkedHashMap<String, int[]>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
            return size() > 800;
        }
    };
    private final HashMap<String, Float> sizeCache = new HashMap<>();
    private boolean colorShotScheduled = false;

    private static final class Pending {
        final String key;
        final Rect rect;
        final boolean focused;

        Pending(String key, Rect rect, boolean focused) {
            this.key = key;
            this.rect = rect;
            this.focused = focused;
        }
    }

    private List<Pending> pending = new ArrayList<>();

    // ------------------------------------------------------------------ mode OCR

    private TextRecognizer recognizer;
    private boolean ocrMode = false;
    private boolean ocrBusy = false;
    private boolean ocrAgain = false;
    private int ocrGeneration = 0;
    private long lastKeyAt = 0;
    private int[] refSignature = null; // aspect de l'écran (avec nos étiquettes) après la dernière traduction
    private boolean ocrFromWatch = false;  // la traduction en cours a été lancée par la surveillance
    private String lastLabelsKey = "";
    private int idleRepeats = 0;           // relances inutiles d'affilée (écran animé)

    private final Runnable refreshRunnable = this::refreshNow;
    private final Runnable refreshLateRunnable = this::refreshNow;
    private final Runnable ocrRunnable = this::startOcr;
    private final Runnable watchRunnable = this::watch;

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Journal.init(this);
        Journal.log("===== service démarré ===== (Android " + Build.VERSION.SDK_INT + ")");
        instance = this;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WANTED, true).apply();
        Watchdog.scheduleChecks(this);

        // Chargement lourd (dictionnaire + moteur OCR) en arrière-plan : au démarrage du projecteur,
        // un service qui bloque trop longtemps se fait couper par le système.
        loadedLang = language(this);
        final String lang = loadedLang;
        new Thread(() -> {
            Dictionary d = loadDictionary(lang);
            TextRecognizer r = null;
            try {
                r = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                Journal.log("OCR : moteur créé");
            } catch (Throwable t) {
                Journal.log("OCR : création impossible", t);
            }
            final Dictionary fd = d;
            final TextRecognizer fr = r;
            handler.post(() -> {
                if (instance != TranslateService.this) return; // service arrêté entre-temps
                recognizer = fr;
                dict = fd;
                scheduleRefresh();
            });
        }, "chargement").start();

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new OverlayView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(overlay, lp);
            Journal.log("surimpression ajoutée");
        } catch (Exception e) {
            Journal.log("surimpression impossible", e);
            Log.e(TAG, "Impossible d'ajouter la surimpression", e);
        }

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        enabled = sp.getBoolean(KEY_ON, true);
        useScreenshotColors = sp.getBoolean(KEY_COLORS, true);
        sp.registerOnSharedPreferenceChangeListener(this);
        scheduleRefresh();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        Journal.log("service arrêté");
        handler.removeCallbacksAndMessages(null);
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(this);
        } catch (Exception ignored) {
        }
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Exception ignored) {
            }
        }
        if (overlay != null && wm != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (KEY_LANG.equals(key)) {
            String lang = language(this);
            if (!lang.equals(loadedLang)) switchLanguage(lang);
            return;
        }
        if (KEY_WANTED.equals(key)) return;
        enabled = sp.getBoolean(KEY_ON, true);
        useScreenshotColors = sp.getBoolean(KEY_COLORS, true);
        colorCache.clear();
        if (!enabled && overlay != null) overlay.clear();
        setOcrMode(false);
        scheduleRefresh();
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------------------ événements

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!enabled || dict == null || overlay == null) return;
        int type = e.getEventType();
        CharSequence pkg = e.getPackageName();
        boolean target = pkg != null && isTarget(pkg.toString());

        if (ocrMode) {
            // En mode OCR, les changements internes de l'image sont suivis par les touches et la surveillance.
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                scheduleRefresh();
                scheduleOcr(350);
            }
            return;
        }
        if (!target && overlay.isEmpty() && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return; // une autre app qui se met à jour : rien à faire
        }
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            overlay.clear();
        }
        scheduleRefresh();
    }

    /** Touches de la télécommande : en mode OCR, on retraduit après chaque appui. */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (enabled && ocrMode && overlay != null) {
            lastKeyAt = SystemClock.uptimeMillis();
            ocrFromWatch = false;
            idleRepeats = 0;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                ocrGeneration++;          // une traduction en cours serait périmée
                handler.removeCallbacks(ocrRunnable);
                overlay.clear();          // l'écran va changer
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                Journal.log("touche " + event.getKeyCode());
                scheduleOcr(260);         // le temps que l'animation se termine
            }
        }
        return false; // on ne bloque jamais la touche
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(refreshLateRunnable);
        handler.postDelayed(refreshRunnable, 90);
        handler.postDelayed(refreshLateRunnable, 500);
    }

    /** Apps dont on traduit les écrans. */
    static boolean isTarget(String pkg) {
        return pkg.startsWith("com.xgimi")
                || pkg.equals("com.android.newsettings")
                || pkg.equals("com.android.systemui")
                || pkg.equals("android");
    }

    // ------------------------------------------------------------------ lecture de l'écran (mode TEXTE)

    private String lastSummary = "";

    private void refreshNow() {
        if (overlay == null || dict == null) return;
        if (!enabled) {
            setOcrMode(false);
            overlay.clear();
            return;
        }
        List<AccessibilityWindowInfo> windows;
        try {
            windows = new ArrayList<>(getWindows());
        } catch (Exception e) {
            windows = new ArrayList<>();
        }
        Collections.sort(windows, (a, b) -> b.getLayer() - a.getLayer());

        DisplayMetrics dm = realMetrics();
        int screenArea = dm.widthPixels * dm.heightPixels;

        ArrayList<Rect> covers = new ArrayList<>();
        ArrayList<OverlayView.Label> ready = new ArrayList<>();
        ArrayList<Pending> missing = new ArrayList<>();
        int[] budget = {MAX_NODES};
        StringBuilder summary = new StringBuilder();
        boolean topAppFound = false;
        boolean wantOcr = false;

        for (AccessibilityWindowInfo w : windows) {
            int wtype = w.getType();
            if (wtype == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            Rect wb = new Rect();
            w.getBoundsInScreen(wb);
            AccessibilityNodeInfo root = null;
            try {
                root = w.getRoot();
            } catch (Exception ignored) {
            }
            CharSequence pkg = root != null ? root.getPackageName() : null;
            boolean target = root != null && pkg != null && isTarget(pkg.toString());
            int[] chineseSeen = {0};
            if (target) {
                collect(root, wb, 0, covers, ready, missing, budget, chineseSeen);
            }
            summary.append("[type=").append(wtype).append(" calque=").append(w.getLayer())
                    .append(" app=").append(pkg).append(" enfants=").append(root != null ? root.getChildCount() : -1)
                    .append(" chinois=").append(chineseSeen[0]).append(' ').append(wb.flattenToString()).append("] ");
            // Fenêtre principale = la plus haute qui occupe une bonne partie de l'écran.
            // (Les Paramètres XGIMI sont une fenêtre de type SYSTÈME, pas APPLICATION.)
            boolean big = (long) wb.width() * wb.height() >= screenArea / 4;
            if (!topAppFound && root != null && big
                    && (wtype == AccessibilityWindowInfo.TYPE_APPLICATION
                    || wtype == AccessibilityWindowInfo.TYPE_SYSTEM)) {
                topAppFound = true;
                // Fenêtre d'app XGIMI au premier plan sans aucun texte lisible : c'est une image -> OCR
                wantOcr = target && chineseSeen[0] == 0 && recognizer != null && !shotsUnavailable
                        && Build.VERSION.SDK_INT >= 30;
            }
            boolean fullScreen = (long) wb.width() * wb.height() >= screenArea * 0.95;
            if (wtype == AccessibilityWindowInfo.TYPE_APPLICATION
                    || wtype == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    || (wtype == AccessibilityWindowInfo.TYPE_SYSTEM && !fullScreen)) {
                covers.add(wb);
            }
        }

        String sum = summary.toString() + " -> ocr=" + wantOcr + " textes=" + ready.size() + "+" + missing.size();
        if (!sum.equals(lastSummary)) {
            lastSummary = sum;
            Journal.log("fenêtres : " + sum);
        }
        setOcrMode(wantOcr);
        if (ocrMode) return; // l'OCR gère l'affichage

        overlay.setLabels(ready);
        pending = missing;
        if (!missing.isEmpty() && !colorShotScheduled) {
            colorShotScheduled = true;
            // Laisse la surimpression se redessiner (sans ces étiquettes) avant la capture
            handler.postDelayed(() -> {
                colorShotScheduled = false;
                final List<Pending> todo = pending;
                if (todo.isEmpty()) return;
                shoot(bmp -> {
                    if (bmp != null) sampleColors(bmp, todo);
                    else useFallbackFor(todo);
                    refreshNow();
                });
            }, 80);
        }
    }

    private void collect(AccessibilityNodeInfo n, Rect windowBounds, int depth, List<Rect> covers,
                         List<OverlayView.Label> ready, List<Pending> missing, int[] budget, int[] chineseSeen) {
        if (n == null || depth > 60 || budget[0]-- <= 0) return;
        if (!n.isVisibleToUser()) return;

        CharSequence text = n.getText();
        if (text == null || text.length() == 0) {
            CharSequence hint = n.getHintText();
            if (hint != null && hint.length() > 0 && n.isEditable()) text = hint;
        }
        if (text != null && Dictionary.hasChinese(text)) {
            chineseSeen[0]++;
            String fr = dict.translate(text);
            if (fr != null) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.intersect(windowBounds) && r.width() > 4 && r.height() > 4 && !isCovered(r, covers)) {
                    addLabel(n, r, fr, ready, missing);
                }
            }
        }
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c;
            try {
                c = n.getChild(i);
            } catch (Exception e) {
                c = null;
            }
            collect(c, windowBounds, depth + 1, covers, ready, missing, budget, chineseSeen);
        }
    }

    private static boolean isCovered(Rect r, List<Rect> covers) {
        int cx = r.centerX();
        int cy = r.centerY();
        for (Rect c : covers) {
            if (c.contains(cx, cy)) return true;
        }
        return false;
    }

    private void addLabel(AccessibilityNodeInfo n, Rect r, String fr,
                          List<OverlayView.Label> ready, List<Pending> missing) {
        boolean focused = isFocusedRow(n);
        CharSequence cls = n.getClassName();
        boolean center = cls != null && cls.toString().endsWith("Button");
        float size = textSizeOf(n);

        String key = fr + "|" + r.flattenToString() + "|" + focused;
        int[] colors = colorCache.get(key);
        if (colors == null && (!useScreenshotColors || Build.VERSION.SDK_INT < 30 || shotsUnavailable)) {
            colors = new int[]{OverlayView.fallbackBg(focused), OverlayView.fallbackFg(focused)};
        }
        if (colors != null) {
            ready.add(new OverlayView.Label(r, fr, colors[0], colors[1], size, center));
        } else {
            missing.add(new Pending(key, new Rect(r), focused));
        }
    }

    private static boolean isFocusedRow(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isFocused() || cur.isSelected() || cur.isAccessibilityFocused()) return true;
            try {
                cur = cur.getParent();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private float textSizeOf(AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT < 30) return 0;
        String id = n.getViewIdResourceName();
        if (id != null) {
            Float s = sizeCache.get(id);
            if (s != null) return s;
        }
        float size = 0;
        try {
            List<String> extras = n.getAvailableExtraData();
            if (extras != null && extras.contains(AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY)
                    && n.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY, new Bundle())) {
                AccessibilityNodeInfo.ExtraRenderingInfo info = n.getExtraRenderingInfo();
                if (info != null) size = info.getTextSizeInPx();
            }
        } catch (Throwable ignored) {
        }
        if (id != null && size > 0) sizeCache.put(id, size);
        return size;
    }

    private void useFallbackFor(List<Pending> todo) {
        for (Pending p : todo) {
            colorCache.put(p.key, new int[]{OverlayView.fallbackBg(p.focused), OverlayView.fallbackFg(p.focused)});
        }
    }

    private void sampleColors(Bitmap bmp, List<Pending> todo) {
        DisplayMetrics dm = realMetrics();
        float sx = bmp.getWidth() / (float) Math.max(1, dm.widthPixels);
        float sy = bmp.getHeight() / (float) Math.max(1, dm.heightPixels);
        for (Pending p : todo) {
            Rect inBmp = new Rect((int) (p.rect.left * sx), (int) (p.rect.top * sy),
                    (int) (p.rect.right * sx), (int) (p.rect.bottom * sy));
            colorCache.put(p.key, measureColors(bmp, inBmp, 2));
        }
    }

    // ------------------------------------------------------------------ mode OCR

    private void setOcrMode(boolean on) {
        if (on == ocrMode) return;
        ocrMode = on;
        Journal.log("mode OCR : " + on);
        ocrGeneration++;
        refSignature = null;
        handler.removeCallbacks(ocrRunnable);
        handler.removeCallbacks(watchRunnable);
        if (overlay != null) overlay.clear();
        if (on) {
            scheduleOcr(300);
            handler.postDelayed(watchRunnable, 1500);
        }
    }

    private void scheduleOcr(long delay) {
        if (!ocrMode) return;
        handler.removeCallbacks(ocrRunnable);
        handler.postDelayed(ocrRunnable, delay);
    }

    private void startOcr() {
        if (!enabled || !ocrMode || recognizer == null) return;
        if (ocrBusy) {
            ocrAgain = true;
            return;
        }
        ocrBusy = true;
        final int gen = ++ocrGeneration;
        Journal.log("OCR : lancement n°" + gen);
        overlay.clear(); // nos étiquettes ne doivent pas apparaître sur la capture
        handler.postDelayed(() -> shoot(bmp -> {
            if (bmp == null || gen != ocrGeneration) {
                Journal.log("OCR : " + (bmp == null ? "pas de capture" : "annulé (touche pressée)"));
                finishOcr();
                return;
            }
            final long t0 = SystemClock.uptimeMillis();
            final int[] sig = signature(bmp);
            // Écran déjà vu ? On réaffiche directement la traduction mémorisée.
            for (int i = screenCache.size() - 1; i >= 0; i--) {
                CachedScreen cs = screenCache.get(i);
                if (similar(sig, cs.signature)) {
                    screenCache.remove(i);
                    screenCache.add(cs);
                    Journal.log("OCR : écran déjà vu, traduction immédiate (" + cs.labels.size() + " textes)");
                    overlay.setLabels(new ArrayList<>(cs.labels));
                    rememberOverlayLook(gen);
                    finishOcr();
                    return;
                }
            }
            // Image réduite : l'OCR est beaucoup plus rapide et le texte reste lisible.
            final Bitmap own;
            if (bmp.getWidth() > 1300) {
                own = Bitmap.createScaledBitmap(bmp, bmp.getWidth() * 3 / 4, bmp.getHeight() * 3 / 4, true);
            } else {
                own = bmp.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (own == null) {
                finishOcr();
                return;
            }
            try {
                recognizer.process(InputImage.fromBitmap(own, 0))
                        .addOnSuccessListener(text -> {
                            try {
                                Journal.log("OCR : terminé en " + (SystemClock.uptimeMillis() - t0) + " ms");
                                onOcrResult(text, own, gen, sig);
                            } catch (Throwable t) {
                                Log.w(TAG, "Erreur après OCR", t);
                                Journal.log("erreur après OCR", t);
                            }
                            own.recycle();
                            finishOcr();
                        })
                        .addOnFailureListener(e -> {
                            Log.w(TAG, "OCR en échec", e);
                            Journal.log("OCR en échec", e);
                            own.recycle();
                            finishOcr();
                        });
            } catch (Throwable t) {
                Log.w(TAG, "OCR impossible", t);
                Journal.log("OCR impossible", t);
                own.recycle();
                finishOcr();
            }
        }), 50);
    }

    // ------------------------------------------------------------------ mémoire des écrans déjà traduits

    private static final class CachedScreen {
        final int[] signature;
        final List<OverlayView.Label> labels;

        CachedScreen(int[] signature, List<OverlayView.Label> labels) {
            this.signature = signature;
            this.labels = labels;
        }
    }

    private final ArrayList<CachedScreen> screenCache = new ArrayList<>();

    /** Même écran (à quelques pixels près, ex. une horloge) ? */
    private static boolean similar(int[] a, int[] b) {
        if (a.length != b.length) return false;
        int changed = 0;
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 18) changed++;
        return changed <= a.length / 400;
    }

    /** Mémorise l'aspect de l'écran avec nos étiquettes, pour détecter les changements ensuite. */
    private void rememberOverlayLook(final int gen) {
        handler.postDelayed(() -> shoot(b -> {
            if (b != null && gen == ocrGeneration) refSignature = signature(b);
        }), 150);
    }

    private void finishOcr() {
        ocrBusy = false;
        if (ocrAgain) {
            ocrAgain = false;
            scheduleOcr(60);
        }
    }

    private void onOcrResult(Text text, Bitmap bmp, int gen, int[] sig) {
        if (gen != ocrGeneration || !ocrMode || !enabled) return;
        DisplayMetrics dm = realMetrics();
        float sx = dm.widthPixels / (float) Math.max(1, bmp.getWidth());
        float sy = dm.heightPixels / (float) Math.max(1, bmp.getHeight());

        ArrayList<OverlayView.Label> labels = new ArrayList<>();
        StringBuilder seen = new StringBuilder();
        int nLines = 0;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String s = line.getText();
                Rect box = line.getBoundingBox();
                nLines++;
                if (box == null || !Dictionary.hasChinese(s)) {
                    if (nLines <= 40) seen.append("\n      « ").append(s).append(" » (sans chinois)");
                    continue;
                }
                String fr = dict.translateFuzzy(s);
                if (nLines <= 40) seen.append("\n      « ").append(s).append(" » -> ").append(fr == null ? "?" : fr);
                if (fr != null) {
                    labels.add(ocrLabel(bmp, box, fr, sx, sy));
                    continue;
                }
                // La ligne réunit peut-être plusieurs textes : on essaie morceau par morceau.
                for (Text.Element el : line.getElements()) {
                    Rect eb = el.getBoundingBox();
                    String es = el.getText();
                    if (eb == null || !Dictionary.hasChinese(es)) continue;
                    String f2 = dict.translateFuzzy(es);
                    if (f2 != null) labels.add(ocrLabel(bmp, eb, f2, sx, sy));
                }
            }
        }
        overlay.setLabels(labels);
        Journal.log("OCR : " + nLines + " lignes lues, " + labels.size() + " traduites" + seen);
        if (labels.isEmpty()) Journal.saveCapture(bmp);
        screenCache.add(new CachedScreen(sig, new ArrayList<>(labels)));
        if (screenCache.size() > 40) screenCache.remove(0);

        StringBuilder k = new StringBuilder();
        for (OverlayView.Label l : labels) k.append(l.text).append('@').append(l.rect.top / 20).append(';');
        String key = k.toString();
        if (ocrFromWatch && key.equals(lastLabelsKey)) idleRepeats++;
        else idleRepeats = 0;
        lastLabelsKey = key;
        ocrFromWatch = false;

        rememberOverlayLook(gen);
    }

    private OverlayView.Label ocrLabel(Bitmap bmp, Rect boxInBmp, String fr, float sx, float sy) {
        int padX = Math.max(3, boxInBmp.height() / 5);
        int padY = Math.max(2, boxInBmp.height() / 8);
        Rect padded = new Rect(boxInBmp.left - padX, boxInBmp.top - padY,
                boxInBmp.right + padX, boxInBmp.bottom + padY);
        int[] colors = useScreenshotColors ? measureColors(bmp, padded, 0)
                : new int[]{OverlayView.fallbackBg(false), OverlayView.fallbackFg(false)};
        Rect onScreen = new Rect((int) (padded.left * sx), (int) (padded.top * sy),
                (int) (padded.right * sx), (int) (padded.bottom * sy));
        float size = boxInBmp.height() * sy * 0.95f;
        return new OverlayView.Label(onScreen, fr, colors[0], colors[1], size, false);
    }

    /** Vérifie régulièrement si l'écran a changé sans appui de touche (ex. valeur mise à jour). */
    private void watch() {
        if (!enabled || !ocrMode) return;
        handler.postDelayed(watchRunnable, 1500);
        if (ocrBusy || SystemClock.uptimeMillis() - lastKeyAt < 800) return;
        if (idleRepeats >= 2) return; // écran animé : on attend le prochain appui de touche
        shoot(b -> {
            if (b == null || !ocrMode || ocrBusy) return;
            int[] sig = signature(b);
            if (refSignature == null || differs(sig, refSignature)) {
                ocrFromWatch = true;
                scheduleOcr(0);
            }
        });
    }

    // ------------------------------------------------------------------ captures d'écran (file d'attente)

    private void shoot(ShotCallback cb) {
        shotQueue.add(cb);
        pumpShots();
    }

    private void pumpShots() {
        if (shotInFlight || shotQueue.isEmpty()) return;
        if (Build.VERSION.SDK_INT < 30 || shotsUnavailable) {
            deliver(new ArrayList<>(shotQueue), null);
            shotQueue.clear();
            return;
        }
        long wait = 360 - (SystemClock.uptimeMillis() - lastShotAt);
        if (wait > 0) {
            handler.removeCallbacks(pumpShotsRunnable);
            handler.postDelayed(pumpShotsRunnable, wait);
            return;
        }
        final ArrayList<ShotCallback> batch = new ArrayList<>(shotQueue);
        shotQueue.clear();
        shotInFlight = true;
        lastShotAt = SystemClock.uptimeMillis();
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    shotInFlight = false;
                    shotFailures = 0;
                    Bitmap sw = null;
                    try {
                        HardwareBuffer hb = result.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                        if (hw != null) {
                            sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                        }
                        hb.close();
                    } catch (Throwable t) {
                        Log.w(TAG, "Capture illisible", t);
                        Journal.log("capture illisible", t);
                    }
                    deliver(batch, sw);
                    if (sw != null) sw.recycle();
                    pumpShots();
                }

                @Override
                public void onFailure(int errorCode) {
                    shotInFlight = false;
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        shotQueue.addAll(0, batch);
                        handler.postDelayed(pumpShotsRunnable, 380);
                        return;
                    }
                    shotFailures++;
                    if (shotFailures >= 4) shotsUnavailable = true;
                    Log.w(TAG, "Capture refusée, code " + errorCode);
                    Journal.log("capture refusée, code " + errorCode);
                    deliver(batch, null);
                    pumpShots();
                }
            });
        } catch (Throwable t) {
            shotInFlight = false;
            shotsUnavailable = true;
            Log.w(TAG, "Capture indisponible", t);
            Journal.log("capture indisponible", t);
            deliver(batch, null);
        }
    }

    private static void deliver(List<ShotCallback> batch, Bitmap bmp) {
        for (ShotCallback cb : batch) {
            try {
                cb.onShot(bmp);
            } catch (Throwable t) {
                Log.w(TAG, "Erreur de traitement de capture", t);
            }
        }
    }

    // ------------------------------------------------------------------ outils couleur / image

    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    /**
     * Couleur de fond = la plus fréquente sur le bord du rectangle ;
     * couleur du texte = pixel le plus différent du fond sur la ligne du milieu.
     */
    private static int[] measureColors(Bitmap bmp, Rect r, int inset) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int l = clamp(r.left + inset, w);
        int t = clamp(r.top + inset, h);
        int rr = clamp(r.right - 1 - inset, w);
        int b = clamp(r.bottom - 1 - inset, h);
        int cy = clamp((t + b) / 2, h);
        int cx = clamp((l + rr) / 2, w);

        int[] samples = {
                bmp.getPixel(l, t), bmp.getPixel(rr, t), bmp.getPixel(l, b), bmp.getPixel(rr, b),
                bmp.getPixel(l, cy), bmp.getPixel(rr, cy), bmp.getPixel(cx, t), bmp.getPixel(cx, b)
        };
        int bg = mostCommon(samples);

        int fg = 0;
        int best = -1;
        int step = Math.max(1, (rr - l) / 200);
        int dyStep = Math.max(1, (b - t) / 6);
        for (int x = l; x <= rr; x += step) {
            for (int dy = -1; dy <= 1; dy++) {
                int y = clamp(cy + dy * dyStep, h);
                int c = bmp.getPixel(x, y);
                int d = dist(c, bg);
                if (d > best) {
                    best = d;
                    fg = c;
                }
            }
        }
        if (best < 60) fg = luminance(bg) > 140 ? Color.rgb(20, 20, 20) : Color.WHITE;
        bg = Color.rgb(Color.red(bg), Color.green(bg), Color.blue(bg));
        fg = Color.rgb(Color.red(fg), Color.green(fg), Color.blue(fg));
        return new int[]{bg, fg};
    }

    /** Empreinte grossière de l'écran (grille 64x36 de luminosité). */
    private static int[] signature(Bitmap bmp) {
        int gw = 64, gh = 36;
        int[] s = new int[gw * gh];
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int c = bmp.getPixel(Math.min(w - 1, x * w / gw + w / gw / 2), Math.min(h - 1, y * h / gh + h / gh / 2));
                s[y * gw + x] = luminance(c);
            }
        }
        return s;
    }

    private static boolean differs(int[] a, int[] b) {
        if (a.length != b.length) return true;
        int changed = 0;
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 18) changed++;
        return changed > a.length / 150; // plus de ~0,7 % de la grille a changé
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max - 1, v));
    }

    private static int dist(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b));
    }

    private static int luminance(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
    }

    private static int mostCommon(int[] samples) {
        int best = samples[0];
        int bestCount = 0;
        for (int a : samples) {
            int count = 0;
            for (int b : samples) if (dist(a, b) < 24) count++;
            if (count > bestCount) {
                bestCount = count;
                best = a;
            }
        }
        return best;
    }
}
