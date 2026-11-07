package fr.traducteur.xgimi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Vue transparente plein écran qui dessine les étiquettes françaises
 * exactement par-dessus les textes chinois.
 */
public final class OverlayView extends View {

    /** Une étiquette = un texte traduit à dessiner dans un rectangle de l'écran. */
    public static final class Label {
        final Rect rect;          // position du texte chinois (coordonnées écran)
        final String text;        // traduction
        final int bg;             // couleur de fond
        final int fg;             // couleur du texte
        final float sizePx;       // taille du texte d'origine (0 si inconnue)
        final boolean center;     // centrer (boutons) ou aligner à gauche

        // Calculé au moment de la mise en page
        StaticLayout layout;
        RectF box;

        public Label(Rect rect, String text, int bg, int fg, float sizePx, boolean center) {
            this.rect = rect;
            this.text = text;
            this.bg = bg;
            this.fg = fg;
            this.sizePx = sizePx;
            this.center = center;
        }
    }

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int[] loc = new int[2];
    private List<Label> labels = new ArrayList<>();
    private final float density;

    public OverlayView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        setWillNotDraw(false);
    }

    public void setLabels(List<Label> newLabels) {
        int screenW = getRootView() != null && getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        for (Label l : newLabels) layoutLabel(l, screenW);
        labels = newLabels;
        invalidate();
    }

    public void clear() {
        if (!labels.isEmpty()) {
            labels = new ArrayList<>();
            invalidate();
        }
    }

    public boolean isEmpty() {
        return labels.isEmpty();
    }

    /** Choisit la taille de police pour que la traduction tienne dans le rectangle. */
    private void layoutLabel(Label l, int screenW) {
        Rect r = l.rect;
        float pad = Math.max(2f, 3f * density);
        int availW = Math.max(1, (int) (r.width() - 2 * pad));
        int availH = Math.max(1, r.height());

        float start = l.sizePx > 0 ? l.sizePx : availH * 0.62f;
        start = Math.min(start, availH * 0.9f);
        float min = Math.max(9f * density * 0.75f, start * 0.55f);

        textPaint.setColor(l.fg);
        float size = start;
        StaticLayout lay = null;
        while (true) {
            textPaint.setTextSize(size);
            lay = build(l.text, availW, l.center);
            if (fits(lay, availW, availH) || size <= min) break;
            size = Math.max(min, size * 0.9f);
        }

        RectF box = new RectF(r);
        if (!fits(lay, availW, availH)) {
            // Toujours trop long : on élargit la zone vers la droite (sur une ligne).
            textPaint.setTextSize(min);
            float w = textPaint.measureText(l.text.replace('\n', ' ')) + 2 * pad;
            float maxRight = screenW - pad;
            box.right = Math.min(maxRight, Math.max(box.right, box.left + w));
            int newW = Math.max(1, (int) (box.width() - 2 * pad));
            lay = buildEllipsized(l.text.replace('\n', ' '), newW, l.center);
            if (lay.getHeight() > box.height()) {
                float grow = (lay.getHeight() - box.height()) / 2f;
                box.top -= grow;
                box.bottom += grow;
            }
        }
        l.layout = lay;
        l.box = box;
    }

    private StaticLayout build(String text, int width, boolean center) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), new TextPaint(textPaint), width)
                .setAlignment(center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 0.95f)
                .build();
    }

    private StaticLayout buildEllipsized(String text, int width, boolean center) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), new TextPaint(textPaint), width)
                .setAlignment(center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    private static boolean fits(StaticLayout lay, int w, int h) {
        if (lay.getHeight() > h) return false;
        for (int i = 0; i < lay.getLineCount(); i++) {
            if (lay.getLineWidth(i) > w + 0.5f) return false;
            // Un mot coupé au milieu = ne tient pas vraiment
            int end = lay.getLineEnd(i);
            if (i < lay.getLineCount() - 1 && end > 0 && end < lay.getText().length()) {
                char before = lay.getText().charAt(end - 1);
                char after = lay.getText().charAt(end);
                if (Character.isLetter(before) && Character.isLetter(after)) return false;
            }
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (labels.isEmpty()) return;
        getLocationOnScreen(loc);
        canvas.save();
        canvas.translate(-loc[0], -loc[1]);
        float radius = 3f * density;
        float pad = Math.max(2f, 3f * density);
        for (Label l : labels) {
            if (l.layout == null || l.box == null) continue;
            bgPaint.setColor(l.bg);
            RectF b = l.box;
            canvas.drawRoundRect(b.left - 1, b.top - 1, b.right + 1, b.bottom + 1, radius, radius, bgPaint);
            canvas.save();
            float y = b.top + (b.height() - l.layout.getHeight()) / 2f;
            canvas.translate(b.left + pad, y);
            l.layout.getPaint().setColor(l.fg);
            l.layout.draw(canvas);
            canvas.restore();
        }
        canvas.restore();
    }

    /** Couleurs de secours quand la capture d'écran n'est pas possible. */
    static int fallbackBg(boolean focused) {
        return focused ? Color.argb(245, 236, 236, 236) : Color.argb(240, 30, 30, 34);
    }

    static int fallbackFg(boolean focused) {
        return focused ? Color.rgb(20, 20, 20) : Color.WHITE;
    }
}
