package pl.mateusz.helios;

import java.util.*;

/**
 * What an `alerts` tile and its list show (SPEC 0.20), from one entity snapshot. Pure: no Views, no clock of its own.
 * A condition has three answers, not two: a missing, unknown or unavailable entity, or no live session at all, is
 * "we do not know" - never "nothing to see", so a stand-in card can never hide missing data.
 */
final class AlertsModel {
    enum Cond {ACTIVE,INACTIVE,UNKNOWN}
    static final String DEFAULT_ICON="mdi:alert-circle";
    /** The stand-in card waits this long in the all-clear before it takes the place over. */
    static final long EMPTY_DELAY_MS=2000;

    /** One source as it stands now; text is null when the text entity has nothing to say. */
    static final class Row {
        final int index;final DashboardSpec.Source source;final Cond cond;final String text;final long since;
        Row(int index,DashboardSpec.Source source,Cond cond,String text,long since){this.index=index;this.source=source;this.cond=cond;this.text=text;this.since=since;}
        String icon(){return source.icon!=null?source.icon:DEFAULT_ICON;}
        /** Stable across refreshes, for keeping a row and its scroll position. */
        String key(){return source.whenEntity+"="+source.whenState;}
    }
    /** One line of the tile's preview: an icon and a title, or the "+N pozostałych" overflow line. */
    static final class Slot {
        final String icon,title;
        Slot(String icon,String title){this.icon=icon;this.title=title;}
    }

    /** Active rows newest first (a row without a time last, ties in config order), then unknown rows in config order. */
    final List<Row> active,unknown;
    final boolean live;

    AlertsModel(List<DashboardSpec.Source> sources,Map<String,EntityStates.Entity> states,boolean live){
        this.live=live;
        List<Row> a=new ArrayList<>(),u=new ArrayList<>();
        for(int i=0;i<sources.size();i++){
            DashboardSpec.Source s=sources.get(i);
            Cond c=cond(s,states,live);
            if(c==Cond.INACTIVE)continue;
            EntityStates.Entity text=states.get(s.entity),when=states.get(s.whenEntity);
            Row row=new Row(i,s,c,text!=null&&text.known()?text.state:null,when!=null?when.lastChanged:0); // show_since only hides the time on screen; the order still follows it
            (c==Cond.ACTIVE?a:u).add(row);
        }
        a.sort((x,y)->{
            if((x.since==0)!=(y.since==0))return x.since==0?1:-1;
            if(x.since!=y.since)return Long.compare(y.since,x.since);
            return Integer.compare(x.index,y.index);
        });
        active=Collections.unmodifiableList(a);unknown=Collections.unmodifiableList(u);
    }
    static Cond cond(DashboardSpec.Source s,Map<String,EntityStates.Entity> states,boolean live){
        if(!live)return Cond.UNKNOWN;
        EntityStates.Entity e=states.get(s.whenEntity);
        if(e==null||!e.known())return Cond.UNKNOWN;
        return e.state.equals(s.whenState)?Cond.ACTIVE:Cond.INACTIVE;
    }
    int a(){return active.size();}
    int u(){return unknown.size();}
    /** Everything known and nothing active: the only state in which the stand-in card may appear. */
    boolean quiet(){return active.isEmpty()&&unknown.isEmpty();}
    /** The big number: how many apply, a dash while only unknowns are left, 0 when all is clear. */
    String count(){return !active.isEmpty()?String.valueOf(active.size()):!unknown.isEmpty()?"-":"0";}
    /** The word in place of the preview when nothing is active; null while something is. */
    String idleWord(){return !active.isEmpty()?null:!unknown.isEmpty()?"Brak danych":"Brak uwag";}
    /** "Brak danych: U" under an active preview; null when there is none to report or no active row to report it under. */
    String footer(){return active.isEmpty()||unknown.isEmpty()?null:"Brak danych: "+unknown.size();}
    /** 1x1: the newest title, and " +N" for the rest (the view reserves room for the suffix and ellipsizes only the title). */
    String summaryTitle(){return active.isEmpty()?null:active.get(0).source.title;}
    String summarySuffix(){return active.size()>1?" +"+(active.size()-1):"";}
    /** Up to `max` preview lines; past that, max-1 sources and one "+N pozostałych" line. */
    List<Slot> slots(int max){
        List<Slot> out=new ArrayList<>();
        int n=active.size(),shown=n<=max?n:max-1;
        for(int i=0;i<shown;i++){Row r=active.get(i);out.add(new Slot(r.icon(),r.source.title));}
        if(n>max)out.add(new Slot("mdi:dots-horizontal","+"+(n-shown)+" pozostałych"));
        return out;
    }
    /** Screen reader line for the whole tile. */
    String description(String title){
        StringBuilder b=new StringBuilder(title).append(": ");
        if(!live)b.append("brak połączenia, dane nieaktualne");
        else if(active.isEmpty())b.append(unknown.isEmpty()?"brak uwag":"brak danych");
        else{
            b.append(active.size()).append(active.size()==1?" uwaga":" uwagi").append(": ");
            for(int i=0;i<active.size();i++){if(i>0)b.append(", ");b.append(active.get(i).source.title);}
            if(!unknown.isEmpty())b.append("; brak danych: ").append(unknown.size());
        }
        return b.toString();
    }

    private static final String[] MONTHS={"sty","lut","mar","kwi","maj","cze","lip","sie","wrz","paź","lis","gru"};
    /** "Aktywne od 14:32", "Aktywne od wczoraj 22:10", "Aktywne od 23 wrz 22:10" in the clock's zone; null without a time. */
    static String since(long at,long now,TimeZone zone){
        if(at<=0)return null;
        java.time.ZoneId z=zone.toZoneId();
        java.time.ZonedDateTime then=java.time.Instant.ofEpochMilli(at).atZone(z);
        long days=java.time.temporal.ChronoUnit.DAYS.between(then.toLocalDate(),java.time.Instant.ofEpochMilli(now).atZone(z).toLocalDate());
        String hm=then.getHour()+":"+String.format(Locale.ROOT,"%02d",then.getMinute());
        if(days<=0)return "Aktywne od "+hm; // today, or a clock a little behind HA's
        if(days==1)return "Aktywne od wczoraj "+hm;
        return "Aktywne od "+then.getDayOfMonth()+" "+MONTHS[then.getMonthValue()-1]+" "+hm;
    }

    /** Why "Zgaś" must not go out now (SPEC 0.20 pkt 4), or null when it may. Checked at the tap and again after the confirmation. */
    static String offBlock(DashboardSpec.Source s,Map<String,EntityStates.Entity> states,boolean live){
        if(s.offEntity==null)return "Brak świateł do zgaszenia";
        if(!live)return "Brak połączenia z Home Assistant";
        if(cond(s,states,true)!=Cond.ACTIVE)return s.title+" - już nieaktywne";
        EntityStates.Entity e=states.get(s.offEntity);
        if(e==null||!e.known())return s.title+" - brak danych";
        return null;
    }

    /**
     * "Zgaś" on one row (SPEC 0.20 pkt 4): one call at a time, a message in place of the text for 10 s or until the
     * source changes, and after a success the button rests until the warning goes or 10 s pass. An answer to an older
     * call (or one after the local 10 s limit) is ignored.
     */
    static final class OffState {
        static final long HOLD_MS=10_000;
        /** A call with no answer after this long counts as failed (the transport gives up at 10 s). */
        static final long LIMIT_MS=10_500;
        private boolean busy;private int call;private long heldUntil,messageUntil,sentAt;private String message,messageKey;
        boolean busy(long now,String key){if(busy&&now-sentAt>=LIMIT_MS)expire(call,key,now);return busy;}
        /** Resting after a success: the button is off, but not for lack of data. */
        boolean held(){return heldUntil>0;}
        /** Starts a call; returns its number for result(). */
        int send(long now){busy=true;sentAt=now;return ++call;}
        void result(int n,String error,String key,long now){
            if(n!=call||!busy)return;
            busy=false;
            if(error!=null)show("Nie udało się zgasić",key,now);else heldUntil=now+HOLD_MS;
        }
        /** The call that is still out after the limit counts as failed; its late answer then changes nothing. */
        void expire(int n,String key,long now){if(n==call&&busy){result(n,"timeout",key,now);call++;}}
        /** A message tied to the source as it is now (its condition and text); a change of either drops it. */
        void show(String text,String key,long now){message=text;messageKey=key;messageUntil=now+HOLD_MS;}
        String message(String key,long now){
            if(message!=null&&(now>=messageUntil||!key.equals(messageKey)))message=null;
            return message;
        }
        void observe(boolean active,long now){if(heldUntil>0&&(!active||now>=heldUntil))heldUntil=0;}
        /** The next moment this row changes by itself, or -1. */
        long due(){long d=-1;if(heldUntil>0)d=heldUntil;if(busy)d=d<0?sentAt+LIMIT_MS:Math.min(d,sentAt+LIMIT_MS);if(message!=null)d=d<0?messageUntil:Math.min(d,messageUntil);return d;}
    }

    /** The 2 s wait before the stand-in card: only while everything stays known and clear; any warning or unknown resets it at once. */
    static final class EmptyGate {
        private long quietSince=-1;
        /** Whether the stand-in card shows at `now`; call on every render. */
        boolean show(boolean quiet,long now){
            if(!quiet){quietSince=-1;return false;}
            if(quietSince<0)quietSince=now;
            return now-quietSince>=EMPTY_DELAY_MS;
        }
        /** When the card is due, or -1 when nothing is waiting. */
        long due(){return quietSince<0?-1:quietSince+EMPTY_DELAY_MS;}
    }
}
