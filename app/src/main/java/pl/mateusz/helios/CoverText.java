package pl.mateusz.helios;

/** Polish cover state lines for the Rolety tile and panel rows, plus the supported_features bits (SPEC 0.9 pkt 4). Pure. */
final class CoverText {
    static final int OPEN=1,CLOSE=2,STOP=8;
    private CoverText(){}
    /** "<prefix>: <stan>": zamknięta / otwieranie / zamykanie / NN% (open with position) / otwarta / brak danych. */
    static String line(String prefix,EntityStates.Entity e){return prefix+": "+state(e);}
    static String state(EntityStates.Entity e){
        if(e==null||!e.known())return "brak danych";
        switch(e.state){
            case "closed":return "zamknięta";
            case "opening":return "otwieranie";
            case "closing":return "zamykanie";
            case "open":{
                String position=e.attribute("current_position");
                if(position!=null)try{return Math.round(Double.parseDouble(position))+"%";}catch(NumberFormatException ignored){}
                return "otwarta";
            }
            default:return e.state;
        }
    }
    /** A known state other than closed tints the icon with the accent, like the single cover tile does today. */
    static boolean attention(EntityStates.Entity e){return e!=null&&e.known()&&!e.state.equals("closed");}
    /** supported_features bit test; unknown or malformed attribute means the feature is absent. */
    static boolean has(EntityStates.Entity e,int bit){
        if(e==null||!e.known())return false;
        String raw=e.attribute("supported_features");
        if(raw==null)return false;
        try{return (Math.round(Double.parseDouble(raw))&bit)!=0;}catch(NumberFormatException ex){return false;}
    }
}
