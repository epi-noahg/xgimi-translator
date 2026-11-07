package fr.traducteur.xgimi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Réveillé au démarrage du projecteur, après une mise à jour de l'app,
 * et par les vérifications programmées : relance le traducteur s'il a été coupé.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Journal.init(context);
        String action = intent != null && intent.getAction() != null ? intent.getAction() : "?";
        String why;
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.LOCKED_BOOT_COMPLETED":
                why = "démarrage";
                break;
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                why = "mise à jour";
                break;
            default:
                why = "vérification";
        }
        if (!why.equals("vérification")) Watchdog.scheduleChecks(context);
        final PendingResult result = goAsync();
        Watchdog.ensure(context, why, result::finish);
    }
}
