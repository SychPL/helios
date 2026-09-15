package pl.mateusz.plugin;

import android.content.Context;
import android.content.pm.PackageInfo;
import org.json.JSONObject;

public final class HeliosPackageStatus {
    public static String run(Context context, String argument) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo("pl.mateusz.helios", 0);
        return new JSONObject().put("package", info.packageName)
                .put("version", info.versionName).put("version_code", info.getLongVersionCode())
                .put("updated_at", info.lastUpdateTime).toString();
    }
}
