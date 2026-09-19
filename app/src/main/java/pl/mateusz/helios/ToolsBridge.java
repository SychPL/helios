package pl.mateusz.helios;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;

import org.json.JSONObject;

import java.security.MessageDigest;

/**
 * The Android half of talking to the clock tools (SPEC 0.12 pkt 4.1, 6.2, 6.3).
 *
 * <p>All the rules live in {@link ToolsCall} and {@link ToolsTrust}, which are plain classes with tests. This one
 * only builds the intent, reads the answer and remembers which request is still waiting, because those are the
 * parts that need Android and cannot be tested on a JVM.
 */
final class ToolsBridge {
    static final int REQUEST_CODE = 0x70015;

    private static final String ACTION = "pl.mateusz.clockadbprobe.action.BRIDGE";
    private static final String COMPONENT = "pl.mateusz.clockadbprobe.BridgeActivity";
    private static final int API = 1;

    private static final String PREFS = "helios";
    private static final String KEY_ACCEPTED = "tools_fingerprint";
    private static final String KEY_PENDING_ID = "tools_pending_op_id";
    private static final String KEY_PENDING_OP = "tools_pending_op";

    private ToolsBridge() {}

    /** What the package manager says about the tool right now, or null when it is not installed. */
    @SuppressWarnings("deprecation")
    static ToolsTrust.Installed installed(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(ToolsTrust.TOOL_PACKAGE, PackageManager.GET_SIGNATURES);
            Signature[] signatures = info.signatures;
            StringBuilder fingerprint = new StringBuilder();
            for (Signature signature : signatures == null ? new Signature[0] : signatures) {
                if (fingerprint.length() > 0) fingerprint.append('+');
                fingerprint.append(sha256(signature.toByteArray()));
            }
            return new ToolsTrust.Installed(fingerprint.toString(), info.versionCode);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The version of the installed tool, empty when it is not there at all. */
    static String installedVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(ToolsTrust.TOOL_PACKAGE, 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    static ToolsTrust.Status status(Context context) {
        return ToolsTrust.check(installed(context), prefs(context).getString(KEY_ACCEPTED, ""));
    }

    /** Recording the fingerprint the user agreed to; installing it was not the same as trusting it. */
    static void accept(Context context) {
        ToolsTrust.Installed tool = installed(context);
        if (tool == null) return;
        prefs(context).edit().putString(KEY_ACCEPTED, tool.fingerprint).commit();
    }

    /**
     * The intent for one operation. Explicit, with no flag beyond the URI grant a file operation needs: anything
     * else would let the system reuse a screen that already carries somebody's consent.
     */
    static Intent intentFor(String op, String argsJson, String opId, Uri file) {
        Intent intent = new Intent(ACTION);
        intent.setClassName(ToolsTrust.TOOL_PACKAGE, COMPONENT);
        intent.putExtra("api", API);
        intent.putExtra("op", op);
        intent.putExtra("args", argsJson == null ? "" : argsJson);
        intent.putExtra("op_id", opId);
        if (file != null) {
            intent.setData(file);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return intent;
    }

    /**
     * Starts an operation, writing down which one it is before the call goes out: the process may not survive long
     * enough to see the answer, and then the record is the only way back to it.
     */
    static String start(Activity activity, String op, String argsJson, Uri file) {
        if (!ToolsTrust.mayCall(status(activity))) return null;
        String opId = ToolsCall.newOpId();
        remember(activity, opId, op);
        activity.startActivityForResult(intentFor(op, argsJson, opId, file), REQUEST_CODE);
        return opId;
    }

    /**
     * A read. It carries its own identifier, because it is its own request, and asks about the operation we are
     * waiting for. The answer therefore never matches the pending identifier, and must not be judged by that.
     */
    static String ask(Activity activity, String aboutOpId) {
        if (!ToolsTrust.mayCall(status(activity))) return null;
        String opId = ToolsCall.newOpId();
        String args = aboutOpId == null ? "" : ToolsCall.aboutArgs(aboutOpId);
        activity.startActivityForResult(intentFor("state", args, opId, null), REQUEST_CODE);
        return opId;
    }

    static void remember(Context context, String opId, String op) {
        prefs(context).edit().putString(KEY_PENDING_ID, opId).putString(KEY_PENDING_OP, op).commit();
    }

    static String pendingOpId(Context context) {
        String opId = prefs(context).getString(KEY_PENDING_ID, "");
        return opId.isEmpty() ? null : opId;
    }

    static String pendingOp(Context context) {
        return prefs(context).getString(KEY_PENDING_OP, "");
    }

    /**
     * Closes the pending record, but only on a stage that cannot change by itself. Not knowing (an empty stage,
     * a cancelled screen, a lost answer) keeps the record: the work may well be running, and the record is the
     * only way back to it.
     */
    static void observe(Context context, String opId, String stage) {
        if (opId == null || !opId.equals(pendingOpId(context))) return;
        if (ToolsCall.terminal(stage)) {
            prefs(context).edit().remove(KEY_PENDING_ID).remove(KEY_PENDING_OP).commit();
        }
    }

    /** Drops the record without waiting for a stage, for an answer that settles the operation by itself. */
    static void forget(Context context, String opId) {
        if (opId == null || !opId.equals(pendingOpId(context))) return;
        prefs(context).edit().remove(KEY_PENDING_ID).remove(KEY_PENDING_OP).commit();
    }

    static String statusOf(Intent data) {
        return data == null ? null : data.getStringExtra("status");
    }

    static String detailOf(Intent data) {
        return data == null ? "" : String.valueOf(data.getStringExtra("detail"));
    }

    static String opIdOf(Intent data) {
        return data == null ? null : data.getStringExtra("op_id");
    }

    static String snapshotOf(Intent data) {
        return data == null ? "{}" : String.valueOf(data.getStringExtra("state"));
    }

    /** The state Helios can see about itself, merged with what only the tool can see. */
    static ToolsState stateFrom(Context context, String snapshotJson) {
        PackageManager pm = context.getPackageManager();
        boolean recordAudio = pm.checkPermission("android.permission.RECORD_AUDIO", context.getPackageName())
                == PackageManager.PERMISSION_GRANTED;
        boolean canWrite = android.provider.Settings.System.canWrite(context);
        String home = "";
        try {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo info = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null && info.activityInfo != null) home = info.activityInfo.packageName;
        } catch (Throwable ignored) {
            // an unknown home app simply means the entry stays offered
        }
        return ToolsState.from(status(context), snapshotJson, recordAudio, canWrite,
                context.getPackageName().equals(home), true);
    }

    static String grantArgs(String permission) {
        try {
            return new JSONObject().put("permission", permission).toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
