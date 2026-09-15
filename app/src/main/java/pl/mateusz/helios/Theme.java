package pl.mateusz.helios;

/**
 * Colour presets from SPEC 0.8a pkt 3.1 plus the static contrast math the tests gate on. Pure Java; Android helpers live at the bottom
 * and are only ever called from the UI thread.
 */
final class Theme {
    static final Theme WARM_GRAPHITE=new Theme("warm_graphite",0xFF181C24,0xFF262D38,0xFFF7F4EE,0xFFC1C7D0,0xFFEDBE83);
    static final Theme NIGHT_BLUE=new Theme("night_blue",0xFF151D2B,0xFF243247,0xFFF7F4EE,0xFFC1C7D0,0xFF9CCFE0);
    static final int RADIUS=18;
    static final int HA_CONNECTED=0xFF18BCF2;
    final String id;final int background,surface,text,muted,accent,raised;
    private static volatile Theme current=WARM_GRAPHITE;
    private Theme(String id,int background,int surface,int text,int muted,int accent){
        this.id=id;this.background=background;this.surface=surface;this.text=text;this.muted=muted;this.accent=accent;
        raised=composite(text,.10f,surface); // secondary buttons and the cover placeholder: a touch lighter than the card
    }
    static Theme current(){return current;}
    static void set(Theme theme){current=theme;}
    static Theme byId(String id){return NIGHT_BLUE.id.equals(id)?NIGHT_BLUE:WARM_GRAPHITE.id.equals(id)?WARM_GRAPHITE:null;}

    /** WCAG 2 relative luminance of an opaque ARGB colour. */
    static double luminance(int argb){
        return .2126*channel((argb>>16)&255)+.7152*channel((argb>>8)&255)+.0722*channel(argb&255);
    }
    private static double channel(int v){double c=v/255.0;return c<=.03928?c/12.92:Math.pow((c+.055)/1.055,2.4);}
    /** WCAG contrast ratio, order independent. */
    static double contrast(int a,int b){
        double la=luminance(a)+.05,lb=luminance(b)+.05;return la>lb?la/lb:lb/la;
    }
    /** Opaque result of drawing `over` with `alpha` on top of the opaque `under`. */
    static int composite(int over,float alpha,int under){
        int r=Math.round(((over>>16)&255)*alpha+((under>>16)&255)*(1-alpha)),g=Math.round(((over>>8)&255)*alpha+((under>>8)&255)*(1-alpha)),b=Math.round((over&255)*alpha+(under&255)*(1-alpha));
        return 0xFF000000|(r<<16)|(g<<8)|b;
    }
    /** Icon colour that contrasts with a filled button of the given colour: the theme background (dark) or text (light), whichever wins. */
    int onColor(int fill){return contrast(background,fill)>=contrast(text,fill)?background:text;}

    // --- Android helpers (UI thread only) ---
    static int dp(android.content.Context context,float dp){return Math.round(dp*context.getResources().getDisplayMetrics().density);}
    /** Dialog body: palette surface, rounded corners, no system title. */
    static android.widget.LinearLayout dialogColumn(android.content.Context context,int padDp){
        android.widget.LinearLayout column=new android.widget.LinearLayout(context);column.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad=dp(context,padDp);column.setPadding(pad,pad,pad,pad);column.setBackground(card(current.surface,dp(context,RADIUS)));return column;
    }
    static android.widget.TextView label(android.content.Context context,String text,float sp,boolean muted){
        android.widget.TextView t=new android.widget.TextView(context);t.setText(text);t.setTextSize(sp);t.setTextColor(muted?current.muted:current.text);return t;
    }
    static void tint(android.widget.SeekBar bar){
        android.content.res.ColorStateList accent=android.content.res.ColorStateList.valueOf(current.accent);bar.setProgressTintList(accent);bar.setThumbTintList(accent);
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(current.raised));
    }
    static android.graphics.drawable.GradientDrawable card(int color,float radiusPx){
        android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(radiusPx);return d;
    }
    /** Rounded button in the palette; primary = accent fill with dark text, otherwise raised surface with light text. */
    static android.widget.Button button(android.content.Context context,String label,boolean primary,float textPx,float radiusPx){
        Theme t=current;
        android.widget.Button b=new android.widget.Button(context);b.setText(label);b.setAllCaps(false);b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,textPx);
        b.setTextColor(primary?t.onColor(t.accent):t.text);b.setBackground(card(primary?t.accent:t.raised,radiusPx));b.setStateListAnimator(null);b.setPadding(0,0,0,0);
        return b;
    }
}
