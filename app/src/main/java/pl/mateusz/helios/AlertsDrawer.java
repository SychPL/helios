package pl.mateusz.helios;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.*;
import java.util.function.Consumer;

/**
 * The list behind an `alerts` tile (SPEC 0.20 pkt 4): slides in from the right under the bar, one row per active
 * warning (newest first) and one muted row per warning without data. Rows are information, not buttons; the only
 * control is "Zgaś" on a source with off_entity, and every call goes through Host.turnOff, which re-checks it.
 * While a finger or a fling is on the list the rows keep their heights and places; what changed shape waits for it.
 */
final class AlertsDrawer {
    interface Host {
        Map<String,EntityStates.Entity> states();
        boolean live();
        /** Turns the source's lights off; null when the call went out, else why nothing was sent. */
        String turnOff(DashboardSpec.Source source,Consumer<String> done);
        /** "Zgaś" state by source key; owned by the host so it outlives this drawer. */
        Map<String,AlertsModel.OffState> offs();
        /** The palette confirmation over the drawer; the drawer stays open. Returns the dialog so it can be withdrawn. */
        Dialog confirm(String question,Runnable ok);
    }
    static final long IDLE_MS=120_000;
    /** Window below the 52-unit bar; list from y=124 on screen (72 inside the window). */
    static final int WIN_Y=52,WIN_W=800,WIN_H=428,LIST_Y=72,ROW_MIN=100,ROW_GAP=8,BUTTON_COLUMN=112;

    final Dialog dialog;
    Runnable onUserClose;
    private final Activity activity;private final DashboardSpec.Item item;private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final float s;
    private final android.graphics.Typeface sans;
    private final FrameLayout root;
    private final IconView headIcon;private final TextView headCount;
    private final ScrollView scroll;private final LinearLayout list;
    private final Map<String,RowView> rows=new LinkedHashMap<>();
    private List<String> shownKeys; // null until the first build, so an empty list still gets its "Brak uwag"
    private boolean touching,rebuildWaiting,closed,frozen;
    private long lastScroll;
    private float downX,downY;private boolean dragging;
    private Dialog confirm;private String confirmKey;
    private final Runnable idle=this::closeByUser,settle=this::settle,tick=this::refresh;

    AlertsDrawer(Activity activity,DashboardSpec.Item item,Host host){
        this.activity=activity;this.item=item;this.host=host;
        sans=android.graphics.Typeface.createFromAsset(activity.getAssets(),"Geist.ttf");
        View decor=activity.getWindow().getDecorView();
        int sw=decor.getWidth()>0?decor.getWidth():activity.getResources().getDisplayMetrics().widthPixels,sh=decor.getHeight()>0?decor.getHeight():activity.getResources().getDisplayMetrics().heightPixels;
        s=Math.min(sw/800f,sh/480f);
        Theme t=Theme.current();
        root=new FrameLayout(activity){
            @Override public boolean dispatchTouchEvent(MotionEvent e){
                int a=e.getActionMasked();
                if(a==MotionEvent.ACTION_DOWN){touched();touching=true;freeze();}
                else if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){touching=false;main.removeCallbacks(settle);main.postDelayed(settle,250);}
                return super.dispatchTouchEvent(e);
            }
            /** A clearly sideways move to the right is the close gesture; the rows' buttons then get CANCEL, not a tap. */
            @Override public boolean onInterceptTouchEvent(MotionEvent e){
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:downX=e.getRawX();downY=e.getRawY();dragging=false;return false;
                    case MotionEvent.ACTION_MOVE:{float dx=(e.getRawX()-downX)/s,dy=(e.getRawY()-downY)/s;dragging=dx>0&&PageSwipe.sideways(dx,dy);return dragging;}
                    default:return false;
                }
            }
            @Override public boolean onTouchEvent(MotionEvent e){
                float dx=(e.getRawX()-downX)/s;
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:return true;
                    case MotionEvent.ACTION_MOVE:if(dragging)setTranslationX(Math.max(0,dx*s));return true;
                    case MotionEvent.ACTION_UP:
                        if(dragging&&dx>=PageSwipe.DISTANCE)closeByUser();else animate().translationX(0).setDuration(150).start();
                        dragging=false;return true;
                    case MotionEvent.ACTION_CANCEL:animate().translationX(0).setDuration(150).start();dragging=false;return true;
                    default:return false;
                }
            }
        };
        root.setBackgroundColor(t.background);root.setClickable(true);
        LinearLayout head=new LinearLayout(activity);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);
        headIcon=new IconView(activity);head.addView(headIcon,new LinearLayout.LayoutParams(px(34),px(34)));
        TextView title=text(26,t.text,1);title.setText(CardBodies.defaultLabel(item));title.setPadding(px(10),0,0,0);head.addView(title,new LinearLayout.LayoutParams(-2,-2));
        headCount=text(18,t.muted,1);headCount.setPadding(px(16),0,0,0);head.addView(headCount,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(head,box(16,15,700,34));
        MusicOverlay.IconButton close=new MusicOverlay.IconButton(activity,"mdi:close","Zamknij");root.addView(close,box(728,0,64,64));close.style(t.raised,t.text,s);
        close.setOnClickListener(v->closeByUser());
        scroll=new ScrollView(activity){
            @Override protected void onScrollChanged(int l,int top,int oldl,int oldt){super.onScrollChanged(l,top,oldl,oldt);lastScroll=SystemClock.uptimeMillis();freeze();main.removeCallbacks(settle);main.postDelayed(settle,250);}
        };
        scroll.setVerticalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(px(8),0,px(8),px(8));
        scroll.addView(list,new FrameLayout.LayoutParams(-1,-2));
        root.addView(scroll,box(0,LIST_Y,WIN_W,WIN_H-LIST_Y));
        dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root,new android.view.ViewGroup.LayoutParams(px(WIN_W),px(WIN_H)));
        Window win=dialog.getWindow();
        if(win!=null){
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            win.setGravity(Gravity.TOP|Gravity.START);win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp=win.getAttributes();lp.x=0;lp.y=px(WIN_Y);lp.width=px(WIN_W);lp.height=px(WIN_H);win.setAttributes(lp);
        }
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d->closeByUser()); // back: a confirmation first (its own dialog), then this
        dialog.setOnDismissListener(d->stop());
        refresh();
    }
    void show(){
        root.setTranslationX(px(WIN_W));
        dialog.show();touched();
        root.animate().translationX(0).setDuration(200).start();
    }
    void closeByUser(){
        if(closed)return;
        stop();
        if(onUserClose!=null)onUserClose.run();else dialog.dismiss();
    }
    private void stop(){
        closed=true;main.removeCallbacksAndMessages(null);
        if(confirm!=null){Dialog c=confirm;confirm=null;c.dismiss();}
    }
    private void touched(){main.removeCallbacks(idle);main.postDelayed(idle,IDLE_MS);}

    // --- content ---
    /** Re-renders from the latest states; rows that appear, go or move wait until nobody touches or scrolls the list. */
    void refresh(){
        if(closed)return;
        long now=System.currentTimeMillis();
        Map<String,EntityStates.Entity> states=host.states();boolean live=host.live();
        AlertsModel m=new AlertsModel(item.sources,states,live);
        Theme t=Theme.current();
        headIcon.set(m.a()>0&&live?"mdi:bell-alert":"mdi:bell",m.a()>0&&live?Theme.ATTENTION:t.muted);
        headCount.setText(m.a()+(m.a()==1?" aktywna":m.a()>=2&&m.a()<=4?" aktywne":" aktywnych"));
        List<AlertsModel.Row> all=new ArrayList<>(m.active);all.addAll(m.unknown);
        List<String> keys=new ArrayList<>();for(AlertsModel.Row r:all)keys.add(slot(r));

        // a confirmation for a source that stopped being active is withdrawn: nothing goes out
        boolean stillActive=false;for(AlertsModel.Row r:m.active)if(r.key().equals(confirmKey))stillActive=true;
        if(confirm!=null&&!stillActive){Dialog c=confirm;confirm=null;c.dismiss();}
        if(keys.equals(shownKeys))rebuildWaiting=false; // a change that undid itself during the touch needs no rebuild
        else if(interacting())rebuildWaiting=true;
        else{rebuild(keys,all);rebuildWaiting=false;}
        long due=-1;
        for(AlertsModel.Row r:all){
            RowView v=rows.get(slot(r));
            if(v!=null)v.fill(r,states,live,now);
            AlertsModel.OffState o=host.offs().get(r.key());
            if(o!=null&&o.due()>0)due=due<0?o.due():Math.min(due,o.due());
        }
        main.removeCallbacks(tick);
        if(due>0)main.postDelayed(tick,Math.max(50,due-now));
    }
    /** A row's place in the list: its source and whether it is active, so a row that gains or loses data is built anew. */
    private static String slot(AlertsModel.Row r){return r.key()+"|"+r.cond;}
    private static String source(String slot){return slot.substring(0,slot.lastIndexOf('|'));}
    private RowView rowOf(String source){for(Map.Entry<String,RowView> e:rows.entrySet())if(source(e.getKey()).equals(source))return e.getValue();return null;}
    private boolean interacting(){return touching||SystemClock.uptimeMillis()-lastScroll<200;}
    /** The finger went down or the list moved: every row keeps its height and line count until it settles. */
    private void freeze(){
        if(frozen)return;frozen=true;
        for(RowView v:rows.values())v.freeze();
    }
    private void settle(){
        if(closed)return;
        if(interacting()){main.removeCallbacks(settle);main.postDelayed(settle,100);return;}
        frozen=false;
        List<String> order=shownKeys==null?Collections.<String>emptyList():shownKeys;
        String[] anchor=anchor(order);
        if(rebuildWaiting)refresh(); // rows that came, went or moved meanwhile; a rebuild keeps the same anchor
        for(RowView v:rows.values())v.thaw(); // rows grown or shrunk while frozen take their size now; the row being read stays put
        restore(anchor,order);
    }
    /** The topmost fully visible row and its distance from the top of the list, or null. */
    private String[] anchor(List<String> order){
        for(String k:order){RowView v=rows.get(k);if(v!=null&&v.view.getTop()-scroll.getScrollY()>=0)return new String[]{k,String.valueOf(v.view.getTop()-scroll.getScrollY())};}
        return null;
    }
    /** Scrolls so the anchor row (or the next one of the old order that is still there) sits where it was. */
    private void restore(String[] anchor,List<String> order){
        if(anchor==null)return;
        RowView target=null; // by source, not by view key: a row that only lost or regained its data is still the one being read
        for(int i=order.indexOf(anchor[0]);i>=0&&i<order.size()&&target==null;i++)target=rowOf(source(order.get(i)));
        if(target==null)return;
        final View v=target.view;final int off=Integer.parseInt(anchor[1]);
        scroll.post(()->scroll.scrollTo(0,Math.max(0,v.getTop()-off)));
    }
    /** New rows in the new order; the topmost fully visible row (or the next one that is still there) stays where it was. */
    private void rebuild(List<String> keys,List<AlertsModel.Row> all){
        List<String> old=shownKeys==null?Collections.<String>emptyList():shownKeys;
        String[] anchor=anchor(old);
        Map<String,RowView> keep=new HashMap<>(rows);
        rows.clear();list.removeAllViews();
        if(all.isEmpty()){
            TextView none=text(22,Theme.current().muted,1);none.setText("Brak uwag");none.setGravity(Gravity.CENTER);
            list.addView(none,new LinearLayout.LayoutParams(-1,px(WIN_H-LIST_Y-8)));
        }
        for(AlertsModel.Row r:all){
            RowView v=keep.get(slot(r));
            if(v==null)v=new RowView(r);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=list.getChildCount()==0?0:px(ROW_GAP);
            list.addView(v.view,p);rows.put(slot(r),v);
        }
        shownKeys=keys;
        restore(anchor,old);
    }

    /** One warning: icon, title, "Aktywne od", the helper's text (or a message in its place) and, with off_entity, "Zgaś". */
    private final class RowView {
        final FrameLayout view;final IconView icon;final TextView title,since,text;final FrameLayout button;final TextView buttonLabel;final ProgressBar spinner;
        final boolean withButton;
        String key;boolean showingMessage;
        RowView(AlertsModel.Row r){
            Theme t=Theme.current();
            key=r.key();withButton=r.cond==AlertsModel.Cond.ACTIVE&&r.source.offEntity!=null;
            view=new FrameLayout(activity);view.setBackground(Theme.card(t.surface,16*s));view.setMinimumHeight(px(ROW_MIN));
            int right=withButton?BUTTON_COLUMN:16,width=WIN_W-16;
            icon=new IconView(activity);view.addView(icon,box(16,16,28,28));
            LinearLayout top=new LinearLayout(activity);top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);
            title=text(22,t.text,1);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            since=text(16,t.muted,1);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2);sp.leftMargin=px(16);top.addView(since,sp);
            view.addView(top,box(56,14,width-56-right,30));
            text=text(19,t.muted,3);
            FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(px(width-56-right),-2);tp.leftMargin=px(56);tp.topMargin=px(52);tp.bottomMargin=px(16);
            view.addView(text,tp);
            if(withButton){
                button=new FrameLayout(activity);button.setClickable(true);button.setFocusable(true);
                buttonLabel=text(19,t.text,1);buttonLabel.setText("Zgaś");buttonLabel.setGravity(Gravity.CENTER);button.addView(buttonLabel,new FrameLayout.LayoutParams(-1,-1));
                spinner=new ProgressBar(activity);spinner.setIndeterminate(true);spinner.setVisibility(View.GONE);
                button.addView(spinner,new FrameLayout.LayoutParams(px(28),px(28),Gravity.CENTER));
                view.addView(button,box(width-BUTTON_COLUMN+8,18,96,64));
                final DashboardSpec.Source src=r.source;
                button.setOnClickListener(v->tapOff(src));
            }else{button=null;buttonLabel=null;spinner=null;}
        }
        void fill(AlertsModel.Row r,Map<String,EntityStates.Entity> states,boolean live,long now){
            Theme t=Theme.current();
            boolean active=r.cond==AlertsModel.Cond.ACTIVE;
            icon.set(r.icon(),active?Theme.ATTENTION:t.muted);
            title.setText(active?r.source.title:r.source.title+" - brak danych");title.setTextColor(active?t.text:t.muted);
            String when=active&&r.source.showSince?AlertsModel.since(r.since,now,TimeZone.getDefault()):null;
            since.setText(when==null?"":when);since.setVisibility(when==null?View.GONE:View.VISIBLE);
            String sig=r.cond+"|"+r.text;
            AlertsModel.OffState o=host.offs().get(key);
            String msg=o==null?null:o.message(sig,now);
            if(msg!=null){
                if(!showingMessage&&text.getHeight()>0)text.setMinHeight(text.getHeight()); // the message takes the text's place, not its height: the row keeps its size
                showingMessage=true;text.setText(msg);text.setTextColor(Theme.ATTENTION);text.setMaxLines(1);
            }else{
                if(showingMessage){showingMessage=false;text.setMinHeight(0);}
                text.setText(active?(r.text==null?"Brak treści":r.text):"");text.setTextColor(t.muted);if(!frozen)text.setMaxLines(3);
            }
            text.setVisibility(active?View.VISIBLE:View.GONE);
            if(button!=null){
                if(o!=null)o.observe(active,now);
                boolean busy=o!=null&&o.busy(now,sig),held=o!=null&&o.held();
                boolean usable=AlertsModel.offBlock(r.source,states,live)==null;
                spinner.setVisibility(busy?View.VISIBLE:View.GONE);buttonLabel.setVisibility(busy?View.INVISIBLE:View.VISIBLE);
                button.setEnabled(!busy&&!held);
                // unusable (no connection, target unknown) looks off but still answers a tap with the reason
                boolean looksOn=!busy&&!held&&usable;
                button.setBackground(looksOn?Theme.card(t.raised,14*s):Theme.card(t.surface,t.muted,Math.max(1,s),14*s));
                buttonLabel.setTextColor(looksOn?t.text:t.muted);
                button.setContentDescription(busy?"Gaszenie świateł, czekam na Home Assistant":"Zgaś światła: "+r.source.title);
            }
        }
        void freeze(){
            int h=view.getHeight();if(h<=0)return;
            view.getLayoutParams().height=h;view.setLayoutParams(view.getLayoutParams());
            text.setMaxLines(Math.max(1,text.getLineCount()));
        }
        void thaw(){
            view.getLayoutParams().height=-2;view.setLayoutParams(view.getLayoutParams());
            if(!showingMessage)text.setMaxLines(3);
        }
    }

    // --- Zgaś ---
    private void tapOff(DashboardSpec.Source src){
        if(closed)return;
        String key=src.whenEntity+"="+src.whenState;
        AlertsModel.OffState o=host.offs().computeIfAbsent(key,k->new AlertsModel.OffState());
        if(o.busy(System.currentTimeMillis(),signature(src))||o.held())return;
        Map<String,EntityStates.Entity> states=host.states();
        String why=AlertsModel.offBlock(src,states,host.live());
        if(why!=null){o.show(why,signature(src),System.currentTimeMillis());refresh();return;}
        confirmKey=key;
        confirm=host.confirm("Zgasić wszystkie obserwowane światła?",()->{
            confirm=null;
            if(closed)return;
            String again=AlertsModel.offBlock(src,host.states(),host.live());
            if(again!=null){o.show(again,signature(src),System.currentTimeMillis());refresh();return;}
            final int n=o.send(System.currentTimeMillis());
            String refused=host.turnOff(src,error->main.post(()->{o.result(n,error,signature(src),System.currentTimeMillis());if(!closed)refresh();}));
            if(refused!=null){o.result(n,refused,signature(src),System.currentTimeMillis());o.show(refused,signature(src),System.currentTimeMillis());}
            refresh();
        });
    }
    /** The row as it is now, for dropping a message once its source changes. */
    private String signature(DashboardSpec.Source src){
        Map<String,EntityStates.Entity> states=host.states();
        EntityStates.Entity text=states.get(src.entity);
        return AlertsModel.cond(src,states,host.live())+"|"+(text!=null&&text.known()?text.state:null);
    }

    // --- helpers ---
    private int px(float units){return Math.round(units*s);}
    private FrameLayout.LayoutParams box(float x,float y,float w,float h){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(px(w),px(h));p.leftMargin=px(x);p.topMargin=px(y);return p;}
    private TextView text(float size,int color,int lines){
        TextView v=new TextView(activity);v.setTextSize(TypedValue.COMPLEX_UNIT_PX,size*s);v.setTextColor(color);v.setIncludeFontPadding(false);
        v.setMaxLines(lines);v.setEllipsize(TextUtils.TruncateAt.END);v.setTypeface(sans);
        return v;
    }
}
