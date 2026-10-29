package com.ben.pavo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

/** Minimal read-only provider used to hand files to external viewer/share apps. */
public final class PavoFileProvider extends ContentProvider {
    static final String AUTHORITY = "com.ben.pavo.files";

    static Uri uriFor(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("open")
                .appendQueryParameter("path", file.getAbsolutePath())
                .build();
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        File file = fileFor(uri);
        if (file == null) return null;
        if (file.isDirectory()) return "resource/folder";
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "*/*";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        File file = fileFor(uri);
        if (file == null || !file.isFile()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File fileFor(Uri uri) {
        if (uri == null) return null;
        String path = uri.getQueryParameter("path");
        if (path == null || path.isEmpty()) return null;
        try {
            File file = new File(path).getCanonicalFile();
            String canonical = file.getAbsolutePath();
            if (!canonical.startsWith("/storage/")
                    && !canonical.startsWith("/data/media/")) return null;
            return file;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
