package pl.mateusz.helios;

/**
 * The climate panel's one-call-at-a-time lifecycle (SPEC 0.19 pkt 5.1), as pure state with the time passed in:
 * a setpoint draft that waits 800 ms after the last tap, then exactly one call in flight until HA shows the value,
 * the service fails, or 10 s pass. While anything is pending, every control but -/+ during the draft is locked.
 */
final class ClimateFlow {
    static final long DRAFT_MS=800,CONFIRM_MS=10_000;
    private Double draft;private long draftDue;
    private String sentService;private Object sentValue;private long sentAt;
    private String message,failed; // failed: the service whose call failed or went unconfirmed

    /** A call is out or a draft is waiting: mode and list controls are locked. */
    boolean busy(){return draft!=null||sentService!=null;}
    /** -/+ extend a draft but wait while a call is out, so nothing is ever sent from a stale value. */
    boolean stepping(){return sentService==null;}
    String message(){return message;}
    /** The setpoint to show: the draft, else the value on its way, else HA's. */
    Double shown(ClimateModel m){
        if(draft!=null)return draft;
        if("set_temperature".equals(sentService))return (Double)sentValue;
        return m.target;
    }
    /** The neutral line under the setpoint. */
    String status(){
        if(draft!=null)return "Zmieniasz…";
        if("set_temperature".equals(sentService))return "Ustawianie…";
        if(!"set_temperature".equals(failed))return null;
        return "Brak potwierdzenia".equals(message)?message:"Nie udało się ustawić";
    }
    /** Which service is in flight, for its button's spinner; null when none. */
    String inFlight(){return sentService;}

    /** One -/+ tap; returns false when the step is not possible now. */
    boolean step(ClimateModel m,int direction,long now){
        if(!stepping())return false;
        Double from=shown(m);
        if(from==null||!m.canStep(from,direction))return false;
        draft=m.next(from,direction);draftDue=now+DRAFT_MS;message=null;failed=null;
        return true;
    }
    /** The draft is due for sending (or the panel is closing and flushes it): hands it over and forgets it. */
    Double takeDraft(long now,boolean force){
        if(draft==null||(!force&&now<draftDue))return null;
        Double d=draft;draft=null;return d;
    }
    long draftDue(){return draftDue;}
    /** Anything but a setpoint: only when nothing is pending. */
    boolean mayCall(){return !busy();}
    void sent(String service,Object value,long now){sentService=service;sentValue=value;sentAt=now;message=null;failed=null;}
    /** The service answered: success waits for HA's state, a failure ends the call at once. */
    void result(String error){
        if(sentService==null||error==null)return;
        failed=sentService;clearSent();message="Nie wykonano: "+error;
    }
    /** Host.send refused before anything went out (stale state, no connection): the reason goes to the header. */
    void refused(String service,String reason){failed=service;message=reason;}
    /** A new HA state or a timer tick: the call ends when HA shows the value, or with "Brak potwierdzenia" after 10 s. */
    void observe(ClimateModel m,long now){
        if(sentService==null)return;
        if(observed(m)){clearSent();message=null;failed=null;}
        else if(now-sentAt>=CONFIRM_MS){failed=sentService;clearSent();message="Brak potwierdzenia";}
    }
    long confirmDue(){return sentAt+CONFIRM_MS;}
    /** Connection lost, a new document, the app going away: an unsent draft is dropped, nothing is sent. */
    void discard(){draft=null;}
    private void clearSent(){sentService=null;sentValue=null;}
    private boolean observed(ClimateModel m){
        switch(sentService){
            case "set_temperature":return m.target!=null&&Math.abs(m.target-(Double)sentValue)<1e-6;
            case "set_hvac_mode":return sentValue.equals(m.state);
            default:for(ClimateModel.Group g:m.groups)if(g.service.equals(sentService))return sentValue.equals(g.current);return false;
        }
    }
}
