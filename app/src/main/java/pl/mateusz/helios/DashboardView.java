package pl.mateusz.helios;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Fixed top bar plus a 4x3 grid of large tiles rendered from DashboardSpec; nothing here comes from YAML except tile content. */
public final class DashboardView extends FrameLayout {
    public interface Actions { void onTap(DashboardSpec.Item item); }
    static final int WIDTH=800,HEIGHT=480,BAR=52,GAP=8;
    private final TextView brand,status;
    private final LinearLayout nightLayer;private final TextView nightTime,nightDate;
    private final ImageView connection,backdrop;
    private final View dimLayer,bar;
    private boolean photo;
    private final Typeface sans,mono;
    private final Paint monoPaint=new Paint(Paint.ANTI_ALIAS_FLAG),sansPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String,Tile> tiles=new LinkedHashMap<>();
    /** SPEC 0.20: `alerts` tiles by id; their stand-in cards live in `tiles` under the same id. */
    private final Map<String,AlertsTile> alertTiles=new LinkedHashMap<>();
    /** A finger is on the glass; a card swapped under it voids the tap that finger would end with (SPEC 0.20 pkt 3). */
    private boolean fingerDown,voidTap;
    /** The alerts tiles (or their stand-ins) under the finger when it went down. */
    private final Set<String> downOn=new HashSet<>();
    private final Runnable emptyCheck=this::recheckEmpty;
    private void recheckEmpty(){if(lastStates!=null)render(lastStates,lastVisibility,lastLive);}
    private final MusicOverlay overlay;
    private final PageDots dots;
    /** The page on screen (SPEC 0.18); kept across a new document while that document still has it. */
    private int page;
    private float downX,downY;
    /** Two minutes without a touch bring the first page back. */
    static final long HOME_AFTER_MS=120_000;
    private final Runnable home=this::goHome;
    /** Not while a dialog has the screen: someone is still using the panel, so the timer starts over. */
    private void goHome(){if(!hasWindowFocus()){postDelayed(home,HOME_AFTER_MS);return;}showPage(0);}
    private final Runnable hideDots=this::fadeDots;
    private void fadeDots(){dots.animate().alpha(0f).setDuration(300).start();}
    private DashboardSpec spec;
    private Actions actions;
    private String issue,message="",time="--:--",weekday="",date="",musicInfo="—";
    private boolean haConnected;
    private Map<String,EntityStates.Entity> lastStates;private Map<String,Boolean> lastVisibility;private boolean lastLive;
    private int measuredWidth,measuredHeight;
    private float scale=1;

    public DashboardView(Context context){
        super(context);
        sans=Typeface.createFromAsset(context.getAssets(),"Geist.ttf");mono=Typeface.createFromAsset(context.getAssets(),"GeistMono.ttf");
        monoPaint.setTypeface(mono);sansPaint.setTypeface(sans);
        backdrop=new ImageView(context);backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);backdrop.setVisibility(GONE);addView(backdrop,new LayoutParams(-1,-1));
        dimLayer=new View(context);dimLayer.setBackgroundColor(0xFF000000);dimLayer.setVisibility(GONE);addView(dimLayer,new LayoutParams(-1,-1));
        bar=new View(context);addView(bar);
        brand=text("HELIOS",sans);brand.setLetterSpacing(.18f);
        status=text("",sans);status.setMaxLines(1);status.setEllipsize(TextUtils.TruncateAt.END);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        connection=new ImageView(context);connection.setImageResource(R.drawable.ic_home_assistant);addView(connection);
        dots=new PageDots(context);addView(dots);
        overlay=new MusicOverlay(context,sans,mono);addView(overlay);
        // Night clock (SPEC 0.13): a lid over everything, not a separate screen - the panel keeps its state
        // underneath and comes back the moment the policy says so.
        nightLayer=new LinearLayout(context);nightLayer.setOrientation(LinearLayout.VERTICAL);
        nightLayer.setGravity(Gravity.CENTER);nightLayer.setBackgroundColor(0xFF000000);nightLayer.setVisibility(GONE);
        nightTime=new TextView(context);nightTime.setTypeface(mono);nightTime.setTextColor(NIGHT_TEXT);nightTime.setIncludeFontPadding(false);
        nightDate=new TextView(context);nightDate.setTypeface(sans);nightDate.setTextColor(NIGHT_MUTED);nightDate.setIncludeFontPadding(false);
        // the text views span the width and centre their own text: gravity on the column alone leaves the
        // digits pinned to the left once a view ends up as wide as the screen
        nightTime.setGravity(Gravity.CENTER);nightDate.setGravity(Gravity.CENTER);
        nightLayer.addView(nightTime,new LinearLayout.LayoutParams(-1,-2));
        nightLayer.addView(nightDate,new LinearLayout.LayoutParams(-1,-2));
        addView(nightLayer,new LayoutParams(-1,-1));
        applyTheme();connected(false);
    }
    /** Grey rather than white: readable across a dark bedroom without lighting it up. */
    static final int NIGHT_TEXT=0xFF9A9A9A,NIGHT_MUTED=0xFF5A5A5A;
    /** Puts the night clock over the panel, or takes it away. Idempotent: the caller may say the same twice. */
    public void night(boolean on){
        if(on==(nightLayer.getVisibility()==VISIBLE))return;
        if(on){nightTime.setText(time);nightDate.setText(date);nightLayer.bringToFront();nightLayer.setVisibility(VISIBLE);}
        else nightLayer.setVisibility(GONE);
    }
    public boolean nightVisible(){return nightLayer.getVisibility()==VISIBLE;}
    public MusicOverlay musicOverlay(){return overlay;}
    /** Background photo already cropped to 800x480 (or null for the theme colour) with its black dim in percent; layout untouched (SPEC 0.8b pkt 6). */
    public void setBackdrop(android.graphics.Bitmap bitmap,int dimPercent){
        photo=bitmap!=null;
        backdrop.setImageBitmap(bitmap);backdrop.setVisibility(photo?VISIBLE:GONE);
        dimLayer.setAlpha(dimPercent/100f);dimLayer.setVisibility(photo?VISIBLE:GONE);
        for(Tile tile:tiles.values())tile.theme();
        for(AlertsTile tile:alertTiles.values())tile.theme();
    }
    /** Recolours everything in place: no grid rebuild, no overlay geometry change (SPEC 0.8b pkt 6). */
    public void applyTheme(){
        Theme t=Theme.current();
        setBackgroundColor(t.background);bar.setBackgroundColor(t.background);brand.setTextColor(t.muted);dots.invalidate();refreshStatus();connected(haConnected);
        for(Tile tile:tiles.values())tile.theme();
        for(AlertsTile tile:alertTiles.values())tile.theme();
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
        box(bar,0,0,WIDTH,BAR,s,ox,oy);
        box(brand,24,10,120,32,s,ox,oy);size(brand,17,s);
        box(status,150,10,560,32,s,ox,oy);size(status,16,s);
        box(connection,744,10,32,32,s,ox,oy);
        box(dots,300,44,200,6,s,ox,oy); // the strip between the bar's text and the first row: above the cards, over nothing
        size(nightTime,190,s);size(nightDate,22,s); // 190 of 480 units: the hour fills the screen without touching the edges
        float cellW=(WIDTH-GAP*(DashboardSpec.COLUMNS+1))/(float)DashboardSpec.COLUMNS,cellH=(HEIGHT-BAR-GAP*(DashboardSpec.ROWS+1))/(float)DashboardSpec.ROWS;
        for(Tile tile:tiles.values()){
            DashboardSpec.Item i=tile.item;
            float w=cellW*i.width+GAP*(i.width-1),h=cellH*i.height+GAP*(i.height-1);
            box(tile,GAP+(i.column-1)*(cellW+GAP),BAR+GAP+(i.row-1)*(cellH+GAP),w,h,s,ox,oy);
            tile.scale(s,w,h);
        }
        for(AlertsTile tile:alertTiles.values()){
            DashboardSpec.Item i=tile.item;
            float w=cellW*i.width+GAP*(i.width-1),h=cellH*i.height+GAP*(i.height-1);
            box(tile,GAP+(i.column-1)*(cellW+GAP),BAR+GAP+(i.row-1)*(cellH+GAP),w,h,s,ox,oy);
            tile.scale(s,w,h);
        }
        overlay.arrange(s,ox,oy);
    }

    public void setSpec(DashboardSpec spec,Actions actions){
        this.spec=spec;this.actions=actions;
        if(page>=spec.pages.size())page=0;
        buildPage();
    }
    private void buildPage(){
        for(Tile t:tiles.values())removeView(t);tiles.clear();
        for(AlertsTile t:alertTiles.values())removeView(t);alertTiles.clear();removeCallbacks(emptyCheck);
        for(DashboardSpec.Item item:spec.pages.get(page).items){
            if(item.type.equals("alerts")){
                AlertsTile a=new AlertsTile(item);alertTiles.put(item.id,a);addView(a,indexOfChild(overlay));
                if(item.empty!=null){Tile t=new Tile(item.empty);t.substitute=true;t.setVisibility(GONE);tiles.put(item.id,t);addView(t,indexOfChild(overlay));}
                continue;
            }
            Tile t=new Tile(item);tiles.put(item.id,t);addView(t,indexOfChild(overlay));
        }
        dots.set(spec.pages.size(),page);
        overlay.bringToFront();nightLayer.bringToFront();arrange();
        for(String id:pendingIds){Tile t=tiles.get(id);if(t!=null)t.pending(true);}
    }
    /** Swaps the grid to another page and fills it from the last snapshot at once - no wait for the next HA delta. */
    void showPage(int n){
        if(spec==null||n==page||n<0||n>=spec.pages.size())return;
        page=n;buildPage();
        removeCallbacks(home);if(page!=0)postDelayed(home,HOME_AFTER_MS); // the touch that turned the page ran before it turned
        for(Tile t:tiles.values())if(t.def.feed==CardDefinition.Feed.CLOCK)t.clock();
        if(lastStates!=null)render(lastStates,lastVisibility,lastLive);
    }
    private boolean swipeable(){return spec!=null&&spec.pages.size()>1&&!overlay.isOpen()&&!nightVisible();}
    /** Every touch shows the dots (only with more than one page) and restarts the way home; they fade shortly after the finger lifts. */
    @Override public boolean dispatchTouchEvent(MotionEvent e){
        int a=e.getActionMasked();
        if(a==MotionEvent.ACTION_DOWN){
            fingerDown=true;voidTap=false;downOn.clear();
            for(AlertsTile t:alertTiles.values()){LayoutParams p=(LayoutParams)t.getLayoutParams();if(p!=null&&e.getX()>=p.leftMargin&&e.getX()<p.leftMargin+p.width&&e.getY()>=p.topMargin&&e.getY()<p.topMargin+p.height)downOn.add(t.item.id);}
            removeCallbacks(hideDots);removeCallbacks(home); // both wait while the finger is down
            if(swipeable()){dots.animate().cancel();dots.animate().alpha(1f).setDuration(120).start();}
        }else if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){
            fingerDown=false;
            removeCallbacks(hideDots);removeCallbacks(home);postDelayed(hideDots,1500);
            if(page!=0)postDelayed(home,HOME_AFTER_MS);
        }
        return super.dispatchTouchEvent(e);
    }
    /** A dialog or the night clock took the screen mid-gesture: the dots go, and the way home restarts from now. */
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus){removeCallbacks(hideDots);fadeDots();removeCallbacks(home);}else touched();} // a dialog pauses the way home; closing it starts the full two minutes
    /** A touch the dashboard never sees (the one that wakes the night clock) still counts as someone using the panel. */
    public void touched(){removeCallbacks(home);if(page!=0)postDelayed(home,HOME_AFTER_MS);}
    /** A clearly sideways move is taken from the tile under the finger, which then gets CANCEL instead of a tap. */
    @Override public boolean onInterceptTouchEvent(MotionEvent e){
        if(!swipeable())return false;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:downX=e.getX();downY=e.getY();return false;
            case MotionEvent.ACTION_MOVE:return PageSwipe.sideways((e.getX()-downX)/scale,(e.getY()-downY)/scale);
            default:return false;
        }
    }
    /** Also the gesture that starts on empty grid, where no tile claims the DOWN. */
    @Override public boolean onTouchEvent(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:downX=e.getX();downY=e.getY();return swipeable();
            case MotionEvent.ACTION_MOVE:return true;
            case MotionEvent.ACTION_UP:
                if(swipeable())showPage(PageSwipe.target(page,spec.pages.size(),(e.getX()-downX)/scale,(e.getY()-downY)/scale));
                return true;
            case MotionEvent.ACTION_CANCEL:return true;
            default:return super.onTouchEvent(e);
        }
    }
    /** Text shown on the music tile: remote player and title while a remote player plays, otherwise a dash. */
    public void musicInfo(String text){musicInfo=text==null||text.isEmpty()?"—":text;for(Tile t:tiles.values())if(t.def.feed==CardDefinition.Feed.MUSIC)t.render(Collections.emptyMap(),true);}
    public void clock(String time,String weekday,String date){
        boolean refit=!this.time.equals(time)&&this.time.length()!=time.length();
        this.time=time;this.weekday=weekday;this.date=date;overlay.setClock(time);
        if(nightLayer.getVisibility()==VISIBLE){nightTime.setText(time);nightDate.setText(date);}
        for(Tile t:tiles.values())if(t.def.feed==CardDefinition.Feed.CLOCK){if(refit)t.scale(scale,t.unitW,t.unitH);t.clock();}
        long now=System.currentTimeMillis(); // a forecast expires without any HA delta: re-render just that tile (SPEC 0.9 pkt 6.2)
        for(Tile t:tiles.values())if(t.forecastExpiresAt>0&&t.forecastExpiresAt<=now&&lastStates!=null){t.forecastExpiresAt=0;t.render(lastStates,lastLive);}
    }
    public void setIssue(String issue){this.issue=issue;refreshStatus();}
    public void setMessage(String message){this.message=message==null?"":message;refreshStatus();}
    private void refreshStatus(){Theme t=Theme.current();status.setText(issue!=null?issue:message);status.setTextColor(issue!=null?t.accent:t.muted);}
    public void connected(boolean value){haConnected=value;connection.setImageTintList(ColorStateList.valueOf(value?Theme.HA_CONNECTED:Theme.current().muted));connection.setContentDescription(value?"Home Assistant: połączono":"Home Assistant: brak aktualnego połączenia");}
    public void onBrandHold(Runnable action){brand.setContentDescription("Helios. Przytrzymaj, aby otworzyć menu.");brand.setOnLongClickListener(v->{action.run();return true;});}
    /** Calls in flight by item id, kept apart from the views: a page rebuilt mid-call shows its spinner again. */
    private final Set<String> pendingIds=new HashSet<>();
    public void pending(String id,boolean on){if(on)pendingIds.add(id);else pendingIds.remove(id);Tile t=tiles.get(id);if(t!=null)t.pending(on);}
    /** visibility holds the last decided value per conditional item; a conditional item without an entry stays hidden. */
    public void render(Map<String,EntityStates.Entity> states,Map<String,Boolean> visibility,boolean live){
        lastStates=states;lastVisibility=visibility;lastLive=live;
        long now=System.currentTimeMillis(),due=-1;
        for(AlertsTile a:alertTiles.values()){
            Tile sub=tiles.get(a.item.id);
            boolean stand=a.render(states,live,now)&&sub!=null; // no stand-in configured: the tile itself says "Brak uwag"
            boolean shown=!a.item.conditional()||Boolean.TRUE.equals(visibility.get(a.item.id)); // visible_when hides the tile and its stand-in alike
            if((a.getVisibility()!=VISIBLE)!=(stand||!shown)&&fingerDown&&downOn.contains(a.item.id))voidTap=true; // only the card that changed under the finger loses its tap
            a.setVisibility(shown&&!stand?VISIBLE:GONE);
            if(sub!=null)sub.setVisibility(shown&&stand?VISIBLE:GONE);
            if(!stand&&sub!=null&&a.gate.due()>0)due=due<0?a.gate.due():Math.min(due,a.gate.due());
        }
        removeCallbacks(emptyCheck);if(due>0)postDelayed(emptyCheck,Math.max(0,due-now));
        for(Tile t:tiles.values()){
            if(t.substitute){if(t.getVisibility()==VISIBLE)t.render(states,live);continue;}
            boolean shown=!t.item.conditional()||Boolean.TRUE.equals(visibility.get(t.item.id));
            t.setVisibility(shown?VISIBLE:GONE);
            if(shown)t.render(states,live);
        }
    }

    /** Tomorrow's forecast from HA's daily feed; null until it arrives and again whenever the session drops. */
    public void tomorrow(Tomorrow t){
        String key=t==null?"":t.entity+"|"+t.value()+"|"+t.detail()+"|"+t.icon(); // the entity too: the same numbers for another entity are a new forecast
        if(key.equals(tomorrowKey))return;
        tomorrowKey=key;tomorrow=t;
        if(lastStates!=null)render(lastStates,lastVisibility,lastLive);
    }
    private Tomorrow tomorrow;private String tomorrowKey="";
    private final CardBodies.Env env=new CardBodies.Env(){
        public String time(){return time;}public String weekday(){return weekday;}public String date(){return date;}public String musicInfo(){return musicInfo;}public long now(){return System.currentTimeMillis();}
        public Tomorrow tomorrow(){return tomorrow;}
    };
    /** The frame every card shares: grid box, theme, pending spinner, accessibility. What it shows comes from CardBodies, how it taps from CardDefinition. */
    private final class Tile extends FrameLayout {
        final DashboardSpec.Item item;
        final CardDefinition def;final CardBodies.Body body;
        final LinearLayout split,column,head,side,valueLine;
        final boolean inlineIcon;
        final View rule;
        final IconView icon;
        final IconView valueIcon; // BIG_FIT only: the icon in front of the big value, null for every other layout
        final TextView title,value,detail,detail2;
        final ProgressBar spinner;
        final boolean clock;
        float unitW,unitH;
        boolean live=true,accent,known=true;long forecastExpiresAt;
        String rowsKey=""; // the second half is rebuilt only when what it shows actually changes
        CardBodies.Side lastSide;
        boolean substitute; // the stand-in card of an `alerts` tile: shown by that tile's state, not by visible_when
        String shownIcon; // the config icon, or the entity's own when the body prefers it
        Tile(DashboardSpec.Item item){
            super(DashboardView.this.getContext());this.item=item;def=CardDefinition.of(item.type);body=CardBodies.FOR.get(item.type);clock=def.layout==CardDefinition.Layout.CLOCK;shownIcon=item.icon;
            split=new LinearLayout(getContext());split.setOrientation(LinearLayout.HORIZONTAL);addView(split,new LayoutParams(-1,-1));
            column=new LinearLayout(getContext());column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER_VERTICAL);split.addView(column,new LinearLayout.LayoutParams(0,-1,1));
            rule=new View(getContext());rule.setVisibility(GONE);split.addView(rule,new LinearLayout.LayoutParams(1,-1));
            side=new LinearLayout(getContext());side.setOrientation(LinearLayout.VERTICAL);side.setGravity(Gravity.CENTER_VERTICAL);side.setVisibility(GONE);split.addView(side,new LinearLayout.LayoutParams(0,-1,1)); // same weight as column: the rule sits in the middle, not wherever today's text ends
            head=new LinearLayout(getContext());head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);column.addView(head);
            icon=new IconView(getContext());
            title=line(sans,1);
            // A big-value tile reads better with the icon beside the number and no word above it; everything else keeps the icon in the header.
            inlineIcon=def.layout==CardDefinition.Layout.LARGE_VALUE;
            if(inlineIcon){
                head.addView(title);
                valueLine=new LinearLayout(getContext());valueLine.setOrientation(LinearLayout.HORIZONTAL);valueLine.setGravity(Gravity.CENTER_VERTICAL);
                valueLine.addView(icon);valueIcon=null;
                value=line(sans,1);valueLine.addView(value);
                column.addView(valueLine);
            }else if(def.layout==CardDefinition.Layout.BIG_FIT){
                head.addView(icon);head.addView(title);
                valueLine=new LinearLayout(getContext());valueLine.setOrientation(LinearLayout.HORIZONTAL);valueLine.setGravity(Gravity.CENTER_VERTICAL);
                valueIcon=new IconView(getContext());valueIcon.setVisibility(GONE);valueLine.addView(valueIcon);
                value=line(sans,1);valueLine.addView(value);
                column.addView(valueLine);
            }else{
                head.addView(icon);head.addView(title);
                valueLine=null;valueIcon=null;
                value=line(clock?mono:sans,clock?1:2);column.addView(value);
            }
            if(shownIcon==null)icon.setVisibility(GONE);
            detail=line(sans,1);column.addView(detail);
            detail2=line(sans,1);column.addView(detail2);detail2.setVisibility(GONE);
            spinner=new ProgressBar(getContext());spinner.setIndeterminate(true);spinner.setVisibility(GONE);addView(spinner,new LayoutParams(-2,-2,Gravity.TOP|Gravity.END));
            // The type's own word ("Pogoda") is not worth a line above a tile that already shows a weather icon;
            // a title someone wrote in the YAML still shows. The description keeps the word either way.
            String label=inlineIcon&&item.title==null?"":CardBodies.defaultLabel(item);
            title.setText(label);if(label.isEmpty())title.setVisibility(GONE);
            head.setVisibility(label.isEmpty()&&(inlineIcon||shownIcon==null)?GONE:VISIBLE);
            if(item.interactive()){setClickable(true);setFocusable(true);setOnClickListener(v->{if(actions!=null&&!voidTap)actions.onTap(item);});}
            theme();
            if(clock)clock();
        }
        private TextView line(Typeface face,int lines){TextView t=new TextView(getContext());t.setTypeface(face);t.setMaxLines(lines);t.setEllipsize(TextUtils.TruncateAt.END);t.setIncludeFontPadding(false);return t;}
        /** Over a photo: information tiles keep 92% of the surface colour, the clock a 70% black veil (SPEC 0.8b pkt 6); plain colour otherwise. */
        private int surface(Theme t){return !photo?t.surface:clock?0xB3000000:(0xEB000000|(t.surface&0xFFFFFF));}
        /** Notification tiles (entity + visible_when) glow: accent outline, warm tint, accent text; never while offline. The background is set only here. */
        void theme(){
            Theme t=Theme.current();float r=Theme.RADIUS*scale;
            boolean attention=def.notifies&&item.conditional()&&live;
            if(attention)setBackground(Theme.card(!photo?t.attentionSurface:(0xEB000000|(t.attentionSurface&0xFFFFFF)),Theme.ATTENTION,2*scale,r));
            else setBackground(Theme.card(surface(t),r));
            title.setTextColor(attention?Theme.ATTENTION:t.muted);detail.setTextColor(t.muted);detail2.setTextColor(t.muted);
            value.setTextColor(!live?t.muted:attention?Theme.ATTENTION:t.text);
            icon.setVisibility(shownIcon==null?GONE:VISIBLE);
            if(!inlineIcon&&title.getLayoutParams() instanceof LinearLayout.LayoutParams){ // the gap follows the icon actually shown, also one a body picks (climate)
                LinearLayout.LayoutParams tp=(LinearLayout.LayoutParams)title.getLayoutParams();int gap=shownIcon==null?0:Math.round(8*scale);
                if(tp.leftMargin!=gap){tp.leftMargin=gap;title.setLayoutParams(tp);}
            }
            head.setVisibility(title.getText().length()==0&&(inlineIcon||shownIcon==null)?GONE:VISIBLE);
            if(shownIcon!=null)icon.set(shownIcon,attention?Theme.ATTENTION:accent?t.accent:t.muted);
            rule.setBackgroundColor((0x33<<24)|(t.muted&0xFFFFFF));
        }
        /** Sizes in 800x480 units; w/h are the tile's own size in those units, so the clock fits its hour by measurement. */
        void scale(float s,float w,float h){
            unitW=w;unitH=h;
            int pad=Math.round((clock?ClockLayout.PAD:14)*s);column.setPadding(pad,pad,pad,pad);
            int iconPx=Math.round((inlineIcon?52:26)*s);
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(iconPx,iconPx);if(inlineIcon)ip.rightMargin=Math.round(10*s);
            icon.setLayoutParams(ip);
            LinearLayout.LayoutParams t=new LinearLayout.LayoutParams(0,-2,1);t.leftMargin=inlineIcon||item.icon==null?0:Math.round(8*s);title.setLayoutParams(t);
            size(title,clock?ClockLayout.TITLE:17,s);
            switch(def.layout){
                case CLOCK:{
                    ClockLayout.Plan plan=ClockLayout.plan((text,size)->{monoPaint.setTextSize(size);monoPaint.setLetterSpacing(-.05f);return monoPaint.measureText(text);},time,w,h,item.title!=null);
                    size(value,plan.hourSize,s);value.setLetterSpacing(-.05f);
                    size(detail,ClockLayout.DATE,s);size(detail2,ClockLayout.WEEKDAY,s);
                    detail.setVisibility(plan.showDate?VISIBLE:GONE);detail2.setVisibility(plan.showWeekday?VISIBLE:GONE);
                    break;}
                case LARGE_VALUE:size(value,44,s);size(detail,18,s);break;
                default:{
                    float inner=w-28;
                    if(valueIcon!=null){ // a fixed-size icon in front; the value fits what is left
                        int px=Math.round(40*s);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(px,px);vp.rightMargin=Math.round(6*s);valueIcon.setLayoutParams(vp);
                        if(valueIcon.getVisibility()==VISIBLE)inner-=46;
                    }
                    float fit=TextFit.size((text,size)->{sansPaint.setTextSize(size);return sansPaint.measureText(text);},value.getText().toString(),inner,24,def.layout==CardDefinition.Layout.BIG_FIT?44:28);
                    size(value,fit,s);size(detail,17,s);
                }
            }
            int spin=Math.round(28*s);LayoutParams sp=new LayoutParams(spin,spin,Gravity.TOP|Gravity.END);sp.topMargin=sp.rightMargin=Math.round(10*s);spinner.setLayoutParams(sp);
            LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(1,-1);rl.topMargin=rl.bottomMargin=Math.round(14*s);rule.setLayoutParams(rl);
            rowsKey="";details(lastSide); // text sizes follow the tile, so the half is rebuilt after every resize
            theme();
        }
        void clock(){apply(body.render(item,Collections.emptyMap(),true,env));}
        /** A pending call blocks only tiles whose tap is the action; cover tiles open a panel and stay reachable (SPEC 0.9 pkt 4.2). */
        void pending(boolean on){spinner.setVisibility(on?VISIBLE:GONE);if(def.gate(item)==CardDefinition.Gate.KNOWN_NOT_PENDING)setEnabled(!on&&live&&known);}
        void render(Map<String,EntityStates.Entity> states,boolean live){
            if(def.feed==CardDefinition.Feed.CLOCK)return;
            boolean shown=def.feed==CardDefinition.Feed.MUSIC||live; // the music tile answers to the player, not to HA
            EntityStates.Entity e=item.entity==null?null:states.get(item.entity);
            known=ActionPolicy.usable(item.action,e);
            CardBodies.CardContent c=body.render(item,states,shown,env);
            boolean changed=!c.value.equals(value.getText().toString());
            apply(c);
            String label=c.label!=null?c.label:CardBodies.defaultLabel(item);
            title.setText(shown?label:label+" (offline)"); // stale values are muted text plus a word, never a faded tile (contrast)
            accent=c.accent;this.live=shown;forecastExpiresAt=c.expiresAt;theme();
            if(changed&&(def.layout==CardDefinition.Layout.FIT||def.layout==CardDefinition.Layout.BIG_FIT)&&def.feed==CardDefinition.Feed.ENTITIES&&unitW>0)scale(scale,unitW,unitH);
            value.setContentDescription(c.value);
            gate();
        }
        private void apply(CardBodies.CardContent c){
            value.setText(c.value);detail.setText(c.detail);detail2.setText(c.detail2);shownIcon=c.icon!=null?c.icon:item.icon;
            if(valueIcon!=null){valueIcon.setVisibility(c.valueIcon==null?GONE:VISIBLE);if(c.valueIcon!=null)valueIcon.set(c.valueIcon,Theme.current().muted);}
            if(!clock)detail.setVisibility(c.detail.isEmpty()?GONE:VISIBLE);
            lastSide=c.side;details(c.side);
            setContentDescription(c.description);
        }
        /** The second half of a wide tile: built like the first one - big icon, big value, a small line under it. */
        private void details(CardBodies.Side s){
            String key=s==null?"":s.icon+" "+s.value+" "+s.detail;
            if(key.equals(rowsKey))return;
            rowsKey=key;
            side.removeAllViews();
            side.setVisibility(s==null?GONE:VISIBLE);rule.setVisibility(s==null?GONE:VISIBLE);
            if(s==null)return;
            Theme t=Theme.current();
            int pad=Math.round(14*scale);side.setPadding(pad,pad,pad,pad); // the column's own padding, so both halves sit the same distance from the rule and the edge
            LinearLayout top=new LinearLayout(getContext());top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);
            if(s.icon!=null){
                IconView ic=new IconView(getContext());ic.set(s.icon,t.muted);
                int px=Math.round(52*scale);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(px,px);ip.rightMargin=Math.round(10*scale);
                top.addView(ic,ip);
            }
            TextView reading=line(sans,1);reading.setText(s.value);reading.setTextColor(t.text);size(reading,44,scale);
            top.addView(reading,new LinearLayout.LayoutParams(-2,-2));
            side.addView(top,new LinearLayout.LayoutParams(-2,-2));
            if(!s.detail.isEmpty()){
                TextView under=line(sans,1);under.setText(s.detail);under.setTextColor(t.muted);size(under,18,scale);
                side.addView(under,new LinearLayout.LayoutParams(-2,-2));
            }
        }
        /** Visible but inactive while HA reports no usable state; a call in flight blocks only tiles whose tap is that call. */
        private void gate(){
            switch(def.gate(item)){
                case KNOWN_NOT_PENDING:setEnabled(live&&known&&spinner.getVisibility()!=VISIBLE);break;
                case KNOWN:setEnabled(live&&known);break;
                default:break;
            }
        }
    }

    /**
     * SPEC 0.20: the count and the newest warnings in one tile (1x1, 1x2 or 2x1), drawn at the fixed positions of the
     * spec's geometry table; the whole tile opens the list. What it says comes from AlertsModel.
     */
    private final class AlertsTile extends FrameLayout {
        final DashboardSpec.Item item;
        final AlertsModel.EmptyGate gate=new AlertsModel.EmptyGate();
        private final IconView headIcon;
        private final TextView title,count,summary,suffix,idle,footer;
        private final LinearLayout summaryRow;
        private final IconView[] slotIcons=new IconView[3];private final TextView[] slotTexts=new TextView[3];private final String[] slotGlyphs=new String[3];
        private final boolean tall,wide;
        private AlertsModel model;private boolean live=true;
        AlertsTile(DashboardSpec.Item item){
            super(DashboardView.this.getContext());this.item=item;
            tall=item.height>1;wide=item.width>1;
            headIcon=new IconView(getContext());addView(headIcon);
            title=text(1);count=text(1);idle=text(1);footer=text(1);
            summaryRow=new LinearLayout(getContext());summaryRow.setOrientation(LinearLayout.HORIZONTAL);addView(summaryRow);
            summary=line(1);suffix=line(1);
            summaryRow.addView(summary,new LinearLayout.LayoutParams(-2,-2)); // its max width leaves room for the suffix: the "+N" is never cut
            summaryRow.addView(suffix,new LinearLayout.LayoutParams(-2,-2));
            for(int n=0;n<3;n++){slotIcons[n]=new IconView(getContext());addView(slotIcons[n]);slotTexts[n]=text(tall?2:1);}
            setClickable(true);setFocusable(true);
            setOnClickListener(v->{if(actions!=null&&!voidTap)actions.onTap(item);});
        }
        private TextView line(int lines){TextView t=new TextView(getContext());t.setTypeface(sans);t.setMaxLines(lines);t.setEllipsize(TextUtils.TruncateAt.END);t.setIncludeFontPadding(false);return t;}
        private TextView text(int lines){TextView t=line(lines);addView(t);return t;}
        private void put(View v,float x,float y,float w,float h){LayoutParams p=new LayoutParams(Math.round(w*scale),Math.round(h*scale));p.leftMargin=Math.round(x*scale);p.topMargin=Math.round(y*scale);v.setLayoutParams(p);}
        /** Positions from the SPEC 0.20 pkt 3 table, in 800x480 units of the tile's own box. */
        private float unitW;
        void scale(float s,float w,float h){
            unitW=w;
            put(headIcon,12,10,22,22);put(title,40,10,w-52,22);size(title,17,s);
            put(count,12,wide?40:32,wide?64:w-24,48);size(count,44,s);
            put(summaryRow,12,84,w-24,20);size(summary,17,s);size(suffix,17,s);
            put(footer,wide?92:12,tall?228:106,wide?284:w-24,16);size(footer,13,s);
            for(int n=0;n<3;n++){
                if(tall){put(slotIcons[n],12,92+44*n,20,20);put(slotTexts[n],40,92+44*n,w-52,40);size(slotTexts[n],17,s);}
                else{put(slotIcons[n],92,44+32*n,20,20);put(slotTexts[n],120,44+32*n,w-132,24);size(slotTexts[n],19,s);}
            }
            if(tall)put(idle,12,92,w-24,40);else if(wide)put(idle,92,44,w-104,24);else put(idle,12,84,w-24,20);
            size(idle,wide?19:17,s);
            theme();
        }
        /** Fills the tile; true when its place should go to the stand-in card now. */
        boolean render(Map<String,EntityStates.Entity> states,boolean live,long now){
            this.live=live;model=new AlertsModel(item.sources,states,live);
            String label=CardBodies.defaultLabel(item);
            title.setText(live?label:label+" (offline)");
            count.setText(model.count());
            String word=model.idleWord();
            idle.setText(word==null?"":word);idle.setVisibility(word==null?GONE:VISIBLE);
            boolean single=!tall&&!wide;
            summaryRow.setVisibility(single&&word==null?VISIBLE:GONE);
            if(single&&word==null){
                summary.setText(model.summaryTitle());suffix.setText(model.summarySuffix());
                summary.setMaxWidth(Math.max(0,Math.round((unitW-24)*scale-suffix.getPaint().measureText(model.summarySuffix()))));
            }
            List<AlertsModel.Slot> slots=single||word!=null?Collections.emptyList():model.slots(tall?3:2);
            for(int n=0;n<3;n++){
                boolean on=n<slots.size();
                slotIcons[n].setVisibility(on?VISIBLE:GONE);slotTexts[n].setVisibility(on?VISIBLE:GONE);
                slotGlyphs[n]=on?slots.get(n).icon:null;
                if(on)slotTexts[n].setText(slots.get(n).title);
            }
            String foot=model.footer();footer.setText(foot==null?"":foot);footer.setVisibility(foot==null?GONE:VISIBLE);
            setContentDescription(model.description(label));
            theme();
            return gate.show(model.quiet(),now);
        }
        /** Warm glow while something applies and the data is live; the plain card otherwise. */
        void theme(){
            Theme t=Theme.current();float r=Theme.RADIUS*scale;
            boolean on=live&&model!=null&&model.a()>0;
            if(on)setBackground(Theme.card(!photo?t.attentionSurface:(0xEB000000|(t.attentionSurface&0xFFFFFF)),Theme.ATTENTION,2*scale,r));
            else setBackground(Theme.card(!photo?t.surface:(0xEB000000|(t.surface&0xFFFFFF)),r));
            int ink=on?Theme.ATTENTION:t.muted;
            headIcon.set(on?"mdi:bell-alert":"mdi:bell",ink);
            title.setTextColor(ink);
            count.setTextColor(on?Theme.ATTENTION:live?t.text:t.muted);
            summary.setTextColor(t.text);suffix.setTextColor(t.text);idle.setTextColor(t.muted);footer.setTextColor(t.muted);
            for(int n=0;n<3;n++){slotTexts[n].setTextColor(t.text);if(slotGlyphs[n]!=null)slotIcons[n].set(slotGlyphs[n],ink);}
        }
    }
}
