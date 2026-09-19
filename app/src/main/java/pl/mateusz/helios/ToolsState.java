package pl.mateusz.helios;

import org.json.JSONObject;

/**
 * What Helios knows about the tool and about itself (SPEC 0.12 pkt 5.1, 6.1, 6.3).
 *
 * <p>Two sources, deliberately. Anything Helios can measure about itself it measures itself: its own permission,
 * whether it may write system settings, whether it is the home app. Only what it cannot see, the root channel and
 * who holds the microphone, comes from the tool's snapshot. A field the tool could not measure stays unknown, and
 * unknown never hides a repair.
 */
final class ToolsState {
    final ToolsTrust.Status trust;

    /** From the snapshot; null means the tool could not tell. */
    final Boolean root;
    final Boolean adbListening;
    final Boolean firmwareSupported;
    final Integer micHolders;          // null = unknown, 0 = nobody, >0 = that many watched shells
    final boolean micSaved;

    /** Measured locally by Helios. */
    final boolean recordAudio;
    final boolean canWriteSettings;
    final boolean isHome;
    final boolean declaresHome;

    ToolsState(ToolsTrust.Status trust, Boolean root, Boolean adbListening, Boolean firmwareSupported,
               Integer micHolders, boolean micSaved, boolean recordAudio, boolean canWriteSettings,
               boolean isHome, boolean declaresHome) {
        this.trust = trust;
        this.root = root;
        this.adbListening = adbListening;
        this.firmwareSupported = firmwareSupported;
        this.micHolders = micHolders;
        this.micSaved = micSaved;
        this.recordAudio = recordAudio;
        this.canWriteSettings = canWriteSettings;
        this.isHome = isHome;
        this.declaresHome = declaresHome;
    }

    /** Builds the tool-side half from a snapshot; anything missing or "unknown" stays null. */
    static ToolsState from(ToolsTrust.Status trust, String snapshotJson, boolean recordAudio,
                           boolean canWriteSettings, boolean isHome, boolean declaresHome) {
        Boolean root = null;
        Boolean adbListening = null;
        Boolean firmwareSupported = null;
        Integer micHolders = null;
        boolean micSaved = false;
        try {
            JSONObject snapshot = new JSONObject(snapshotJson);
            root = bool(snapshot, "root");
            adbListening = bool(snapshot, "adb_listening");
            firmwareSupported = bool(snapshot, "firmware_supported");
            Object holders = snapshot.opt("mic_holders");
            if (holders instanceof org.json.JSONArray) micHolders = ((org.json.JSONArray) holders).length();
            micSaved = "saved".equals(snapshot.optString("mic_saved_state"));
        } catch (Exception ignored) {
            // no snapshot at all is the same as a snapshot that measured nothing
        }
        return new ToolsState(trust, root, adbListening, firmwareSupported, micHolders, micSaved,
                recordAudio, canWriteSettings, isHome, declaresHome);
    }

    private static Boolean bool(JSONObject snapshot, String key) {
        Object value = snapshot.opt(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    boolean rooted() {
        return Boolean.TRUE.equals(root);
    }

    /** Unknown counts as "maybe", because hiding a repair when we cannot see the problem is the worst case. */
    boolean micNeedsRepair() {
        return micHolders == null || micHolders > 0;
    }
}
