package fr.traducteur.xgimi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;

/**
 * Gardien : vérifie que le service de traduction tourne, et le relance sinon.
 *
 * Le firmware XGIMI coupe parfois le service (au démarrage du projecteur, ou lors d'une mise à jour
 * de l'app) sans qu'Android le relance. Pour forcer Android à le reconnecter, on le retire puis on
 * le remet dans la liste des services d'accessibilité, ce qui demande la permission
 * WRITE_SECURE_SETTINGS (accordée une seule fois par ADB).
 */
final class Watchdog {

    static final String ACTION_CHECK = "fr.traducteur.xgimi.CHECK";
    private static final long MINUTE = 60_000L;

    private Watchdog() {
    }

    static boolean canWriteSettings(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private static ComponentName me(Context c) {
        return new ComponentName(c, TranslateService.class);
    }

    /** Le service est-il dans la liste des services activés ? */
    static boolean isEnabledInSettings(Context c) {
        return findIndex(splitServices(c), me(c)) >= 0;
    }

    private static ArrayList<String> splitServices(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ArrayList<String> out = new ArrayList<>();
        if (!TextUtils.isEmpty(list) && !"null".equals(list)) {
            for (String s : list.split(":")) if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    private static int findIndex(ArrayList<String> services, ComponentName target) {
        for (int i = 0; i < services.size(); i++) {
            if (target.equals(ComponentName.unflattenFromString(services.get(i)))) return i;
        }
        return -1;
    }

    /**
     * Relance le service s'il ne tourne pas.
     *
     * @param done appelé une fois le travail terminé (peut être null)
     */
    static void ensure(final Context ctx, final String why, final Runnable done) {
        final Context c = ctx.getApplicationContext();
        boolean wanted = c.getSharedPreferences(TranslateService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(TranslateService.KEY_WANTED, false);
        if (TranslateService.isAlive()) {
            if (done != null) done.run();
            return;
        }
        if (!wanted && !isEnabledInSettings(c)) {
            // Jamais activé par l'utilisateur : on ne force rien.
            if (done != null) done.run();
            return;
        }
        if (!canWriteSettings(c)) {
            Journal.log("gardien (" + why + ") : service arrêté, mais permission WRITE_SECURE_SETTINGS manquante");
            if (done != null) done.run();
            return;
        }
        final ContentResolver cr = c.getContentResolver();
        final ComponentName target = me(c);
        final ArrayList<String> services = splitServices(c);
        int idx = findIndex(services, target);
        if (idx >= 0) services.remove(idx);
        final String without = TextUtils.join(":", services);
        try {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without);
        } catch (Exception e) {
            Journal.log("gardien : écriture impossible", e);
            if (done != null) done.run();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                String with = without.isEmpty() ? target.flattenToShortString()
                        : without + ":" + target.flattenToShortString();
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, with);
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Journal.log("gardien (" + why + ") : service relancé");
            } catch (Exception e) {
                Journal.log("gardien : réactivation impossible", e);
            }
            if (done != null) done.run();
        }, 800);
    }

    /** Programme des vérifications : peu après le démarrage, puis toutes les 15 minutes. */
    static void scheduleChecks(Context ctx) {
        Context c = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = SystemClock.elapsedRealtime();
        long[] delays = {MINUTE / 2, 2 * MINUTE, 5 * MINUTE};
        for (int i = 0; i < delays.length; i++) {
            am.set(AlarmManager.ELAPSED_REALTIME, now + delays[i], pending(c, i));
        }
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, now + 15 * MINUTE,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, pending(c, 99));
    }

    private static PendingIntent pending(Context c, int code) {
        Intent i = new Intent(c, BootReceiver.class).setAction(ACTION_CHECK);
        return PendingIntent.getBroadcast(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
