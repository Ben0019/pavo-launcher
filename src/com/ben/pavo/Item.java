package com.ben.pavo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One slot on the desktop or dock. */
final class Item {
    static final int TYPE_APP = 0;
    static final int TYPE_WEB = 1;
    static final int TYPE_WIDGET = 2;
    static final int TYPE_FOLDER = 3;

    int type = TYPE_APP;
    String pkg;
    String cls;
    String url;
    String title;
    String id;
    int widgetId = -1;
    final List<Item> children = new ArrayList<>();

    Item() {}

    static Item app(String pkg, String cls) {
        Item i = new Item();
        i.type = TYPE_APP;
        i.pkg = pkg;
        i.cls = cls;
        return i;
    }

    static Item web(String url, String title) {
        Item i = new Item();
        i.type = TYPE_WEB;
        i.url = url;
        i.title = title;
        return i;
    }

    static Item folder(String name, List<Item> children) {
        Item i = new Item();
        i.type = TYPE_FOLDER;
        i.title = name;
        i.id = "f" + System.currentTimeMillis()
                + Integer.toHexString((int) (Math.random() * 0xFFFF));
        if (children != null) {
            for (Item c : children) if (c != null) i.children.add(c.copy());
        }
        return i;
    }

    String key() {
        switch (type) {
            case TYPE_WEB:
                return "web:" + url;
            case TYPE_WIDGET:
                return "widget:" + widgetId;
            case TYPE_FOLDER:
                return "folder:" + id;
            default:
                return "app:" + pkg + "/" + cls;
        }
    }

    Item copy() {
        Item i = new Item();
        i.type = type;
        i.pkg = pkg;
        i.cls = cls;
        i.url = url;
        i.title = title;
        i.id = id;
        i.widgetId = widgetId;
        for (Item c : children) i.children.add(c.copy());
        return i;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("t", type);
        if (pkg != null) o.put("p", pkg);
        if (cls != null) o.put("c", cls);
        if (url != null) o.put("u", url);
        if (title != null) o.put("n", title);
        if (id != null) o.put("id", id);
        if (widgetId >= 0) o.put("w", widgetId);
        if (!children.isEmpty()) {
            JSONArray kids = new JSONArray();
            for (Item c : children) kids.put(c.toJson());
            o.put("kids", kids);
        }
        return o;
    }

    static Item fromJson(JSONObject o) {
        Item i = new Item();
        i.type = o.optInt("t", TYPE_APP);
        i.pkg = o.isNull("p") ? null : o.optString("p", null);
        i.cls = o.isNull("c") ? null : o.optString("c", null);
        i.url = o.isNull("u") ? null : o.optString("u", null);
        i.title = o.isNull("n") ? null : o.optString("n", null);
        i.id = o.isNull("id") ? null : o.optString("id", null);
        i.widgetId = o.optInt("w", -1);
        JSONArray kids = o.optJSONArray("kids");
        if (kids != null) {
            for (int k = 0; k < kids.length(); k++) {
                JSONObject ko = kids.optJSONObject(k);
                if (ko != null) i.children.add(fromJson(ko));
            }
        }
        return i;
    }
}
