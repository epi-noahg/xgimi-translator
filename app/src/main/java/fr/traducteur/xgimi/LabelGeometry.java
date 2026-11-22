package fr.traducteur.xgimi;

import android.graphics.Bitmap;
import android.graphics.Rect;

/** Mesures géométriques bon marché sur la capture d'écran, pour placer les étiquettes. */
final class LabelGeometry {
    private LabelGeometry() {}

    /**
     * Espace libre de même couleur de fond autour d'un texte, en coordonnées bitmap.
     * Scanne 3 lignes (haut, milieu, bas de la boîte) vers la gauche et la droite
     * et s'arrête au premier pixel qui s'écarte de {@code bg} de plus de {@code tol}
     * (par canal) ou au bord / à {@code maxReach} pixels.
     * Le scan commence après un petit retrait (pour ne pas lire les glyphes eux-mêmes).
     *
     * @return {left, right} : bornes x extrêmes atteignables (left ≤ box.left, right ≥ box.right)
     */
    static int[] freeSpan(Bitmap bmp, Rect box, int bg, int tol, int maxReach) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int top = clamp(box.top, 0, h - 1), bottom = clamp(box.bottom - 1, 0, h - 1);
        int[] ys = {top + (bottom - top) / 6, (top + bottom) / 2, bottom - (bottom - top) / 6};
        int x0 = clamp(box.left, 0, w), x1 = clamp(box.right, 0, w);
        int lo = Math.max(0, x0 - maxReach), hi = Math.min(w, x1 + maxReach);
        int[] row = new int[Math.max(1, hi - lo)];
        int left = lo, right = hi;
        int br = (bg >> 16) & 0xff, bgG = (bg >> 8) & 0xff, bb = bg & 0xff;
        for (int y : ys) {
            bmp.getPixels(row, 0, row.length, lo, y, hi - lo, 1);
            int x = x1;
            while (x < right && same(row[x - lo], br, bgG, bb, tol)) x++;
            right = Math.min(right, x);
            x = x0 - 1;
            while (x >= left && same(row[x - lo], br, bgG, bb, tol)) x--;
            left = Math.max(left, x + 1);
        }
        // Marge de sécurité : ne pas coller au contenu voisin.
        int margin = Math.max(2, box.height() / 4);
        if (right < hi) right = Math.max(x1, right - margin);
        if (left > lo) left = Math.min(x0, left + margin);
        return new int[]{Math.min(left, x0), Math.max(right, x1)};
    }

    private static boolean same(int c, int r, int g, int b, int tol) {
        return Math.abs(((c >> 16) & 0xff) - r) <= tol
                && Math.abs(((c >> 8) & 0xff) - g) <= tol
                && Math.abs((c & 0xff) - b) <= tol;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
