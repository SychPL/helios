package pl.mateusz.helios;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** A quiet, landscape clock layout made from accessible native controls. */
public final class DashboardView extends FrameLayout {
    public final TextView time,date,connection,temperature,condition,weatherDetail,freshness,message;
    public final Button talk,menu;
    public final Switch wake;
    private final TextView brand;
    private final View divider,weatherDivider;
    private final Typeface sans,mono;
    private int measuredWidth,measuredHeight;
    private static final int INK=0xFFF1EFE6,MUTED=0xFF9EA59B,ACCENT=0xFFDCE5CB;
    public DashboardView(Context context){
        super(context);setBackgroundColor(0xFF1B201D);
        sans=Typeface.createFromAsset(context.getAssets(),"Geist.ttf");mono=Typeface.createFromAsset(context.getAssets(),"GeistMono.ttf");
        brand=text("HELIOS",MUTED);brand.setLetterSpacing(.18f);
        wake=new Switch(context);wake.setText("Okay Nabu  ");wake.setTextColor(INK);wake.setTypeface(sans);wake.setContentDescription("Nasłuch Okay Nabu");addView(wake);
        connection=text("Łączenie z HA…",MUTED);connection.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        menu=new Button(context);menu.setText("☰ Menu");menu.setAllCaps(false);menu.setTypeface(sans);menu.setTextColor(INK);menu.setBackgroundColor(0xFF30392F);menu.setPadding(0,0,0,0);menu.setContentDescription("Otwórz menu Heliosa");addView(menu);
        time=text("--:--",INK);time.setTypeface(mono);time.setLetterSpacing(-.065f);time.setIncludeFontPadding(false);
        date=text("",INK);
        temperature=text("—",ACCENT);condition=text("Pogoda z domu",INK);
        weatherDetail=text("",MUTED);freshness=text("Czekam na odczyt",MUTED);
        message=text("Naciśnij, aby porozmawiać.\nHome Assistant Cloud",MUTED);message.setMaxLines(3);message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        divider=new View(context);divider.setBackgroundColor(0xFF394038);addView(divider);
        weatherDivider=new View(context);weatherDivider.setBackgroundColor(0xFF394038);addView(weatherDivider);
        talk=new Button(context);talk.setText("Porozmawiaj");talk.setAllCaps(false);talk.setTypeface(sans);talk.setTextColor(0xFF20291E);talk.setElevation(0);talk.setStateListAnimator(null);talk.setPadding(12,0,12,0);addView(talk);
    }
    private TextView text(String value,int color){TextView t=new TextView(getContext());t.setText(value);t.setTextColor(color);t.setTypeface(sans);t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);addView(t);return t;}
    private void box(View v,float x,float y,float w,float h,float s,float ox,float oy){LayoutParams p=new LayoutParams(Math.round(w*s),Math.round(h*s));p.leftMargin=Math.round(ox+x*s);p.topMargin=Math.round(oy+y*s);v.setLayoutParams(p);}
    private void size(TextView v,float px,float s){v.setTextSize(TypedValue.COMPLEX_UNIT_PX,px*s);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int w=MeasureSpec.getSize(widthSpec),h=MeasureSpec.getSize(heightSpec);
        if(w!=measuredWidth||h!=measuredHeight){measuredWidth=w;measuredHeight=h;arrange(w,h);}
        super.onMeasure(widthSpec,heightSpec);
    }
    private void arrange(int w,int h){
        float s=Math.min(w/800f,h/480f),ox=(w-800*s)/2,oy=(h-480*s)/2;
        box(brand,44,24,120,40,s,ox,oy);size(brand,17,s);
        box(wake,178,24,220,40,s,ox,oy);size(wake,16,s);
        box(connection,408,24,232,40,s,ox,oy);size(connection,14,s);
        box(menu,654,20,102,48,s,ox,oy);size(menu,16,s);
        box(time,34,97,490,148,s,ox,oy);size(time,142,s);
        box(date,46,258,460,40,s,ox,oy);size(date,22,s);
        box(weatherDivider,532,114,1,177,s,ox,oy);
        box(temperature,568,102,195,79,s,ox,oy);size(temperature,62,s);
        box(condition,572,191,195,30,s,ox,oy);size(condition,19,s);
        box(weatherDetail,572,227,195,26,s,ox,oy);size(weatherDetail,13,s);
        box(freshness,572,262,195,22,s,ox,oy);size(freshness,12,s);
        box(divider,44,328,712,1,s,ox,oy);
        box(message,44,353,466,77,s,ox,oy);size(message,18,s);
        box(talk,540,354,216,74,s,ox,oy);size(talk,20,s);
        GradientDrawable shape=new GradientDrawable();shape.setColor(ACCENT);shape.setCornerRadius(10*s);
        talk.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55778569),shape,null));
    }
    public void connected(boolean value){connection.setText(value?"HA Cloud · połączono":"HA · brak połączenia");connection.setTextColor(value?0xFFBBCBA9:0xFFE0BAA3);}
    public void onBrandHold(Runnable action){
        brand.setContentDescription("Helios. Przytrzymaj, aby otworzyć menu.");
        brand.setOnLongClickListener(v->{action.run();return true;});
    }
}
