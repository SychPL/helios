package pl.mateusz.helios;

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

/**
 * Hands one file, read only, to whoever was granted it (SPEC 0.12 pkt 5.8).
 *
 * <p>This exists because there is no FileProvider here: the app has no AndroidX, and pulling in a whole library to
 * share a single file would be the wrong trade. The rules are therefore written out: not exported, grants only,
 * one fixed file name, read mode only, and no writes or deletes of any kind.
 */
public final class ApkProvider extends ContentProvider {
    static final String AUTHORITY = "pl.mateusz.helios.files";
    private static final String DIR = "shared";
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[0-9a-f]{32}\\.apk");

    /**
     * One file per hand-off, named after the operation. A single shared name would let the next update truncate the
     * file the tools are still reading, and an open descriptor does not freeze the bytes behind it.
     */
    static File shared(Context context, String opId) {
        File dir = new File(context.getFilesDir(), DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, opId + ".apk");
    }

    static Uri uriFor(Context context, String opId) {
        return shared(context, opId).isFile() ? Uri.parse("content://" + AUTHORITY + "/" + opId + ".apk") : null;
    }

    /** Files of hand-offs that are over; called at startup so a killed process leaves nothing behind. */
    static void sweep(Context context, String keepOpId) {
        File[] files = new File(context.getFilesDir(), DIR).listFiles();
        if (files == null) return;
        for (File file : files) {
            if (keepOpId != null && file.getName().equals(keepOpId + ".apk")) continue;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        File file = fileOf(uri);
        if (file == null || !file.isFile()) throw new FileNotFoundException("nothing to share");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File file = fileOf(uri);
        if (file == null || !file.isFile()) return null;
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{file.getName(), file.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read only");
    }

    /** Only the one path is servable; anything else is not a mistake to correct but a request to refuse. */
    private File fileOf(Uri uri) {
        if (getContext() == null || uri == null) return null;
        String path = uri.getLastPathSegment();
        // only a name this app minted is servable: no traversal, no guessing, no other file in our storage
        if (path == null || !NAME.matcher(path).matches()) return null;
        return new File(new File(getContext().getFilesDir(), DIR), path);
    }
}
