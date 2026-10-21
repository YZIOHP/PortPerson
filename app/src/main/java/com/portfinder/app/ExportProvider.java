package com.portfinder.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Minimal read-only provider that lets other apps (Sheets, Gmail, Drive...) open the CSV files the
 * History tab exports. Serves only files directly inside cache/exports; nothing else is reachable.
 */
public final class ExportProvider extends ContentProvider {

    static Uri uriFor(Context c, File f) {
        return Uri.parse("content://" + c.getPackageName() + ".exports/" + Uri.encode(f.getName()));
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException("bad name");
        try {
            File dir = new File(getContext().getCacheDir(), "exports").getCanonicalFile();
            File f = new File(dir, name).getCanonicalFile();
            if (!dir.equals(f.getParentFile()) || !f.isFile()) throw new FileNotFoundException(name);
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException(name);
        }
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String p = uri.getLastPathSegment();
        if (p != null && p.endsWith(".csv")) return "text/csv";
        if (p != null && p.endsWith(".png")) return "image/png";
        if (p != null && p.endsWith(".pcap")) return "application/vnd.tcpdump.pcap";
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
