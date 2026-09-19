package pl.mateusz.helios;

import org.json.JSONObject;

import java.security.SecureRandom;

/**
 * The rules of talking to the tool, with no Android in sight (SPEC 0.12 pkt 4.1, 4.2).
 *
 * <p>Helios may never see an answer: its own process can die, the clock can lose power, the user can leave the
 * screen. So the identifier of a request is chosen here and written down before the call goes out, and what to do
 * next is decided from the registry rather than from whether a result arrived.
 */
final class ToolsCall {
    enum Next {
        /** The answer is final. */
        DONE,
        /** Nothing is settled: ask the registry again. */
        POLL
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private ToolsCall() {}

    static String newOpId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : bytes) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /**
     * What the answer means for the caller. "unknown" is not a verdict: it says the tool did not know at that
     * moment, which is a reason to ask again, not to give up.
     */
    static Next next(String status, boolean cancelled) {
        if (cancelled) return Next.POLL;
        if (status == null || status.isEmpty()) return Next.POLL;
        if ("in_progress".equals(status) || "unknown".equals(status)) return Next.POLL;
        return Next.DONE;
    }

    /** A late answer belongs to whoever asked; anything else is ignored. */
    static boolean acceptResult(String answeredOpId, String myOpId) {
        return myOpId != null && myOpId.equals(answeredOpId);
    }

    /** Args for asking the tool about an earlier request of ours. */
    static String aboutArgs(String opId) {
        try {
            return new JSONObject().put("about", opId).toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * How long the current stage has been running, from the snapshot alone. Times are monotonic and only comparable
     * within one boot, so a different boot means the difference says nothing: -1.
     */
    static long stageAgeMs(String snapshotJson, String knownBootId) {
        try {
            JSONObject about = new JSONObject(snapshotJson).optJSONObject("about");
            if (about == null) return -1;
            if (knownBootId != null && !knownBootId.isEmpty() && !knownBootId.equals(about.optString("boot_id"))) return -1;
            long now = about.optLong("now_uptime_ms", -1);
            long since = about.optLong("stage_since_uptime_ms", -1);
            if (now < 0 || since < 0 || now < since) return -1;
            return now - since;
        } catch (Exception e) {
            return -1;
        }
    }

    static String stageOf(String snapshotJson) {
        return stageOf(snapshotJson, null);
    }

    /**
     * The stage the snapshot reports, but only when it is about the request we asked about. A snapshot describing
     * somebody else's operation says nothing about ours, and treating it as ours would close a record too early.
     */
    static String stageOf(String snapshotJson, String aboutOpId) {
        try {
            JSONObject about = new JSONObject(snapshotJson).optJSONObject("about");
            if (about == null) return "";
            if (aboutOpId != null && !aboutOpId.equals(about.optString("op_id"))) return "";
            return about.optString("stage");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Whether a stage can still change by itself. An empty stage is not one of those: it means we did not learn
     * anything, which is a reason to ask again rather than to declare the operation over.
     */
    static boolean terminal(String aboutStage) {
        return "finished".equals(aboutStage) || "interrupted".equals(aboutStage) || "absent".equals(aboutStage);
    }

    /**
     * How long a machine stage of this operation may take (SPEC 0.12 pkt 4.2). Waiting for a human is not a machine
     * stage and has no limit, which is why the consent stage answers with something no age will ever exceed.
     */
    static long limitFor(String op, String stage) {
        if ("awaiting_consent".equals(stage) || "accepted".equals(stage) || stage == null || stage.isEmpty()) {
            return Long.MAX_VALUE;
        }
        if ("copying".equals(stage)) return 60_000;
        if ("installing".equals(stage)) return 120_000;
        if ("root_adb_on".equals(op)) return 240_000;
        if ("adb_on".equals(op) || "adb_off".equals(op)) return 45_000;
        return 20_000;
    }

    /** A second operation waits until the first one has reached a stage that cannot change by itself. */
    static boolean mayStartAnother(String pendingOpId, String aboutStage) {
        return pendingOpId == null || terminal(aboutStage);
    }
}
