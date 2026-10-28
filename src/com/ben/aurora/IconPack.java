package com.ben.aurora;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the standard {@code appfilter.xml} used by Android icon packs, plus the optional
 * {@code drawable.xml} catalogue, and resolves themed bitmaps for individual app components.
 */
final class IconPack {
    private IconPack() {}

    static final class Pack {
        String pkg;
        String label;
        Bitmap icon;
        Resolver resolver;
    }

    private static final class Parsed {
        final Map<String, String> map = new HashMap<>();
        final Set<String> names = new HashSet<>();
    }

    private static final Object CACHE_LOCK = new Object();
    private static final Map<String, Resolver> RESOLVERS = new HashMap<>();
    private static final Set<String> NON_PACKS = new HashSet<>();
    private static List<Pack> cachedPacks;

    static final class Resolver {
        final String pkg;
        final Context packContext;
        final Resources resources;
        final Map<String, String> map;
        final List<String> names;
        final int px;
        final Map<String, Bitmap> cache = new HashMap<>();

        private Resolver(String pkg, Context packContext, Resources resources,
                         Map<String, String> map, List<String> names, int px) {
            this.pkg = pkg;
            this.packContext = packContext;
            this.resources = resources;
            this.map = map;
            this.names = names;
            this.px = px;
        }

        /** @return a themed bitmap, or null when this pack has no entry for the app. */
        Bitmap iconFor(String appPkg, String activity) {
            if (appPkg == null || appPkg.isEmpty()) return null;
            String key = appPkg + "/" + (activity == null ? "" : activity);
            String name = map.get(key);
            if (name == null) name = map.get(key.toLowerCase(Locale.US));
            if (name == null) name = map.get(appPkg + "/");
            if (name == null) return null;
            return iconByName(name, px);
        }

        /** All drawable names advertised by the pack. */
        List<String> iconNames() {
            return names;
        }

        /** Load one catalogue icon at an arbitrary bitmap size. */
        Bitmap iconByName(String name, int size) {
            if (name == null || name.isEmpty() || size <= 0) return null;
            String cacheKey = name + "@" + size;
            Bitmap cached = cache.get(cacheKey);
            if (cached != null) return cached;
            int id = resources.getIdentifier(name, "drawable", pkg);
            if (id == 0) id = resources.getIdentifier(name, "mipmap", pkg);
            if (id == 0) return null;
            try {
                Drawable d = resources.getDrawable(id, packContext.getTheme());
                Bitmap bmp = Apps.drawableToBitmap(d, size);
                if (bmp != null) cache.put(cacheKey, bmp);
                return bmp;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** Build a resolver for a package, or null when it has no usable icon catalogue. */
    static Resolver resolver(Context context, String packPkg, int px) {
        if (packPkg == null || packPkg.isEmpty()) return null;
        synchronized (CACHE_LOCK) {
            if (NON_PACKS.contains(packPkg)) return null;
            String key = packPkg + "@" + px;
            Resolver cached = RESOLVERS.get(key);
            if (cached != null) return cached;
            try {
                Context base = context.getApplicationContext() != null
                        ? context.getApplicationContext() : context;
                Context pc = base.createPackageContext(packPkg, 0);
                Resources res = pc.getResources();
                Parsed parsed = readMap(pc, res, packPkg);
                List<String> names = new ArrayList<>(parsed.names);
                Collections.sort(names);
                if (parsed.map.isEmpty() && names.isEmpty()) {
                    NON_PACKS.add(packPkg);
                    return null;
                }
                Resolver resolver = new Resolver(packPkg, pc, res, parsed.map, names, px);
                RESOLVERS.put(key, resolver);
                return resolver;
            } catch (Throwable t) {
                NON_PACKS.add(packPkg);
                return null;
            }
        }
    }

    static boolean hasCachedPacks() {
        synchronized (CACHE_LOCK) {
            return cachedPacks != null;
        }
    }

    /** Drop cached pack data after an install/uninstall/update. */
    static void invalidate() {
        synchronized (CACHE_LOCK) {
            cachedPacks = null;
            RESOLVERS.clear();
            NON_PACKS.clear();
        }
    }

    /** All installed applications that expose a non-empty appfilter or drawable catalogue. */
    static List<Pack> findPacks(Context context) {
        synchronized (CACHE_LOCK) {
            if (cachedPacks != null) return new ArrayList<>(cachedPacks);

            List<Pack> out = new ArrayList<>();
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps;
            try {
                apps = pm.getInstalledApplications(0);
            } catch (Throwable t) {
                cachedPacks = out;
                return out;
            }
            int iconPx = Ui.dp(context, 48);
            int resolverPx = Ui.dp(context, 96);
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null) continue;
                if (ai.packageName.equals(context.getPackageName())) continue;
                Resolver resolver = resolver(context, ai.packageName, resolverPx);
                if (resolver == null) continue;
                try {
                    Pack p = new Pack();
                    p.pkg = ai.packageName;
                    p.resolver = resolver;
                    CharSequence label = pm.getApplicationLabel(ai);
                    p.label = label == null ? ai.packageName : label.toString().trim();
                    if (p.label.isEmpty()) p.label = ai.packageName;
                    try {
                        p.icon = Apps.drawableToBitmap(pm.getApplicationIcon(ai), iconPx);
                    } catch (Throwable ignored) {
                    }
                    out.add(p);
                } catch (Throwable ignored) {
                }
            }
            Collections.sort(out, new Comparator<Pack>() {
                @Override
                public int compare(Pack a, Pack b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
            cachedPacks = out;
            return new ArrayList<>(out);
        }
    }

    private static Parsed readMap(Context pc, Resources res, String pkg) {
        Parsed parsed = new Parsed();
        String[] appfilters = {"appfilter.xml", "iconpack/appfilter.xml", "res/xml/appfilter.xml"};
        for (String asset : appfilters) {
            InputStream in = null;
            try {
                in = pc.getAssets().open(asset);
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(in, null);
                parse(parsed, parser);
            } catch (Throwable ignored) {
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        XmlResourceParser parser = null;
        try {
            int id = res.getIdentifier("appfilter", "xml", pkg);
            if (id != 0) {
                parser = res.getXml(id);
                parse(parsed, parser);
            }
        } catch (Throwable ignored) {
        } finally {
            if (parser != null) {
                try {
                    parser.close();
                } catch (Throwable ignored) {
                }
            }
        }

        String[] catalogues = {"drawable.xml", "iconpack/drawable.xml", "res/xml/drawable.xml"};
        for (String asset : catalogues) {
            InputStream in = null;
            try {
                in = pc.getAssets().open(asset);
                XmlPullParser p = Xml.newPullParser();
                p.setInput(in, null);
                parse(parsed, p);
            } catch (Throwable ignored) {
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        XmlResourceParser drawableParser = null;
        try {
            int id = res.getIdentifier("drawable", "xml", pkg);
            if (id != 0) {
                drawableParser = res.getXml(id);
                parse(parsed, drawableParser);
            }
        } catch (Throwable ignored) {
        } finally {
            if (drawableParser != null) {
                try {
                    drawableParser.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return parsed;
    }

    private static void parse(Parsed parsed, XmlPullParser parser) {
        try {
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG
                        && "item".equalsIgnoreCase(parser.getName())) {
                    String component = null;
                    String drawable = null;
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        String name = parser.getAttributeName(i);
                        if ("component".equalsIgnoreCase(name)) {
                            component = parser.getAttributeValue(i);
                        } else if ("drawable".equalsIgnoreCase(name)) {
                            drawable = parser.getAttributeValue(i);
                        }
                    }
                    if (drawable != null && !drawable.trim().isEmpty()) {
                        String value = drawable.trim();
                        if (value.startsWith("@drawable/")) {
                            value = value.substring("@drawable/".length());
                        }
                        parsed.names.add(value);
                        String key = normalizeComponent(component);
                        if (key != null) {
                            parsed.map.put(key, value);
                            parsed.map.put(key.toLowerCase(Locale.US), value);
                        }
                    }
                }
                event = parser.next();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String normalizeComponent(String component) {
        if (component == null) return null;
        String s = component.trim();
        if (s.startsWith("ComponentInfo{")) s = s.substring("ComponentInfo{".length());
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);
        s = s.trim();
        if (s.isEmpty()) return null;
        int slash = s.indexOf('/');
        if (slash > 0 && slash + 1 < s.length() && s.charAt(slash + 1) == '.') {
            s = s.substring(0, slash + 1) + s.substring(0, slash) + s.substring(slash + 1);
        }
        return s;
    }
}
