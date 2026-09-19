package pl.mateusz.helios;

import java.util.ArrayList;
import java.util.List;

/**
 * Which entries the clock tools menu shows (SPEC 0.12 pkt 6.1).
 *
 * <p>Every entry has its own condition, so the menu never claims something is possible when it is not, and never
 * hides a repair because the state could not be measured. Without the tool there is exactly one entry, and that is
 * a normal state rather than a warning.
 */
final class ToolsMenu {
    static final String INSTALL = "Zainstaluj narzędzia";
    static final String ACCEPT = "Zaakceptuj narzędzia";
    static final String UPDATE = "Zaktualizuj narzędzia";
    static final String ROOT_AND_ADB = "Włącz root i ADB";
    static final String ADB_ON = "Włącz ADB";
    static final String ADB_OFF = "Wyłącz ADB";
    static final String MIC_FIX = "Napraw mikrofon";
    static final String MIC_RESTORE = "Przywróć mikrofon";
    static final String PERMISSION_MIC = "Uprawnienie mikrofonu";
    static final String ALLOW_BRIGHTNESS = "Pozwól na jasność";
    static final String SET_HOME = "Ustaw jako ekran główny";

    private ToolsMenu() {}

    static List<String> items(ToolsState state) {
        List<String> items = new ArrayList<>();
        if (state.trust == ToolsTrust.Status.ABSENT) {
            items.add(INSTALL);
            return items;
        }
        if (state.trust == ToolsTrust.Status.NEEDS_ACCEPT || state.trust == ToolsTrust.Status.CHANGED) {
            items.add(ACCEPT);
            return items;
        }
        if (state.trust == ToolsTrust.Status.TOO_OLD) {
            items.add(UPDATE);
            return items;
        }

        // the chain is offered whenever root is not known to be up, and hidden on a firmware that would refuse
        boolean chainPossible = !Boolean.FALSE.equals(state.firmwareSupported);
        if (!state.rooted() && chainPossible) items.add(ROOT_AND_ADB);
        if (state.rooted() && !Boolean.TRUE.equals(state.adbListening)) items.add(ADB_ON);
        if (Boolean.TRUE.equals(state.adbListening)) items.add(ADB_OFF);

        if (state.rooted() && state.micNeedsRepair()) items.add(MIC_FIX);
        if (state.micSaved) items.add(MIC_RESTORE);

        if (state.rooted() && !state.recordAudio) items.add(PERMISSION_MIC);
        if (state.rooted() && !state.canWriteSettings) items.add(ALLOW_BRIGHTNESS);
        if (state.rooted() && state.declaresHome && !state.isHome) items.add(SET_HOME);
        return items;
    }
}
