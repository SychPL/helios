package pl.mateusz.helios;

/** The only place that turns a tap into an HA service or a panel. Service names never come from the config (SPEC 0.5 pkt 11.4). Pure. */
final class ActionPolicy {
    enum Panel {COVER,COVER_GROUP,MUSIC_LIBRARY}
    /** One hard-coded service on one entity, asked about with `question` when the item wants a confirmation. */
    static final class Call {
        final String domain,service,entity,question;
        Call(String domain,String service,String entity,String question){this.domain=domain;this.service=service;this.entity=entity;this.question=question;}
    }
    private ActionPolicy(){}
    /** Actions that open a panel instead of calling a service; null otherwise. */
    static Panel panel(String action){
        if(action==null)return null;
        switch(action){case "controls":return Panel.COVER;case "covers":return Panel.COVER_GROUP;case "library":return Panel.MUSIC_LIBRARY;default:return null;}
    }
    /** The service call an action stands for; null when the action opens a panel or the item is not tappable. */
    static Call call(DashboardSpec.Item item,String label){
        if(item.action==null)return null;
        switch(item.action){
            case "toggle":return new Call("light","toggle",item.entity,"Przełączyć: "+label+"?");
            case "close":return new Call("cover","close_cover",item.entity,"Zamknąć bramę?");
            case "lights_off":return new Call("light","turn_off",item.offEntity,"Zgasić światła?");
            default:return null;
        }
    }
    /** unknown/unavailable: the tile is visible but inactive, nothing is sent (SPEC 0.9 pkt 5); panels without an own entity are exempt. */
    static boolean needsKnown(String action){
        if(action==null)return false;
        switch(action){case "toggle":case "close":case "lights_off":case "controls":return true;default:return false;}
    }
}
