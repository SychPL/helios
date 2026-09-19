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
    private static final String FILE = "update.apk";

    /** Where the file to share lives; nothing else in the app's storage is reachable through this provider. */
    static File shared(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    static Uri uriFor(Context context) {
        return shared(context).isFile() ? Uri.parse("content://" + AUTHORITY + "/" + FILE) : null;
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
        cursor.addRow(new Object[]{FILE, file.length()});
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
        return FILE.equals(path) ? shared(getContext()) : null;
    }
}
