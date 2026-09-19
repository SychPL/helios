package pl.mateusz.helios;

/**
 * Whether Helios is willing to talk to the tool on the other side (SPEC 0.12 pkt 3, 4.3, 6.2).
 *
 * <p>Trust runs both ways. The tool asks the user before it lets Helios grant permissions, and Helios asks the user
 * before it hands an installed package that power. A package name is not identity: uninstall the tool, install
 * something else under the same name, and the name is unchanged. The signing certificate is not.
 *
 * <p>Installing is also not accepting: agreeing to a system install dialog is not agreeing that this app may hand
 * out permissions, so the fingerprint is recorded as seen and accepted separately.
 */
final class ToolsTrust {
    static final String TOOL_PACKAGE = "pl.mateusz.clockadbprobe";
    /** The first tool build that speaks the bridge; 31 is the last one without it. */
    static final int MIN_VERSION_CODE = 32;

    enum Status {
        /** Not installed: a normal state, the menu offers to fetch it. */
        ABSENT,
        /** Installed, never accepted by the user. */
        NEEDS_ACCEPT,
        /** Installed, but signed by someone else than the accepted build. */
        CHANGED,
        /** Installed and accepted, but too old to speak the bridge. */
        TOO_OLD,
        OK
    }

    /** What the package manager says about the installed tool right now. */
    static final class Installed {
        final String fingerprint;
        final int versionCode;

        Installed(String fingerprint, int versionCode) {
            this.fingerprint = fingerprint == null ? "" : fingerprint;
            this.versionCode = versionCode;
        }
    }

    private ToolsTrust() {}

    static Status check(Installed tool, String acceptedFingerprint) {
        if (tool == null) return Status.ABSENT;
        if (acceptedFingerprint == null || acceptedFingerprint.isEmpty()) return Status.NEEDS_ACCEPT;
        if (!acceptedFingerprint.equals(tool.fingerprint)) return Status.CHANGED;
        if (tool.versionCode < MIN_VERSION_CODE) return Status.TOO_OLD;
        return Status.OK;
    }

    /** Nothing may be sent to the tool unless this is true, a file included. */
    static boolean mayCall(Status status) {
        return status == Status.OK;
    }

    /** What to tell the user when a call cannot go out. */
    static String explain(Status status) {
        switch (status) {
            case ABSENT: return "Narzędzia zegara nie są zainstalowane";
            case NEEDS_ACCEPT: return "Zaakceptuj narzędzia zegara w menu";
            case CHANGED: return "Narzędzia zegara mają inny podpis niż zaakceptowany";
            case TOO_OLD: return "Narzędzia zegara są za stare, zaktualizuj je";
            default: return "";
        }
    }
}
