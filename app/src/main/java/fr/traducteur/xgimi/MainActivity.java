package fr.traducteur.xgimi;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Écran de contrôle : état du service, pause / reprise, aide à l'activation. */
public class MainActivity extends Activity {

    private TextView status;
    private TextView sub;
    private TextView help;
    private Button toggle;
    private Button colors;
    private Button lang;
    private Button a11y;
    private SharedPreferences prefs;

    /** Texte dans la langue choisie. */
    private String t(String fr, String en) {
        return "en".equals(TranslateService.language(this)) ? en : fr;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(TranslateService.PREFS, MODE_PRIVATE);

        int pad = dp(32);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        col.setBackgroundColor(Color.rgb(18, 20, 28));

        col.addView(text("Traducteur XGIMI / XGIMI Translator", 30, Color.WHITE));

        sub = text("", 16, Color.rgb(170, 176, 190));
        sub.setPadding(0, dp(4), 0, dp(24));
        col.addView(sub);

        status = text("", 18, Color.WHITE);
        status.setPadding(0, 0, 0, dp(20));
        col.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(16);

        lang = button();
        lang.setOnClickListener(v -> {
            String next = "en".equals(TranslateService.language(this)) ? "fr" : "en";
            prefs.edit().putString(TranslateService.KEY_LANG, next).apply();
            refresh();
        });
        row.addView(lang, lp);

        toggle = button();
        toggle.setOnClickListener(v -> {
            prefs.edit().putBoolean(TranslateService.KEY_ON, !prefs.getBoolean(TranslateService.KEY_ON, true)).apply();
            refresh();
        });
        row.addView(toggle, lp);

        colors = button();
        colors.setOnClickListener(v -> {
            prefs.edit().putBoolean(TranslateService.KEY_COLORS, !prefs.getBoolean(TranslateService.KEY_COLORS, true)).apply();
            refresh();
        });
        row.addView(colors, lp);

        a11y = button();
        a11y.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                status.setText(t("Ce projecteur n'a pas d'écran d'accessibilité : utilisez les commandes ADB ci-dessous.",
                        "This projector has no accessibility screen: use the ADB commands below."));
            }
        });
        row.addView(a11y, lp);
        col.addView(row);

        help = text("", 15, Color.rgb(170, 176, 190));
        help.setPadding(0, dp(28), 0, 0);
        col.addView(help);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        lang.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // Ouvrir l'app relance aussi le traducteur s'il a été coupé.
        Watchdog.scheduleChecks(this);
        Watchdog.ensure(this, "appli ouverte", () -> status.postDelayed(this::refresh, 1500));
    }

    private void refresh() {
        boolean active = TranslateService.isAlive() || isServiceEnabled();
        boolean running = TranslateService.isAlive();
        boolean canRestart = Watchdog.canWriteSettings(this);
        boolean on = prefs.getBoolean(TranslateService.KEY_ON, true);
        boolean useColors = prefs.getBoolean(TranslateService.KEY_COLORS, true);
        String pkg = getPackageName();

        sub.setText(t("Affiche le français par-dessus les menus chinois du projecteur.",
                "Shows English on top of the projector's Chinese menus."));
        if (!active) {
            status.setText(t("⚠ Service non activé. Lancez les commandes ADB ci-dessous.",
                    "⚠ Service not enabled. Run the ADB commands below."));
            status.setTextColor(Color.rgb(251, 191, 36));
        } else if (!running) {
            status.setText(canRestart
                    ? t("⟳ Relance du traducteur…", "⟳ Restarting the translator…")
                    : t("⚠ Traducteur arrêté. Accordez la relance automatique (commande ADB ci-dessous).",
                    "⚠ Translator stopped. Grant automatic restart (ADB command below)."));
            status.setTextColor(Color.rgb(251, 191, 36));
        } else if (on) {
            status.setText(canRestart
                    ? t("✔ Traduction active (relance automatique activée)", "✔ Translation on (auto restart enabled)")
                    : t("✔ Traduction active (relance automatique NON accordée)", "✔ Translation on (auto restart NOT granted)"));
            status.setTextColor(Color.rgb(74, 222, 128));
        } else {
            status.setText(t("⏸ Traduction en pause", "⏸ Translation paused"));
            status.setTextColor(Color.rgb(203, 213, 225));
        }
        lang.setText(t("Langue : Français", "Language: English"));
        toggle.setText(on ? t("Mettre en pause", "Pause") : t("Reprendre", "Resume"));
        colors.setText(useColors ? t("Couleurs : auto", "Colors: auto") : t("Couleurs : simples", "Colors: plain"));
        a11y.setText(t("Accessibilité", "Accessibility"));
        help.setText(t("Activation par ADB (depuis le PC), en gardant les services déjà actifs :\n",
                "Enable via ADB (from a PC), keeping any services already enabled:\n")
                + "adb shell settings put secure enabled_accessibility_services <existing>:" + pkg + "/.TranslateService\n"
                + "adb shell settings put secure accessibility_enabled 1\n\n"
                + t("Relance automatique (une seule fois) :\n", "Automatic restart (once):\n")
                + "adb shell pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS\n\n"
                + t("Les traductions apparaissent une fraction de seconde après chaque changement d'écran. "
                        + "Seuls les écrans XGIMI sont traduits ; vos autres applis ne sont pas touchées.",
                "Translations appear a fraction of a second after each screen change. "
                        + "Only XGIMI screens are translated; your other apps are left alone."));
    }

    private boolean isServiceEnabled() {
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(list)) return false;
        ComponentName me = new ComponentName(this, TranslateService.class);
        for (String s : list.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return false;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button button() {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(Color.WHITE);
        b.setFocusable(true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.rgb(40, 46, 66));
        b.setBackground(bg);
        b.setOnFocusChangeListener((View v, boolean f) -> {
            bg.setColor(f ? Color.rgb(59, 130, 246) : Color.rgb(40, 46, 66));
            v.setScaleX(f ? 1.05f : 1f);
            v.setScaleY(f ? 1.05f : 1f);
        });
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
