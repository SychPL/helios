package pl.mateusz.helios;

/**
 * Clock tile typography from SPEC 0.8a pkt 3.3: the hour is fitted to the width (cap 120) and to the height left after the
 * fixed lines; when the tile is too low, the weekday goes first, then the date; the full hour always beats a size floor.
 */
final class ClockLayout {
    static final int PAD=20,TITLE=18,DATE=24,WEEKDAY=20,HOUR_MIN=32,HOUR_MAX=120;
    static final float LINE=1.2f;
    static final class Plan {
        final float hourSize;final boolean showDate,showWeekday;
        Plan(float hourSize,boolean showDate,boolean showWeekday){this.hourSize=hourSize;this.showDate=showDate;this.showWeekday=showWeekday;}
        /** Total height the plan needs inside a tile with the given title flag; tests assert it never exceeds the tile. */
        float height(boolean hasTitle){return 2*PAD+LINE*(hourSize+(hasTitle?TITLE:0)+(showDate?DATE:0)+(showWeekday?WEEKDAY:0));}
    }
    private ClockLayout(){}
    static Plan plan(TextFit.Measurer m,String hour,float width,float height,boolean hasTitle){
        float byWidth=TextFit.fit(m,hour,width-2*PAD,HOUR_MAX);
        boolean date=true,weekday=true;
        float budget=budget(height,hasTitle,date,weekday);
        if(budget<HOUR_MIN){weekday=false;budget=budget(height,hasTitle,date,weekday);}
        if(budget<HOUR_MIN){date=false;budget=budget(height,hasTitle,date,weekday);}
        float size=budget<HOUR_MIN?byWidth:Math.min(byWidth,budget);
        return new Plan(size,date,weekday);
    }
    private static float budget(float height,boolean title,boolean date,boolean weekday){
        float lines=(title?TITLE:0)+(date?DATE:0)+(weekday?WEEKDAY:0);
        return (float)Math.floor((height-2*PAD-LINE*lines)/LINE);
    }
}
