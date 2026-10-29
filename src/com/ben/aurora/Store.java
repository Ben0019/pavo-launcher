package com.ben.aurora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Persisted desktop layout: pages, dock and per-item customisation (rename / icon). */
final class Store {

    static final class Custom {
        String label;
        String iconFile;
        Bitmap icon;
    }

    /** An AppWidget placed on the home grid. The clock is built in and not stored here. */
    static final class PlacedWidget {
        int appWidgetId;
        String kind;
        String providerPkg;
        String providerCls;
        int page;
        int cell;
        int spanCols = 1;
        int spanRows = 1;

        JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("id", appWidgetId);
            if (kind != null) o.put("k", kind);
            if (providerPkg != null) o.put("p", providerPkg);
            if (providerCls != null) o.put("c", providerCls);
            o.put("pg", page);
            o.put("cell", cell);
            o.put("cs", spanCols);
            o.put("rs", spanRows);
            return o;
        }

        static PlacedWidget fromJson(JSONObject o) {
            PlacedWidget w = new PlacedWidget();
            w.appWidgetId = o.optInt("id", -1);
            w.kind = o.isNull("k") ? null : o.optString("k", null);
            w.providerPkg = o.isNull("p") ? null : o.optString("p", null);
            w.providerCls = o.isNull("c") ? null : o.optString("c", null);
            w.page = Math.max(0, o.optInt("pg", 0));
            w.cell = Math.max(0, o.optInt("cell", 0));
            w.spanCols = Math.max(1, o.optInt("cs", 1));
            w.spanRows = Math.max(1, o.optInt("rs", 1));
            return w;
        }
    }

    final List<List<Item>> pages = new ArrayList<>();
    final List<Item> dock = new ArrayList<>();
    final List<PlacedWidget> widgets = new ArrayList<>();
    final Map<String, Custom> customs = new HashMap<>();

    private final Prefs prefs;
    private final Context context;

    Store(Context c, Prefs prefs) {
        this.context = c.getApplicationContext();
        this.prefs = prefs;
    }

    void load() {
        pages.clear();
        dock.clear();
        widgets.clear();
        customs.clear();

        try {
            String raw = prefs.layoutJson();
            if (raw != null && !raw.isEmpty()) {
                JSONObject root = new JSONObject(raw);
                JSONArray ps = root.optJSONArray("pages");
                if (ps != null) {
                    for (int i = 0; i < ps.length(); i++) {
                        JSONArray arr = ps.getJSONArray(i);
                        List<Item> page = new ArrayList<>();
                        for (int j = 0; j < arr.length(); j++) {
                            Object v = arr.get(j);
                            page.add(v == JSONObject.NULL ? null : Item.fromJson((JSONObject) v));
                        }
                        pages.add(page);
                    }
                }
                JSONArray dk = root.optJSONArray("dock");
                if (dk != null) {
                    for (int i = 0; i < dk.length(); i++) {
                        Object v = dk.get(i);
                        if (v != JSONObject.NULL) dock.add(Item.fromJson((JSONObject) v));
                    }
                }
                JSONArray ws = root.optJSONArray("widgets");
                if (ws != null) {
                    for (int i = 0; i < ws.length(); i++) {
                        JSONObject o = ws.optJSONObject(i);
                        if (o != null) {
                            PlacedWidget w = PlacedWidget.fromJson(o);
                            if (w.appWidgetId >= 0 || w.kind != null) widgets.add(w);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (pages.isEmpty()) pages.add(new ArrayList<>());

        try {
            String raw = prefs.customJson();
            if (raw != null && !raw.isEmpty()) {
                JSONObject root = new JSONObject(raw);
                for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject o = root.optJSONObject(k);
                    if (o == null) continue;
                    Custom c = new Custom();
                    c.label = o.isNull("l") ? null : o.optString("l", null);
                    c.iconFile = o.isNull("i") ? null : o.optString("i", null);
                    customs.put(k, c);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray ps = new JSONArray();
            for (List<Item> page : pages) {
                JSONArray arr = new JSONArray();
                for (Item it : page) arr.put(it == null ? JSONObject.NULL : it.toJson());
                ps.put(arr);
            }
            root.put("pages", ps);
            JSONArray dk = new JSONArray();
            for (Item it : dock) dk.put(it == null ? JSONObject.NULL : it.toJson());
            root.put("dock", dk);
            JSONArray ws = new JSONArray();
            for (PlacedWidget w : widgets) {
                if (w.appWidgetId >= 0 || w.kind != null) ws.put(w.toJson());
            }
            root.put("widgets", ws);
            prefs.setLayoutJson(root.toString());

            JSONObject cs = new JSONObject();
            for (Map.Entry<String, Custom> e : customs.entrySet()) {
                Custom c = e.getValue();
                if ((c.label == null || c.label.isEmpty()) && c.iconFile == null) continue;
                JSONObject o = new JSONObject();
                if (c.label != null) o.put("l", c.label);
                if (c.iconFile != null) o.put("i", c.iconFile);
                cs.put(e.getKey(), o);
            }
            prefs.setCustomJson(cs.toString());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    Custom custom(Item item) {
        return customs.get(item.key());
    }

    String labelFor(Item item, String fallback) {
        Custom c = custom(item);
        if (c != null && c.label != null && !c.label.isEmpty()) return c.label;
        return fallback;
    }

    Bitmap iconFor(Item item) {
        Custom c = custom(item);
        if (c == null || c.iconFile == null) return null;
        if (c.icon != null) return c.icon;
        try {
            File f = new File(context.getFilesDir(), c.iconFile);
            if (!f.exists()) return null;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            c.icon = b;
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    void setLabel(Item item, String label) {
        Custom c = customs.get(item.key());
        if (c == null) {
            c = new Custom();
            customs.put(item.key(), c);
        }
        c.label = (label == null || label.trim().isEmpty()) ? null : label.trim();
        save();
    }

    void setIcon(Item item, Bitmap bitmap) {
        Custom c = customs.get(item.key());
        if (c == null) {
            c = new Custom();
            customs.put(item.key(), c);
        }
        try {
            String name = "icon_" + Integer.toHexString(item.key().hashCode()) + ".png";
            FileOutputStream fos = new FileOutputStream(new File(context.getFilesDir(), name));
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            c.iconFile = name;
            c.icon = bitmap;
            save();
        } catch (Throwable ignored) {
        }
    }

    /** Remove a per-app custom icon, keeping any custom label. */
    void clearIcon(Item item) {
        Custom c = customs.get(item.key());
        if (c == null) return;
        if (c.iconFile != null) {
            File f = new File(context.getFilesDir(), c.iconFile);
            if (f.exists()) f.delete();
        }
        c.iconFile = null;
        c.icon = null;
        if (c.label == null || c.label.isEmpty()) customs.remove(item.key());
        save();
    }

    // ------------------------------------------------------------------
    // Layout helpers
    // ------------------------------------------------------------------

    boolean isEmpty() {
        boolean anyPage = false;
        for (List<Item> p : pages) {
            for (Item it : p) {
                if (it != null) {
                    anyPage = true;
                    break;
                }
            }
            if (anyPage) break;
        }
        return !anyPage && dock.isEmpty();
    }

    int pageCount() {
        return Math.max(1, pages.size());
    }

    List<Item> page(int i) {
        while (pages.size() <= i) pages.add(new ArrayList<>());
        return pages.get(i);
    }

    /** Index of the first free slot on any page of the given capacity, or -1. */
    int findFreeSlot(int capacity) {
        for (int p = 0; p < pages.size(); p++) {
            List<Item> page = pages.get(p);
            for (int i = 0; i < capacity; i++) {
                if (i >= page.size() || page.get(i) == null) return p * 100000 + i;
            }
        }
        return -1;
    }

    boolean contains(String key) {
        for (List<Item> p : pages) {
            for (Item it : p) {
                if (it != null && it.key().equals(key)) return true;
            }
        }
        return false;
    }

    boolean dockContains(String key) {
        for (Item it : dock) {
            if (it != null && it.key().equals(key)) return true;
        }
        return false;
    }

    void addToDock(Item item, int max) {
        if (dock.size() >= max) dock.remove(dock.size() - 1);
        dock.add(item.copy());
    }

    void removeDockAt(int index) {
        if (index >= 0 && index < dock.size()) dock.remove(index);
    }

    void removeFromDock(String key) {
        for (int i = 0; i < dock.size(); i++) {
            Item it = dock.get(i);
            if (it != null && it.key().equals(key)) {
                dock.remove(i);
                i--;
            }
        }
    }

    void removeFromHome(String key) {
        for (List<Item> p : pages) {
            for (int i = 0; i < p.size(); i++) {
                Item it = p.get(i);
                if (it != null && it.key().equals(key)) p.set(i, null);
            }
        }
    }

    /** New shortcuts go on their own page so a widget can never cover them. */
    void addToNewPage(Item item) {
        if (item == null) return;
        List<Item> page = new ArrayList<>();
        page.add(item.copy());
        pages.add(page);
    }

    void addToHome(Item item, int capacity) {
        for (List<Item> p : pages) {
            for (int i = 0; i < capacity; i++) {
                if (i >= p.size() || p.get(i) == null) {
                    while (p.size() <= i) p.add(null);
                    p.set(i, item.copy());
                    return;
                }
            }
        }
        List<Item> np = new ArrayList<>();
        np.add(item.copy());
        pages.add(np);
    }

    /** Pad each page to the grid capacity; re-pack if any page overflows. */
    void normalize(int capacity) {
        // Remove only the trailing padding first. Otherwise a previous, larger grid
        // (for example with the dock hidden) looks like an overflowing page when the
        // layout shrinks again, and reflow would merge separate pages together.
        for (List<Item> p : pages) {
            int last = -1;
            for (int i = 0; i < p.size(); i++) {
                if (p.get(i) != null) last = i;
            }
            while (p.size() > last + 1) p.remove(p.size() - 1);
        }
        boolean overflow = false;
        for (List<Item> p : pages) {
            if (p.size() > capacity) {
                overflow = true;
                break;
            }
        }
        if (overflow) reflow(capacity);
        for (List<Item> p : pages) {
            while (p.size() < capacity) p.add(null);
        }
        if (pages.isEmpty()) pages.add(new ArrayList<>());
    }

    /** Drop trailing pages that hold nothing. */
    void trim() {
        for (int i = pages.size() - 1; i > 0; i--) {
            boolean empty = true;
            for (Item it : pages.get(i)) {
                if (it != null) {
                    empty = false;
                    break;
                }
            }
            boolean hasWidget = false;
            for (PlacedWidget w : widgets) {
                if (w.page == i) {
                    hasWidget = true;
                    break;
                }
            }
            if (empty && !hasWidget) pages.remove(i);
            else break;
        }
    }

    /** Reflow items into grids of the given capacity, preserving order. */
    void reflow(int capacity) {
        List<Item> all = new ArrayList<>();
        for (List<Item> p : pages) {
            for (Item it : p) {
                if (it != null) all.add(it);
            }
        }
        pages.clear();
        List<Item> page = new ArrayList<>();
        for (Item it : all) {
            if (page.size() >= capacity) {
                pages.add(page);
                page = new ArrayList<>();
            }
            page.add(it);
        }
        pages.add(page);
    }
}
