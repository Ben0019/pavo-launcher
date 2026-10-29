package com.ben.pavo;

import android.content.Context;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Persistent, bounded history of performance sessions. */
final class PerfLogStore {
    private static final int MAX_ENTRIES = 200;
    private final File file;
    private final List<PerfLogEntry> entries = new ArrayList<>();

    PerfLogStore(Context c) {
        file = new File(c.getFilesDir(), "performance_logs.json");
    }

    synchronized void load() {
        entries.clear();
        if (!file.exists()) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file)))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                PerfLogEntry e = PerfLogEntry.fromJson(arr.getJSONObject(i));
                if (e.id > 0) entries.add(e);
            }
        } catch (Throwable ignored) {
        }
        sort();
    }

    synchronized List<PerfLogEntry> entries() {
        sort();
        return new ArrayList<>(entries);
    }

    synchronized void addOrUpdate(PerfLogEntry entry) {
        if (entry == null || entry.id <= 0) return;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == entry.id) {
                entries.set(i, entry);
                sort();
                return;
            }
        }
        entries.add(entry);
        sort();
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }

    synchronized void delete(long id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == id) {
                entries.remove(i);
                save();
                return;
            }
        }
    }

    /** Remove saved sessions for every app that are older than the global retention window. */
    synchronized void cleanupAll(int hours) {
        if (hours <= 0) return;
        load();
        long cutoff = System.currentTimeMillis() - hours * 60L * 60L * 1000L;
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            PerfLogEntry e = entries.get(i);
            if (e.startMs > 0 && e.startMs < cutoff) {
                entries.remove(i);
                changed = true;
            }
        }
        if (changed) save();
    }

    synchronized void clear() {
        entries.clear();
        save();
    }

    synchronized void save() {
        try {
            JSONArray arr = new JSONArray();
            for (PerfLogEntry e : entries) arr.put(e.toJson());
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(arr.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    private void sort() {
        Collections.sort(entries, new Comparator<PerfLogEntry>() {
            @Override
            public int compare(PerfLogEntry a, PerfLogEntry b) {
                return Long.compare(b.startMs, a.startMs);
            }
        });
    }
}
