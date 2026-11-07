package fr.traducteur.xgimi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dictionnaire chinois -> français.
 *
 * Code Java pur (aucune dépendance Android) pour pouvoir le tester sur PC.
 *
 * Trois niveaux de recherche :
 *  1. correspondance exacte (en ignorant espaces et retours à la ligne) ;
 *  2. modèles avec valeurs variables (%s, %1$s, et @ / $ qui sont des icônes de touche) ;
 *  3. découpage : chaque morceau chinois est traduit séparément, les chiffres et
 *     la ponctuation sont conservés (ex. « 亮度 50 » -> « Luminosité 50 »).
 */
public final class Dictionary {

    /** Détecte la présence d'idéogrammes chinois. */
    static final Pattern CJK = Pattern.compile("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]+");
    /** Variables dans les textes : %1$s, %s, %d, et les icônes @ ou $. */
    private static final Pattern TOKEN = Pattern.compile("%(\\d+)\\$[sdf]|%[sdf]|[@$]");
    /** Caractère affiché à la place d'une icône de touche de télécommande. */
    private static final String ICON = "◉";

    private static final class Template {
        final Pattern regex;
        final int[] groupToIndex; // pour chaque groupe capturé : n° de variable, ou -1 pour une icône
        final String french;
        final int literalLength;

        Template(Pattern regex, int[] groupToIndex, String french, int literalLength) {
            this.regex = regex;
            this.groupToIndex = groupToIndex;
            this.french = french;
            this.literalLength = literalLength;
        }
    }

    private final HashMap<String, String> exact = new HashMap<>();
    private final ArrayList<Template> templates = new ArrayList<>();

    private final Map<String, String> cache = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 1500;
        }
    };

    public int size() {
        return exact.size();
    }

    public static Dictionary load(InputStream in) throws IOException {
        Dictionary d = new Dictionary();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            d.add(line.substring(0, tab), line.substring(tab + 1).replace("\\n", "\n").trim());
        }
        // Les modèles les plus précis (plus de texte fixe) sont essayés en premier.
        java.util.Collections.sort(d.templates, (a, b) -> b.literalLength - a.literalLength);
        return d;
    }

    void add(String chinese, String french) {
        if (french.isEmpty()) return;
        String key = norm(chinese);
        if (key.isEmpty()) return;
        if (TOKEN.matcher(key).find()) {
            addTemplate(key, french);
        } else {
            exact.put(key, french);
        }
    }

    private void addTemplate(String key, String french) {
        Matcher m = TOKEN.matcher(key);
        StringBuilder re = new StringBuilder("\\s*");
        ArrayList<Integer> order = new ArrayList<>();
        int last = 0;
        int seq = 0;
        int literal = 0;
        boolean hasCjkLiteral = false;
        while (m.find()) {
            String lit = key.substring(last, m.start());
            appendLiteral(re, lit);
            literal += lit.length();
            if (CJK.matcher(lit).find()) hasCjkLiteral = true;
            String t = m.group();
            if (t.equals("@") || t.equals("$")) {
                re.append("(.{0,3}?)\\s*");
                order.add(-1);
            } else {
                int idx = m.group(1) != null ? Integer.parseInt(m.group(1)) : ++seq;
                re.append("(.+?)\\s*");
                order.add(idx);
            }
            last = m.end();
        }
        String tail = key.substring(last);
        appendLiteral(re, tail);
        literal += tail.length();
        if (CJK.matcher(tail).find()) hasCjkLiteral = true;
        if (!hasCjkLiteral) return; // un modèle sans chinois fixe correspondrait à n'importe quoi
        int[] g = new int[order.size()];
        for (int i = 0; i < g.length; i++) g[i] = order.get(i);
        templates.add(new Template(Pattern.compile(re.toString(), Pattern.DOTALL), g, french, literal));
    }

    /** Ajoute un texte fixe en tolérant des espaces / retours à la ligne entre chaque caractère. */
    private static void appendLiteral(StringBuilder re, String lit) {
        for (int i = 0; i < lit.length(); i++) {
            re.append(Pattern.quote(String.valueOf(lit.charAt(i)))).append("\\s*");
        }
    }

    /** Supprime espaces, retours à la ligne (réels ou écrits « \n ») et caractères invisibles. */
    static String norm(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == 'n') {
                i++;
                continue;
            }
            if (Character.isWhitespace(c) || c == ' ' || c == '　' || c == '​'
                    || c == '‎' || c == '‏' || c == '﻿' || c == '￼') {
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }

    public static boolean hasChinese(CharSequence s) {
        return s != null && CJK.matcher(s).find();
    }

    /** Renvoie la traduction, ou null si le texte n'a pas de chinois ou est inconnu. */
    public synchronized String translate(CharSequence cs) {
        if (cs == null) return null;
        String text = cs.toString();
        if (!hasChinese(text)) return null;
        String cached = cache.get(text);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String r = translateFull(text);
        cache.put(text, r == null ? "" : r);
        return r;
    }

    private String translateFull(String text) {
        String r = translateLine(text);
        if (r != null) return r;
        // Plusieurs lignes : chaque ligne séparément.
        String[] lines = text.split("\n");
        if (lines.length > 1) {
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                String t = hasChinese(l) ? translateLine(l) : null;
                if (t == null && hasChinese(l)) t = translatePieces(l);
                if (t != null) any = true;
                if (i > 0) sb.append('\n');
                sb.append(t != null ? t : l.trim());
            }
            if (any) return sb.toString().trim();
        }
        return translatePieces(text);
    }

    private String translateLine(String text) {
        String r = exact.get(norm(text));
        if (r != null) return r;
        for (Template t : templates) {
            Matcher m = t.regex.matcher(text);
            if (m.matches()) return fill(t, m);
        }
        return null;
    }

    /** Traduit chaque bloc chinois séparément ; échoue si un seul bloc est inconnu. */
    private String translatePieces(String text) {
        Matcher m = CJK_RUN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        int found = 0;
        while (m.find()) {
            String fr = exact.get(m.group());
            if (fr == null) return null;
            String before = text.substring(last, m.start());
            sb.append(before);
            if (sb.length() > 0 && Character.isLetterOrDigit(sb.charAt(sb.length() - 1))) sb.append(' ');
            sb.append(fr);
            last = m.end();
            found++;
            if (last < text.length() && Character.isLetterOrDigit(text.charAt(last))) sb.append(' ');
        }
        if (found == 0) return null;
        sb.append(text.substring(last));
        return sb.toString().trim();
    }

    private String fill(Template t, Matcher m) {
        String[] values = new String[10];
        for (int g = 0; g < t.groupToIndex.length; g++) {
            int idx = t.groupToIndex[g];
            if (idx >= 0 && idx < values.length) values[idx] = translateValue(m.group(g + 1));
        }
        Matcher tok = TOKEN.matcher(t.french);
        StringBuffer sb = new StringBuffer();
        int seq = 0;
        while (tok.find()) {
            String token = tok.group();
            String rep;
            if (token.equals("@") || token.equals("$")) {
                rep = ICON;
            } else {
                int idx = tok.group(1) != null ? Integer.parseInt(tok.group(1)) : ++seq;
                rep = idx < values.length && values[idx] != null ? values[idx] : "";
            }
            tok.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        tok.appendTail(sb);
        return sb.toString().trim();
    }

    /** Une valeur insérée dans un modèle (ex. « 亮度 ») est traduite si possible. */
    private String translateValue(String v) {
        if (v == null) return "";
        v = v.trim();
        if (!hasChinese(v)) return v;
        String r = exact.get(norm(v));
        if (r != null) return r;
        r = translatePieces(v);
        return r != null ? r : v;
    }

    // ------------------------------------------------------------------ texte issu de l'OCR

    private ArrayList<String> fuzzyKeys;
    private ArrayList<String> fuzzyCores;

    /** Garde seulement chinois, lettres et chiffres (l'OCR ajoute souvent des symboles parasites). */
    static String core(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) b.append(c);
        }
        return b.toString();
    }

    /**
     * Comme translate(), mais tolère les petites erreurs de lecture de l'OCR
     * (un caractère mal reconnu, une ponctuation manquante…).
     */
    public synchronized String translateFuzzy(CharSequence cs) {
        if (cs == null) return null;
        String text = cs.toString();
        if (!hasChinese(text)) return null;
        String r = translate(text);
        if (r != null) return r;
        String ck = "~" + text;
        String cached = cache.get(ck);
        if (cached != null) return cached.isEmpty() ? null : cached;

        if (fuzzyKeys == null) {
            fuzzyKeys = new ArrayList<>(exact.keySet());
            fuzzyCores = new ArrayList<>(fuzzyKeys.size());
            for (String k : fuzzyKeys) fuzzyCores.add(core(k));
        }
        String c = core(norm(text));
        int len = c.length();
        String best = null;
        if (len >= 2) {
            int maxDist = len <= 2 ? 0 : len <= 5 ? 1 : Math.max(1, len / 5);
            int bestDist = maxDist + 1;
            for (int i = 0; i < fuzzyCores.size(); i++) {
                String k = fuzzyCores.get(i);
                if (Math.abs(k.length() - len) > maxDist) continue;
                int d = levenshtein(c, k, bestDist);
                if (d < bestDist) {
                    bestDist = d;
                    best = exact.get(fuzzyKeys.get(i));
                    if (d == 0) break;
                }
            }
        }
        cache.put(ck, best == null ? "" : best);
        return best;
    }

    /** Distance d'édition, arrêtée dès qu'elle dépasse 'limit'. */
    static int levenshtein(String a, String b, int limit) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (cur[j] < rowMin) rowMin = cur[j];
            }
            if (rowMin >= limit) return limit;
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    /** Pour les tests. */
    List<String> templateCount() {
        ArrayList<String> l = new ArrayList<>();
        for (Template t : templates) l.add(t.regex.pattern());
        return l;
    }
}
