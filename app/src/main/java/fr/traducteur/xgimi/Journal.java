package fr.traducteur.xgimi;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Journal de diagnostic écrit dans les fichiers de l'app (les logs système sont coupés sur ce projecteur).
 *
 * Lecture depuis le PC :
 *   adb shell run-as fr.traducteur.xgimi cat files/journal.txt
 */
final class Journal {
    private static File file;
    private static File captureFile;
    private static final long MAX_SIZE = 300 * 1024;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE);

    static synchronized void init(Context ctx) {
        file = new File(ctx.getFilesDir(), "journal.txt");
        captureFile = new File(ctx.getFilesDir(), "capture.jpg");
        if (file.length() > MAX_SIZE) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    static synchronized void log(String msg) {
        if (file == null) return;
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((FMT.format(new Date()) + "  " + msg + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        if (file.length() > MAX_SIZE * 2) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    static void log(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        log(msg + " : " + (s.length() > 1500 ? s.substring(0, 1500) : s));
    }

    /** Garde une copie de la dernière capture analysée (pour vérifier ce que l'app « voit »). */
    static synchronized void saveCapture(Bitmap bmp) {
        if (captureFile == null || bmp == null) return;
        try (FileOutputStream out = new FileOutputStream(captureFile)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out);
        } catch (Exception e) {
            log("capture non enregistrée", e);
        }
    }
}
