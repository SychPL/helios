package pl.mateusz.helios;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private static final int INK=0xFFF1EFE6,MUTED=0xFF9EA59B,ACCENT=0xFFDCE5CB,CARD=0xFF262D28,WARN=0xFFE6BD7B;
    private final TextView brand,status;
    private final ImageView connection;
    private final Typeface sans,mono;
    private final Map<String,Tile> tiles=new LinkedHashMap<>();
    private DashboardSpec spec;
    private Actions actions;
    private String issue,message="",time="--:--",date="",musicInfo="—";
    private int measuredWidth,measuredHeight;

    public DashboardView(Context context){
        super(context);setBackgroundColor(0xFF1B201D);
        sans=Typeface.createFromAsset(context.getAssets(),"Geist.ttf");mono=Typeface.createFromAsset(context.getAssets(),"GeistMono.ttf");
        brand=text("HELIOS",MUTED,sans);brand.setLetterSpacing(.18f);
        status=text("",MUTED,sans);status.setMaxLines(1);status.setEllipsize(TextUtils.TruncateAt.END);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        connection=new ImageView(context);connection.setImageResource(R.drawable.ic_home_assistant);addView(connection);connected(false);
    }
    private TextView text(String value,int color,Typeface face){TextView t=new TextView(getContext());t.setText(value);t.setTextColor(color);t.setTypeface(face);t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);addView(t);return t;}
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
        box(brand,24,10,120,32,s,ox,oy);size(brand,17,s);
        box(status,150,10,560,32,s,ox,oy);size(status,16,s);
        box(connection,744,10,32,32,s,ox,oy);
        float cellW=(WIDTH-GAP*(DashboardSpec.COLUMNS+1))/(float)DashboardSpec.COLUMNS,cellH=(HEIGHT-BAR-GAP*(DashboardSpec.ROWS+1))/(float)DashboardSpec.ROWS;
        for(Tile tile:tiles.values()){
            DashboardSpec.Item i=tile.item;
            box(tile,GAP+(i.column-1)*(cellW+GAP),BAR+GAP+(i.row-1)*(cellH+GAP),cellW*i.width+GAP*(i.width-1),cellH*i.height+GAP*(i.height-1),s,ox,oy);
            tile.scale(s);
        }
    }

    public void setSpec(DashboardSpec spec,Actions actions){
        this.spec=spec;this.actions=actions;
        for(Tile t:tiles.values())removeView(t);tiles.clear();
        for(DashboardSpec.Item item:spec.items){Tile t=new Tile(item);tiles.put(item.id,t);addView(t);}
        arrange();
    }
    /** Text shown on the music tile: remote player and title while a remote player plays, otherwise a dash. */
    public void musicInfo(String text){musicInfo=text==null||text.isEmpty()?"—":text;for(Tile t:tiles.values())if(t.item.type.equals("music"))t.render(Collections.emptyMap(),true);}
    public void clock(String time,String date){this.time=time;this.date=date;for(Tile t:tiles.values())if(t.item.type.equals("clock"))t.clock();}
    public void setIssue(String issue){this.issue=issue;refreshStatus();}
    public void setMessage(String message){this.message=message==null?"":message;refreshStatus();}
    private void refreshStatus(){status.setText(issue!=null?issue:message);status.setTextColor(issue!=null?WARN:MUTED);}
    public void connected(boolean value){connection.setImageTintList(ColorStateList.valueOf(value?0xFF18BCF2:0xFF81877F));connection.setContentDescription(value?"Home Assistant: połączono":"Home Assistant: brak aktualnego połączenia");}
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
        final TextView title,value,detail;
        final ProgressBar spinner;
        Tile(DashboardSpec.Item item){
            super(DashboardView.this.getContext());this.item=item;
            GradientDrawable shape=new GradientDrawable();shape.setColor(CARD);shape.setCornerRadius(12);setBackground(shape);
            column=new LinearLayout(getContext());column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER_VERTICAL);addView(column,new LayoutParams(-1,-1));
            head=new LinearLayout(getContext());head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);column.addView(head);
            icon=new IconView(getContext());head.addView(icon);if(item.icon==null)icon.setVisibility(GONE);
            title=new TextView(getContext());title.setTextColor(MUTED);title.setTypeface(sans);title.setMaxLines(1);title.setEllipsize(TextUtils.TruncateAt.END);title.setIncludeFontPadding(false);head.addView(title);
            value=new TextView(getContext());value.setTextColor(INK);value.setTypeface(item.type.equals("clock")?mono:sans);value.setMaxLines(1);value.setEllipsize(TextUtils.TruncateAt.END);value.setIncludeFontPadding(false);column.addView(value);
            detail=new TextView(getContext());detail.setTextColor(MUTED);detail.setTypeface(sans);detail.setMaxLines(1);detail.setEllipsize(TextUtils.TruncateAt.END);detail.setIncludeFontPadding(false);column.addView(detail);
            spinner=new ProgressBar(getContext());spinner.setIndeterminate(true);spinner.setVisibility(GONE);addView(spinner,new LayoutParams(-2,-2,Gravity.TOP|Gravity.END));
            String label=label();title.setText(label);if(label.isEmpty())title.setVisibility(GONE);
            head.setVisibility(item.icon==null&&label.isEmpty()?GONE:VISIBLE);
            if(item.interactive()){setClickable(true);setFocusable(true);setOnClickListener(v->{if(actions!=null)actions.onTap(item);});}
            if(item.type.equals("clock"))clock();
        }
        String label(){
            if(item.title!=null)return item.title;
            if(item.type.equals("clock"))return "";
            if(item.type.equals("weather"))return "Pogoda";
            if(item.type.equals("music"))return "Muzyka";
            String name=item.entity.substring(item.entity.indexOf('.')+1).replace('_',' ');
            return name.substring(0,1).toUpperCase(new Locale("pl"))+name.substring(1);
        }
        void scale(float s){
            int pad=Math.round(14*s);column.setPadding(pad,pad,pad,pad);
            int iconPx=Math.round(26*s);icon.setLayoutParams(new LinearLayout.LayoutParams(iconPx,iconPx));
            LinearLayout.LayoutParams t=new LinearLayout.LayoutParams(0,-2,1);t.leftMargin=item.icon==null?0:Math.round(8*s);title.setLayoutParams(t);
            size(title,15,s);size(detail,15,s);
            float valuePx=item.type.equals("clock")?(item.height>=3?142:item.height==2?96:44):item.type.equals("weather")?40:26;
            size(value,valuePx,s);
            if(item.type.equals("clock"))value.setLetterSpacing(-.05f);
            int spin=Math.round(28*s);LayoutParams sp=new LayoutParams(spin,spin,Gravity.TOP|Gravity.END);sp.topMargin=sp.rightMargin=Math.round(10*s);spinner.setLayoutParams(sp);
        }
        void clock(){value.setText(time);detail.setText(date);detail.setVisibility(VISIBLE);setAlpha(1f);setContentDescription(time+", "+date);}
        void pending(boolean on){spinner.setVisibility(on?VISIBLE:GONE);setEnabled(!on);}
        void render(Map<String,EntityStates.Entity> states,boolean live){
            if(item.type.equals("clock"))return;
            if(item.type.equals("music")){value.setText(musicInfo);detail.setVisibility(GONE);if(item.icon!=null)icon.set(item.icon,MUTED);setAlpha(1f);setContentDescription("Muzyka: "+musicInfo);return;}
            EntityStates.Entity e=item.entity==null?null:states.get(item.entity);
            boolean known=e!=null&&e.known();
            String text,extra="";int tint=MUTED;
            switch(item.type){
                case "weather":{
                    String temperature=null,unit="°C";
                    if(item.temperatureEntity!=null){EntityStates.Entity t=states.get(item.temperatureEntity);if(t!=null&&t.known())temperature=t.state;}
                    else if(known){temperature=e.attribute("temperature");if(e.attribute("temperature_unit")!=null)unit=e.attribute("temperature_unit");}
                    text=number(temperature,unit);
                    if(known){extra=WeatherLabels.polish(e.state);String wind=e.attribute("wind_speed");if(wind!=null)extra+=" · Wiatr "+number(wind,e.attribute("wind_speed_unit")==null?"":" "+e.attribute("wind_speed_unit"));}
                    else extra="Brak danych";
                    break;}
                case "entity":{
                    String v=!known?null:item.attribute==null?e.state:e.attribute(item.attribute);
                    text=v==null?"Brak danych":v;break;}
                case "light":
                    text=!known?"Brak danych":e.state.equals("on")?"Włączone":e.state.equals("off")?"Wyłączone":e.state;
                    tint=known&&e.state.equals("on")?ACCENT:MUTED;break;
                default:
                    text=!known?"Brak danych":cover(e.state);
                    if(known&&e.attribute("current_position")!=null)extra="Otwarcie "+number(e.attribute("current_position"),"%");
                    tint=known&&!e.state.equals("closed")?WARN:MUTED;break;
            }
            value.setText(text);detail.setText(extra);detail.setVisibility(extra.isEmpty()?GONE:VISIBLE);
            if(item.icon!=null)icon.set(item.icon,tint);
            setAlpha(live?1f:.55f);
            setContentDescription(label()+": "+text+(extra.isEmpty()?"":", "+extra)+(live?"":", dane nieaktualne"));
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
