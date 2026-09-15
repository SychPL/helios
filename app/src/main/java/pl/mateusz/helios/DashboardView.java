package pl.mateusz.helios;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Fixed top bar plus a 4x3 grid of large tiles rendered from DashboardSpec; nothing here comes from YAML except tile content. */
public final class DashboardView extends FrameLayout {
    public interface Actions { void onTap(DashboardSpec.Item item); }
    static final int WIDTH=800,HEIGHT=480,BAR=52,GAP=8;
    private final TextView brand,status;
    private final ImageView connection;
    private final Typeface sans,mono;
    private final Paint monoPaint=new Paint(Paint.ANTI_ALIAS_FLAG),sansPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String,Tile> tiles=new LinkedHashMap<>();
    private final MusicOverlay overlay;
    private DashboardSpec spec;
    private Actions actions;
    private String issue,message="",time="--:--",weekday="",date="",musicInfo="—";
    private boolean haConnected;
    private int measuredWidth,measuredHeight;
    private float scale=1;

    public DashboardView(Context context){
        super(context);
        sans=Typeface.createFromAsset(context.getAssets(),"Geist.ttf");mono=Typeface.createFromAsset(context.getAssets(),"GeistMono.ttf");
        monoPaint.setTypeface(mono);sansPaint.setTypeface(sans);
        brand=text("HELIOS",sans);brand.setLetterSpacing(.18f);
        status=text("",sans);status.setMaxLines(1);status.setEllipsize(TextUtils.TruncateAt.END);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        connection=new ImageView(context);connection.setImageResource(R.drawable.ic_home_assistant);addView(connection);
        overlay=new MusicOverlay(context,sans);addView(overlay);
        applyTheme();connected(false);
    }
    public MusicOverlay musicOverlay(){return overlay;}
    /** Recolours everything in place: no grid rebuild, no overlay geometry change (SPEC 0.8b pkt 6). */
    public void applyTheme(){
        Theme t=Theme.current();
        setBackgroundColor(t.background);brand.setTextColor(t.muted);refreshStatus();connected(haConnected);
        for(Tile tile:tiles.values())tile.theme();
        overlay.applyTheme();
    }
    private TextView text(String value,Typeface face){TextView t=new TextView(getContext());t.setText(value);t.setTypeface(face);t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);addView(t);return t;}
    private static void box(View v,float x,float y,float w,float h,float s,float ox,float oy){LayoutParams p=new LayoutParams(Math.round(w*s),Math.round(h*s));p.leftMargin=Math.round(ox+x*s);p.topMargin=Math.round(oy+y*s);v.setLayoutParams(p);}
    private static void size(TextView v,float px,float s){v.setTextSize(TypedValue.COMPLEX_UNIT_PX,px*s);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int w=MeasureSpec.getSize(widthSpec),h=MeasureSpec.getSize(heightSpec);
        if(w!=measuredWidth||h!=measuredHeight){measuredWidth=w;measuredHeight=h;arrange();}
        super.onMeasure(widthSpec,heightSpec);
    }
    private void arrange(){
        if(measuredWidth==0)return;
        float s=Math.min(measuredWidth/(float)WIDTH,measuredHeight/(float)HEIGHT),ox=(measuredWidth-WIDTH*s)/2,oy=(measuredHeight-HEIGHT*s)/2;
        scale=s;
        box(brand,24,10,120,32,s,ox,oy);size(brand,17,s);
        box(status,150,10,560,32,s,ox,oy);size(status,16,s);
        box(connection,744,10,32,32,s,ox,oy);
        float cellW=(WIDTH-GAP*(DashboardSpec.COLUMNS+1))/(float)DashboardSpec.COLUMNS,cellH=(HEIGHT-BAR-GAP*(DashboardSpec.ROWS+1))/(float)DashboardSpec.ROWS;
        for(Tile tile:tiles.values()){
            DashboardSpec.Item i=tile.item;
            float w=cellW*i.width+GAP*(i.width-1),h=cellH*i.height+GAP*(i.height-1);
            box(tile,GAP+(i.column-1)*(cellW+GAP),BAR+GAP+(i.row-1)*(cellH+GAP),w,h,s,ox,oy);
            tile.scale(s,w,h);
        }
        overlay.arrange(s,ox,oy);
    }

    public void setSpec(DashboardSpec spec,Actions actions){
        this.spec=spec;this.actions=actions;
        for(Tile t:tiles.values())removeView(t);tiles.clear();
        for(DashboardSpec.Item item:spec.items){Tile t=new Tile(item);tiles.put(item.id,t);addView(t,indexOfChild(overlay));}
        overlay.bringToFront();arrange();
    }
    /** Text shown on the music tile: remote player and title while a remote player plays, otherwise a dash. */
    public void musicInfo(String text){musicInfo=text==null||text.isEmpty()?"—":text;for(Tile t:tiles.values())if(t.item.type.equals("music"))t.render(Collections.emptyMap(),true);}
    public void clock(String time,String weekday,String date){
        boolean refit=!this.time.equals(time)&&this.time.length()!=time.length();
        this.time=time;this.weekday=weekday;this.date=date;
        for(Tile t:tiles.values())if(t.item.type.equals("clock")){if(refit)t.scale(scale,t.unitW,t.unitH);t.clock();}
    }
    public void setIssue(String issue){this.issue=issue;refreshStatus();}
    public void setMessage(String message){this.message=message==null?"":message;refreshStatus();}
    private void refreshStatus(){Theme t=Theme.current();status.setText(issue!=null?issue:message);status.setTextColor(issue!=null?t.accent:t.muted);}
    public void connected(boolean value){haConnected=value;connection.setImageTintList(ColorStateList.valueOf(value?Theme.HA_CONNECTED:Theme.current().muted));connection.setContentDescription(value?"Home Assistant: połączono":"Home Assistant: brak aktualnego połączenia");}
    public void onBrandHold(Runnable action){brand.setContentDescription("Helios. Przytrzymaj, aby otworzyć menu.");brand.setOnLongClickListener(v->{action.run();return true;});}
    public void pending(String id,boolean on){Tile t=tiles.get(id);if(t!=null)t.pending(on);}
    /** visibility holds the last decided value per conditional item; a conditional item without an entry stays hidden. */
    public void render(Map<String,EntityStates.Entity> states,Map<String,Boolean> visibility,boolean live){
        for(Tile t:tiles.values()){
            boolean shown=!t.item.conditional()||Boolean.TRUE.equals(visibility.get(t.item.id));
            t.setVisibility(shown?VISIBLE:GONE);
            if(shown)t.render(states,live);
        }
    }

    private final class Tile extends FrameLayout {
        final DashboardSpec.Item item;
        final LinearLayout column,head;
        final IconView icon;
        final TextView title,value,detail,detail2;
        final ProgressBar spinner;
        final boolean clock;
        float unitW,unitH;
        boolean live=true;int iconTint;
        Tile(DashboardSpec.Item item){
            super(DashboardView.this.getContext());this.item=item;clock=item.type.equals("clock");
            column=new LinearLayout(getContext());column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER_VERTICAL);addView(column,new LayoutParams(-1,-1));
            head=new LinearLayout(getContext());head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);column.addView(head);
            icon=new IconView(getContext());head.addView(icon);if(item.icon==null)icon.setVisibility(GONE);
            title=line(sans,1);head.addView(title);
            value=line(clock?mono:sans,clock?1:2);column.addView(value);
            detail=line(sans,1);column.addView(detail);
            detail2=line(sans,1);column.addView(detail2);detail2.setVisibility(GONE);
            spinner=new ProgressBar(getContext());spinner.setIndeterminate(true);spinner.setVisibility(GONE);addView(spinner,new LayoutParams(-2,-2,Gravity.TOP|Gravity.END));
            String label=label();title.setText(label);if(label.isEmpty())title.setVisibility(GONE);
            head.setVisibility(item.icon==null&&label.isEmpty()?GONE:VISIBLE);
            if(item.interactive()){setClickable(true);setFocusable(true);setOnClickListener(v->{if(actions!=null)actions.onTap(item);});}
            theme();
            if(clock)clock();
        }
        private TextView line(Typeface face,int lines){TextView t=new TextView(getContext());t.setTypeface(face);t.setMaxLines(lines);t.setEllipsize(TextUtils.TruncateAt.END);t.setIncludeFontPadding(false);return t;}
        String label(){
            if(item.title!=null)return item.title;
            if(clock)return "";
            if(item.type.equals("weather"))return "Pogoda";
            if(item.type.equals("music"))return "Muzyka";
            String name=item.entity.substring(item.entity.indexOf('.')+1).replace('_',' ');
            return name.substring(0,1).toUpperCase(new Locale("pl"))+name.substring(1);
        }
        void theme(){
            Theme t=Theme.current();
            setBackground(Theme.card(t.surface,Theme.RADIUS*scale));
            title.setTextColor(t.muted);detail.setTextColor(t.muted);detail2.setTextColor(t.muted);
            value.setTextColor(live?t.text:t.muted);
            if(item.icon!=null)icon.set(item.icon,iconTint==0?t.muted:iconTint);
        }
        /** Sizes in 800x480 units; w/h are the tile's own size in those units, so the clock fits its hour by measurement. */
        void scale(float s,float w,float h){
            unitW=w;unitH=h;
            int pad=Math.round((clock?ClockLayout.PAD:14)*s);column.setPadding(pad,pad,pad,pad);
            int iconPx=Math.round(26*s);icon.setLayoutParams(new LinearLayout.LayoutParams(iconPx,iconPx));
            LinearLayout.LayoutParams t=new LinearLayout.LayoutParams(0,-2,1);t.leftMargin=item.icon==null?0:Math.round(8*s);title.setLayoutParams(t);
            size(title,clock?ClockLayout.TITLE:17,s);
            if(clock){
                ClockLayout.Plan plan=ClockLayout.plan((text,size)->{monoPaint.setTextSize(size);monoPaint.setLetterSpacing(-.05f);return monoPaint.measureText(text);},time,w,h,item.title!=null);
                size(value,plan.hourSize,s);value.setLetterSpacing(-.05f);
                size(detail,ClockLayout.DATE,s);size(detail2,ClockLayout.WEEKDAY,s);
                detail.setVisibility(plan.showDate?VISIBLE:GONE);detail2.setVisibility(plan.showWeekday?VISIBLE:GONE);
            }else if(item.type.equals("weather")){size(value,44,s);size(detail,18,s);}
            else{
                float inner=w-28;
                float fit=TextFit.size((text,size)->{sansPaint.setTextSize(size);return sansPaint.measureText(text);},value.getText().toString(),inner,24,28);
                size(value,fit,s);size(detail,17,s);
            }
            setBackground(Theme.card(Theme.current().surface,Theme.RADIUS*s));
            int spin=Math.round(28*s);LayoutParams sp=new LayoutParams(spin,spin,Gravity.TOP|Gravity.END);sp.topMargin=sp.rightMargin=Math.round(10*s);spinner.setLayoutParams(sp);
        }
        void clock(){value.setText(time);detail.setText(date);detail2.setText(weekday);setContentDescription(time+", "+weekday+", "+date);}
        void pending(boolean on){spinner.setVisibility(on?VISIBLE:GONE);setEnabled(!on);}
        void render(Map<String,EntityStates.Entity> states,boolean live){
            if(clock)return;
            Theme th=Theme.current();
            if(item.type.equals("music")){value.setText(musicInfo);detail.setVisibility(GONE);iconTint=th.muted;this.live=true;theme();setContentDescription("Muzyka: "+musicInfo);return;}
            EntityStates.Entity e=item.entity==null?null:states.get(item.entity);
            boolean known=e!=null&&e.known();
            String text,extra="";int tint=th.muted;
            switch(item.type){
                case "weather":{
                    String temperature=null,unit="";
                    if(item.temperatureEntity!=null){EntityStates.Entity t=states.get(item.temperatureEntity);if(t!=null&&t.known()){temperature=t.state;if(t.attribute("unit_of_measurement")!=null)unit=t.attribute("unit_of_measurement");}}
                    else if(known){temperature=e.attribute("temperature");if(e.attribute("temperature_unit")!=null)unit=e.attribute("temperature_unit");}
                    text=number(temperature,unit); // no unit in HA means no unit on screen (SPEC 0.8a pkt 3.3)
                    if(known){extra=WeatherLabels.polish(e.state);String wind=e.attribute("wind_speed");if(wind!=null)extra+=" · Wiatr "+number(wind,e.attribute("wind_speed_unit")==null?"":" "+e.attribute("wind_speed_unit"));}
                    else extra="Brak danych";
                    break;}
                case "entity":{
                    String v=!known?null:item.attribute==null?e.state:e.attribute(item.attribute);
                    text=v==null?"Brak danych":v;break;}
                case "light":
                    text=!known?"Brak danych":e.state.equals("on")?"Włączone":e.state.equals("off")?"Wyłączone":e.state;
                    tint=known&&e.state.equals("on")?th.accent:th.muted;break;
                default:
                    text=!known?"Brak danych":cover(e.state);
                    if(known&&e.attribute("current_position")!=null)extra="Otwarcie "+number(e.attribute("current_position"),"%");
                    tint=known&&!e.state.equals("closed")?th.accent:th.muted;break;
            }
            String label=label();
            boolean changed=!text.equals(value.getText().toString());
            value.setText(text);detail.setText(extra);detail.setVisibility(extra.isEmpty()?GONE:VISIBLE);
            title.setText(live?label:label+" (offline)"); // stale values are muted text plus a word, never a faded tile (contrast)
            iconTint=tint;this.live=live;theme();
            if(changed&&!item.type.equals("weather")&&unitW>0)scale(scale,unitW,unitH);
            value.setContentDescription(text);
            setContentDescription(label+": "+text+(extra.isEmpty()?"":", "+extra)+(live?"":", dane nieaktualne"));
        }
        private String number(String raw,String unit){
            if(raw==null)return "—";
            try{return String.format(new Locale("pl"),"%.0f%s",Double.parseDouble(raw),unit);}catch(NumberFormatException e){return raw+unit;}
        }
        private String cover(String state){
            switch(state){case "open":return "Otwarta";case "closed":return "Zamknięta";case "opening":return "Otwieranie…";case "closing":return "Zamykanie…";default:return state;}
        }
    }
}
