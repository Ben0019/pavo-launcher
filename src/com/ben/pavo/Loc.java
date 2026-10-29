package com.ben.pavo;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.text.Spanned;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small runtime localisation layer for Pavo's hand-built UI.  The launcher is
 * not resource based, so translated strings are loaded from a compact JSON
 * catalogue in assets and applied by LTextView / LToast as text is displayed.
 */
final class Loc {
    static final String AUTO = "auto";
    static final String EN_US = "en-US";
    static final String EN_GB = "en-GB";

    private static final String[] SUPPORTED = {
            "it", "es", "fr", "de", "pt", "ru", "ja", "ko", "zh-CN", "nl",
            "pl", "tr", "vi", "id", "sv", "da", "nb", "fi", "cs", "el",
            "he", "th", "uk", "ro", "hu"
    };

    /** Display order in the language picker. */
    static final String[][] LANGUAGES = {
            {AUTO, "Automatic (device)"},
            {EN_US, "English (US)"},
            {EN_GB, "English (UK)"},
            {"it", "Italiano"},
            {"es", "Español"},
            {"fr", "Français"},
            {"de", "Deutsch"},
            {"pt", "Português"},
            {"ru", "Русский"},
            {"ja", "日本語"},
            {"ko", "한국어"},
            {"zh-CN", "中文（简体）"},
            {"nl", "Nederlands"},
            {"pl", "Polski"},
            {"tr", "Türkçe"},
            {"vi", "Tiếng Việt"},
            {"id", "Bahasa Indonesia"},
            {"sv", "Svenska"},
            {"da", "Dansk"},
            {"nb", "Norsk bokmål"},
            {"fi", "Suomi"},
            {"cs", "Čeština"},
            {"el", "Ελληνικά"},
            {"he", "עברית"},
            {"th", "ไทย"},
            {"uk", "Українська"},
            {"ro", "Română"},
            {"hu", "Magyar"}
    };

    private static final Map<String, Map<String, String>> CACHE = new HashMap<>();
    private static AssetManager assets;
    private static String appliedCode;

    private Loc() {}

    static String configured(Context c) {
        String v = c.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
                .getString("language", AUTO);
        return v == null || v.trim().isEmpty() ? AUTO : v.trim();
    }

    static String deviceCode() {
        Locale l;
        if (Build.VERSION.SDK_INT >= 24) {
            l = Resources.getSystem().getConfiguration().getLocales().get(0);
        } else {
            l = Resources.getSystem().getConfiguration().locale;
        }
        if (l == null) return EN_US;
        String lang = l.getLanguage();
        String country = l.getCountry();
        if ("en".equals(lang)) {
            return "GB".equalsIgnoreCase(country) ? EN_GB : EN_US;
        }
        if ("zh".equals(lang)) return "zh-CN";
        if ("no".equals(lang) || "nb".equals(lang)) return "nb";
        if ("iw".equals(lang)) return "he";
        if ("in".equals(lang)) return "id";
        for (String code : SUPPORTED) {
            if (code.equals(lang)) return code;
        }
        return EN_US;
    }

    static String languageCode(Context c) {
        String configured = configured(c);
        if (AUTO.equals(configured)) return deviceCode();
        for (String code : SUPPORTED) {
            if (code.equals(configured)) return code;
        }
        if (EN_GB.equals(configured) || EN_US.equals(configured)) return configured;
        return EN_US;
    }

    static String displayName(String code) {
        if (code == null) return "English (US)";
        for (String[] row : LANGUAGES) {
            if (row[0].equals(code)) return row[1];
        }
        return code;
    }

    static String configuredLabel(Context c) {
        String configured = configured(c);
        if (AUTO.equals(configured)) {
            return s(c, "Automatic") + " (" + displayName(deviceCode()) + ")";
        }
        return displayName(languageCode(c));
    }

    /**
     * Applies the selected language to the process locale and reports whether
     * the code changed.  This keeps date/time/number formatting in sync.
     */
    static boolean applyLocale(Context c) {
        String code = languageCode(c);
        if (code.equals(appliedCode)) return false;
        appliedCode = code;
        Locale.setDefault(localeFor(code));
        return true;
    }

    static void invalidate() {
        appliedCode = null;
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    static CharSequence t(Context c, CharSequence input) {
        if (input == null) return null;
        if (input instanceof Spanned) return input;
        String s = input.toString();
        if (s.isEmpty() || s.length() > 400) return input;
        String code = languageCode(c);
        if (EN_US.equals(code)) return input;
        if (EN_GB.equals(code)) {
            String uk = british(s);
            return uk.equals(s) ? input : uk;
        }
        Map<String, String> map = mapFor(c, code);
        if (map.isEmpty()) return input;
        String out = map.get(s);
        if (out == null) {
            String trim = s.trim();
            if (!trim.equals(s)) out = map.get(trim);
        }
        return out == null || out.isEmpty() ? input : out;
    }

    static String s(Context c, String input) {
        return input == null ? null : t(c, input).toString();
    }

    private static Locale localeFor(String code) {
        if (EN_US.equals(code)) return Locale.US;
        if (EN_GB.equals(code)) return Locale.UK;
        if ("zh-CN".equals(code)) return Locale.SIMPLIFIED_CHINESE;
        if ("nb".equals(code)) return new Locale("nb", "NO");
        return Locale.forLanguageTag(code);
    }

    private static Map<String, String> mapFor(Context c, String code) {
        synchronized (CACHE) {
            Map<String, String> cached = CACHE.get(code);
            if (cached != null) return cached;
            Map<String, String> map = new HashMap<>();
            try {
                if (assets == null) {
                    assets = c.getApplicationContext().getAssets();
                }
                String json = readAsset("i18n.json");
                if (json != null && json.length() > 0) {
                    JSONObject root = new JSONObject(json);
                    JSONObject one = root.optJSONObject(code);
                    if (one != null) {
                        java.util.Iterator<String> it = one.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            map.put(k, one.optString(k, ""));
                        }
                    }
                }
            } catch (Throwable ignored) {
                map.clear();
            }
            CACHE.put(code, map);
            return map;
        }
    }

    private static String readAsset(String name) {
        if (assets == null) return null;
        InputStream in = null;
        ByteArrayOutputStream out = null;
        try {
            in = assets.open(name);
            out = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------
    // English (UK) spelling
    // ------------------------------------------------------------------
    private static final Object[][] UK_WORDS = {
            {"color", "colour"}, {"favorite", "favourite"}, {"center", "centre"},
            {"fiber", "fibre"}, {"liter", "litre"}, {"meter", "metre"},
            {"theater", "theatre"}, {"behavior", "behaviour"},
            {"neighbor", "neighbour"}, {"honor", "honour"}, {"labor", "labour"},
            {"favor", "favour"}, {"humor", "humour"}, {"rumor", "rumour"},
            {"savior", "saviour"}, {"defense", "defence"},
            {"offense", "offence"}, {"pretense", "pretence"},
            {"gray", "grey"},
            {"canceled", "cancelled"}, {"canceling", "cancelling"},
            {"centered", "centred"}, {"centering", "centring"},
            {"traveled", "travelled"}, {"traveling", "travelling"},
            {"traveler", "traveller"}, {"travelers", "travellers"},
            {"modeled", "modelled"}, {"modeling", "modelling"},
            {"fulfill", "fulfil"}, {"fulfillment", "fulfilment"},
            {"enrollment", "enrolment"}, {"skillful", "skilful"},
            {"skeptic", "sceptic"}, {"skeptical", "sceptical"},
            {"maneuver", "manoeuvre"}, {"aluminum", "aluminium"},
            {"catalog", "catalogue"}, {"analog", "analogue"},
            {"jewelry", "jewellery"}, {"aging", "ageing"},
            {"acknowledgment", "acknowledgement"}, {"judgment", "judgement"},
            {"customize", "customise"}, {"customized", "customised"},
            {"customizing", "customising"}, {"customizable", "customisable"},
            {"optimize", "optimise"}, {"optimized", "optimised"},
            {"optimizing", "optimising"}, {"optimization", "optimisation"},
            {"organize", "organise"}, {"organized", "organised"},
            {"organizing", "organising"}, {"organization", "organisation"},
            {"recognize", "recognise"}, {"recognized", "recognised"},
            {"recognizing", "recognising"}, {"analyze", "analyse"},
            {"analyzed", "analysed"}, {"analyzing", "analysing"},
            {"license", "licence"}
    };

    private static String british(String s) {
        String out = s;
        for (Object[] row : UK_WORDS) {
            out = replaceWord(out, (String) row[0], (String) row[1]);
        }
        return out;
    }

    private static String replaceWord(String text, String us, String uk) {
        try {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(us) + "(s|ed|ing|ful|less|able)?\\b",
                    Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(text);
            StringBuffer sb = new StringBuffer();
            boolean changed = false;
            while (m.find()) {
                String found = m.group(0);
                String suffix = m.group(1);
                String base = found.substring(0, found.length() - (suffix == null ? 0 : suffix.length()));
                String rep = matchCase(uk, base) + (suffix == null ? "" : suffix);
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
                changed = true;
            }
            if (!changed) return text;
            m.appendTail(sb);
            return sb.toString();
        } catch (Throwable t) {
            return text;
        }
    }

    private static String matchCase(String base, String model) {
        if (model == null || model.isEmpty() || base.isEmpty()) return base;
        if (model.length() > 1 && model.equals(model.toUpperCase(Locale.ROOT))) {
            return base.toUpperCase(Locale.ROOT);
        }
        if (Character.isUpperCase(model.charAt(0))) {
            return Character.toUpperCase(base.charAt(0)) + base.substring(1);
        }
        return base;
    }
}
