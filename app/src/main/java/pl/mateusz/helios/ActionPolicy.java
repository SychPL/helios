package pl.mateusz.helios;

import java.util.*;

/**
 * The only place that turns a tap into an HA service or a panel. The config names an intent, never a service, and the
 * target is always the tile's own entity (SPEC 0.5 pkt 11.4, redrawn for version 6 in docs/ha-dashboard.md). Pure.
 */
final class ActionPolicy {
    enum Panel {COVER,COVER_GROUP,MUSIC_LIBRARY,DETAILS}
    /** What a `tile` may do on tap; `Item.action` stores the lower-case name. Legacy types keep their own action words. */
    enum Intent {NONE,TOGGLE,TURN_ON,TURN_OFF,OPEN,CLOSE,STOP,CONTROLS,DETAILS,ACTIVATE,LOCK,UNLOCK}
    /** One hard-coded service on one entity, asked about with `question` when the item wants a confirmation. */
    static final class Call {
        final String domain,service,entity,question;
        Call(String domain,String service,String entity,String question){this.domain=domain;this.service=service;this.entity=entity;this.question=question;}
    }
    private ActionPolicy(){}
    private static final Set<Intent> READ_ONLY=EnumSet.of(Intent.NONE,Intent.DETAILS);
    private static final Set<Intent> SWITCHABLE=EnumSet.of(Intent.NONE,Intent.DETAILS,Intent.TOGGLE,Intent.TURN_ON,Intent.TURN_OFF);
    private static final Set<Intent> COVER=EnumSet.of(Intent.NONE,Intent.DETAILS,Intent.CONTROLS,Intent.OPEN,Intent.CLOSE,Intent.STOP);
    private static final Set<Intent> LOCK=EnumSet.of(Intent.NONE,Intent.DETAILS,Intent.LOCK,Intent.UNLOCK);
    private static final Set<Intent> ACTIVATABLE=EnumSet.of(Intent.NONE,Intent.DETAILS,Intent.ACTIVATE);
    private static final List<String> SWITCH_DOMAINS=Arrays.asList("light","switch","input_boolean","fan"),RUN_DOMAINS=Arrays.asList("script","scene"),PRESS_DOMAINS=Arrays.asList("input_button","button");

    /** Intents a tile on this domain may configure; everything else is display-only. */
    static Set<Intent> allowed(String domain){
        if(SWITCH_DOMAINS.contains(domain))return SWITCHABLE;
        if(domain.equals("cover"))return COVER;
        if(domain.equals("lock"))return LOCK;
        if(RUN_DOMAINS.contains(domain)||PRESS_DOMAINS.contains(domain))return ACTIVATABLE;
        return READ_ONLY;
    }
    /** Without tap_action: covers open their panel, scripts and scenes run, everything else shows its details. */
    static Intent defaultIntent(String domain){
        if(domain.equals("cover"))return Intent.CONTROLS;
        if(RUN_DOMAINS.contains(domain)||PRESS_DOMAINS.contains(domain))return Intent.ACTIVATE;
        return Intent.DETAILS;
    }
    /** tap_action.action -> intent; null for a word the catalogue does not know. */
    static Intent intent(String name){
        if(name==null)return null;
        for(Intent i:Intent.values())if(name(i).equals(name))return i;
        return null;
    }
    static String name(Intent i){return i.name().toLowerCase(Locale.ROOT);}
    /** A lock is never worked without a question, whatever the config says. */
    static boolean forcedConfirm(Intent i){return i==Intent.LOCK||i==Intent.UNLOCK;}
    /** The HA service for an intent on a domain; null when the intent opens a panel or does nothing. */
    static String service(Intent i,String domain){
        switch(i){
            case TOGGLE:return "toggle";case TURN_ON:return "turn_on";case TURN_OFF:return "turn_off";
            case OPEN:return "open_cover";case CLOSE:return "close_cover";case STOP:return "stop_cover";
            case LOCK:return "lock";case UNLOCK:return "unlock";
            case ACTIVATE:return PRESS_DOMAINS.contains(domain)?"press":"turn_on";
            default:return null;
        }
    }
    static String question(Intent i,String label){
        switch(i){
            case TOGGLE:return "Przełączyć: "+label+"?";case TURN_ON:return "Włączyć: "+label+"?";case TURN_OFF:return "Wyłączyć: "+label+"?";
            case OPEN:return "Otworzyć: "+label+"?";case CLOSE:return "Zamknąć: "+label+"?";case STOP:return "Zatrzymać: "+label+"?";
            case ACTIVATE:return "Uruchomić: "+label+"?";case LOCK:return "Zamknąć zamek: "+label+"?";case UNLOCK:return "Otworzyć zamek: "+label+"?";
            default:return null;
        }
    }
    /** Button word in the details dialog. */
    static String verb(Intent i){
        switch(i){
            case TOGGLE:return "Przełącz";case TURN_ON:return "Włącz";case TURN_OFF:return "Wyłącz";case OPEN:return "Otwórz";case CLOSE:return "Zamknij";case STOP:return "Zatrzymaj";
            case CONTROLS:return "Sterowanie";case ACTIVATE:return "Uruchom";case LOCK:return "Zamknij zamek";case UNLOCK:return "Otwórz zamek";default:return null;
        }
    }
    /** Actions that open a panel instead of calling a service; null otherwise. */
    static Panel panel(String action){
        if(action==null)return null;
        switch(action){case "controls":return Panel.COVER;case "covers":return Panel.COVER_GROUP;case "library":return Panel.MUSIC_LIBRARY;case "details":return Panel.DETAILS;default:return null;}
    }
    /** The service call an item's action stands for; null when the action opens a panel or the item is not tappable. */
    static Call call(DashboardSpec.Item item,String label){
        if(item.action==null)return null;
        if(item.action.equals("lights_off"))return new Call("light","turn_off",item.offEntity,"Zgasić światła?");
        Intent i=intent(item.action);
        return i==null?null:call(item,i,label);
    }
    /** A service call for an intent on the item's own entity; null for panels and NONE. The garage keeps its old question. */
    static Call call(DashboardSpec.Item item,Intent i,String label){
        String domain=item.entity.substring(0,item.entity.indexOf('.')),service=service(i,domain);
        if(service==null)return null;
        return new Call(domain,service,item.entity,i==Intent.CLOSE&&item.type.equals("garage")?"Zamknąć bramę?":question(i,label));
    }
    /** The state an action can work on: a known one, except that a button, scene or script reads `unknown` until first used and may still be activated (never a missing or unavailable entity). */
    static boolean usable(String action,EntityStates.Entity e){
        if(e==null)return false;
        return "activate".equals(action)?!e.state.equals("unavailable"):e.known();
    }
    /** unknown/unavailable: the tile is visible but inactive, nothing is sent (SPEC 0.9 pkt 5); panels without an own entity and the details dialog are exempt. */
    static boolean needsKnown(String action){
        if(action==null)return false;
        switch(action){case "lights_off":case "controls":return true;case "covers":case "library":case "details":return false;default:return intent(action)!=null;}
    }
    /** How a tile with this action is gated: service calls wait for a known state and no call in flight, the cover panel for a known state, dialogs never. */
    static CardDefinition.Gate gate(String action){
        if(action==null)return CardDefinition.Gate.NONE;
        if(action.equals("controls"))return CardDefinition.Gate.KNOWN;
        return needsKnown(action)?CardDefinition.Gate.KNOWN_NOT_PENDING:CardDefinition.Gate.NONE;
    }
}
