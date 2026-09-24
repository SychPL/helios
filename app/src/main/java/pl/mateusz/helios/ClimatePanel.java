package pl.mateusz.helios;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.ScrollView;
import android.widget.TextView;
import pl.mateusz.helios.OverlayGeometry.Box;
import java.util.*;
import java.util.function.Consumer;

/**
 * The full thermostat panel (SPEC 0.19 pkt 5): measured and set temperature, mode buttons, option lists and their
 * picker. Views only - what may be edited comes from ClimateModel, when calls go out from ClimateFlow, and every call
 * goes through Host.send, which re-checks it against the entity as it is at that moment.
 */
final class ClimatePanel {
    interface Host {
        EntityStates.Entity state();
        boolean live();
        boolean fahrenheit();
        /** Sends one climate call; null when it went out, else why it did not (nothing was sent). */
        String send(String service,Object value,Consumer<String> done);
    }
    static final long IDLE_MS=120_000;

    final Dialog dialog;
    private final Activity activity;private final DashboardSpec.Item item;private final Host host;
    private final ClimateFlow flow=new ClimateFlow();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final float s;
    private final TextView title,note,nowValue,activity_,setValue,setStatus,modeLabel;
    private final IconView titleIcon;
    private final MusicOverlay.IconButton minus,plus;
    private final LinearLayout modeRow,listRow;
    private final FrameLayout root;
    private Dialog picker;
    private String modesKey="",listsKey="";
    private final Runnable draftCheck=this::draftDue,confirmCheck=this::refresh,idle=this::closeByUser;

    ClimatePanel(Activity activity,DashboardSpec.Item item,String name,Host host){
        this.activity=activity;this.item=item;this.host=host;
        // the activity's own window, as the dashboard measures itself: display metrics may leave out system bars
        View decor=activity.getWindow().getDecorView();
        int sw=decor.getWidth()>0?decor.getWidth():activity.getResources().getDisplayMetrics().widthPixels,sh=decor.getHeight()>0?decor.getHeight():activity.getResources().getDisplayMetrics().heightPixels;
        s=Math.min(sw/800f,sh/480f);
        Theme t=Theme.current();
        root=new FrameLayout(activity){@Override public boolean dispatchTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN)touched();return super.dispatchTouchEvent(e);}};
        root.setBackgroundColor(t.background);
        LinearLayout head=new LinearLayout(activity);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);
        titleIcon=new IconView(activity);head.addView(titleIcon,new LinearLayout.LayoutParams(px(34),px(34)));
        title=text(26,t.text);title.setPadding(px(10),0,0,0);head.addView(title,new LinearLayout.LayoutParams(-2,-2));
        note=text(18,t.muted);note.setPadding(px(16),0,0,0);head.addView(note,new LinearLayout.LayoutParams(0,-2,1)); // why controls are off, or what failed
        root.addView(head,box(ClimatePanelGeometry.TITLE));
        title.setText(name);
        MusicOverlay.IconButton close=new MusicOverlay.IconButton(activity,"mdi:close","Zamknij");root.addView(close,box(ClimatePanelGeometry.CLOSE));close.style(t.raised,t.text,s);
        close.setOnClickListener(v->closeByUser());
        card(ClimatePanelGeometry.NOW_CARD,t);card(ClimatePanelGeometry.SET_CARD,t);
        label("W pokoju",ClimatePanelGeometry.NOW_LABEL,18,t.muted);label("Zadana",ClimatePanelGeometry.SET_LABEL,18,t.muted);
        nowValue=label("",ClimatePanelGeometry.NOW_VALUE,76,t.text);
        activity_=label("",ClimatePanelGeometry.ACTIVITY,20,t.muted);
        minus=new MusicOverlay.IconButton(activity,"mdi:minus","Zmniejsz temperaturę zadaną");root.addView(minus,box(ClimatePanelGeometry.MINUS));
        plus=new MusicOverlay.IconButton(activity,"mdi:plus","Zwiększ temperaturę zadaną");root.addView(plus,box(ClimatePanelGeometry.PLUS));
        square(minus,t);square(plus,t);
        minus.setOnClickListener(v->step(-1));plus.setOnClickListener(v->step(1));
        setValue=label("",ClimatePanelGeometry.SET_VALUE,64,t.text);setValue.setGravity(Gravity.CENTER);
        setStatus=label("",ClimatePanelGeometry.SET_STATUS,18,t.muted);
        modeLabel=label("Tryb",ClimatePanelGeometry.MODE_LABEL,16,t.muted);
        modeRow=row(ClimatePanelGeometry.MODES);listRow=row(ClimatePanelGeometry.LISTS);
        dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Box w=ClimatePanelGeometry.WINDOW;
        dialog.setContentView(root,new android.view.ViewGroup.LayoutParams(px(w.w),px(w.h)));
        Window win=dialog.getWindow();
        if(win!=null){
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            win.setGravity(Gravity.TOP|Gravity.START);win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp=win.getAttributes();lp.x=0;lp.y=px(w.y);lp.width=px(w.w);lp.height=px(w.h);win.setAttributes(lp);
        }
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d->closeByUser()); // back: first the picker (its own dialog), then this
        dialog.setOnDismissListener(d->stopTimers());
        refresh();
    }
    void show(){dialog.show();touched();}

    // --- state ---
    private ClimateModel model(){return new ClimateModel(host.state(),host.fahrenheit());}
    /** Re-renders from the latest HA state; also where a call in flight ends (HA shows the value, or 10 s passed). */
    void refresh(){
        ClimateModel m=model();
        long now=SystemClock.elapsedRealtime();
        flow.observe(m,now);
        Theme t=Theme.current();
        boolean usable=host.live()&&m.known;
        titleIcon.set(m.icon(),m.known&&m.working()?t.accent:t.muted);
        String why=!host.live()?"Brak połączenia z Home Assistant":!m.known?"Brak połączenia":flow.message();
        activity_.setText(!m.known?"Brak połączenia":m.actionWord()==null?"":m.actionWord());
        activity_.setTextColor(m.known&&m.working()?t.accent:t.muted);
        nowValue.setText(m.currentText());
        Double shown=flow.shown(m);
        setValue.setText(m.editable()&&shown!=null?ClimateModel.degrees(shown):m.setpoint());
        String status=flow.status();
        note.setText(why==null?"":why);
        setStatus.setText(status==null?"":status);
        boolean stepping=usable&&m.editable()&&flow.stepping()&&shown!=null;
        minus.setEnabled(stepping&&m.canStep(shown,-1));plus.setEnabled(stepping&&m.canStep(shown,1));
        modes(m,usable&&flow.mayCall());
        lists(m,usable&&flow.mayCall());
        if(flow.inFlight()!=null){main.removeCallbacks(confirmCheck);main.postDelayed(confirmCheck,Math.max(0,flow.confirmDue()-now));}
    }
    private void step(int direction){
        if(!host.live())return;
        if(flow.step(model(),direction,SystemClock.elapsedRealtime())){main.removeCallbacks(draftCheck);main.postDelayed(draftCheck,ClimateFlow.DRAFT_MS);}
        refresh();
    }
    private void draftDue(){
        Double d=flow.takeDraft(SystemClock.elapsedRealtime(),false);
        if(d!=null)send("set_temperature",d);
        refresh();
    }
    private void send(String service,Object value){
        String refused=host.send(service,value,error->main.post(()->{flow.result(error);refresh();}));
        if(refused==null)flow.sent(service,value,SystemClock.elapsedRealtime());
        else flow.refused(service,refused);
    }
    private void choose(String service,String value){
        if(!flow.mayCall())return;
        send(service,value);
        refresh();
    }

    // --- rows ---
    private void modes(ClimateModel m,boolean enabled){
        Theme t=Theme.current();
        boolean picker=m.modes.size()>6; // more than six do not fit as buttons (SPEC 0.19 pkt 5.2): one list button instead
        String key=m.modes+"|"+m.state+"|"+enabled+"|"+flow.inFlight();
        if(key.equals(modesKey))return;modesKey=key;
        modeRow.removeAllViews();
        modeLabel.setVisibility(m.modes.isEmpty()?View.GONE:View.VISIBLE);
        if(picker){
            List<String> labels=new ArrayList<>();for(String mode:m.modes)labels.add(ClimateModel.mode(mode,false));
            addSelector(modeRow,"Tryb",ClimateModel.mode(m.state,false),enabled,1,()->openPicker("Tryb",m.modes,labels,m.state,v->choose("set_hvac_mode",v)));
            return;
        }
        int n=m.modes.size();
        for(int i=0;i<n;i++){
            String mode=m.modes.get(i);
            boolean selected=mode.equals(m.state),sending="set_hvac_mode".equals(flow.inFlight())&&selected;
            TextView b=chip(ClimateModel.mode(mode,n>=5)+(sending?" …":""),selected,n>3?19:21,t);
            b.setEnabled(enabled);b.setAlpha(enabled?1f:.4f);
            b.setOnClickListener(v->{if(!mode.equals(model().state))choose("set_hvac_mode",mode);});
            add(modeRow,b,i,n);
        }
    }
    private void lists(ClimateModel m,boolean enabled){
        StringBuilder key=new StringBuilder(enabled+"|"+flow.inFlight());
        for(ClimateModel.Group g:m.groups)key.append('|').append(g.service).append('=').append(g.current).append(g.options);
        if(key.toString().equals(listsKey))return;listsKey=key.toString();
        listRow.removeAllViews();
        int n=m.groups.size();
        for(int i=0;i<n;i++){
            ClimateModel.Group g=m.groups.get(i);
            List<String> labels=new ArrayList<>();for(String o:g.options)labels.add(ClimateModel.option(g.label,o));
            String value=ClimateModel.option(g.label,g.current)+(g.service.equals(flow.inFlight())?" …":"");
            addSelector(listRow,g.label,value,enabled,n,()->openPicker(g.label,g.options,labels,g.current,v->choose(g.service,v)));
        }
    }
    private void addSelector(LinearLayout row,String label,String value,boolean enabled,int n,Runnable open){
        Theme t=Theme.current();
        FrameLayout b=new FrameLayout(activity);b.setBackground(fill(t.raised,false,t));
        LinearLayout col=new LinearLayout(activity);col.setOrientation(LinearLayout.VERTICAL);col.setPadding(px(16),px(12),px(44),0);
        TextView l=text(16,t.muted);l.setText(label);col.addView(l);
        TextView v=text(21,t.text);v.setText(value);v.setPadding(0,px(6),0,0);col.addView(v);
        b.addView(col,new FrameLayout.LayoutParams(-1,-1));
        IconView chevron=new IconView(activity);chevron.set("mdi:chevron-down",t.muted);
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(px(26),px(26),Gravity.END|Gravity.CENTER_VERTICAL);cp.rightMargin=px(14);b.addView(chevron,cp);
        b.setClickable(true);b.setEnabled(enabled);b.setAlpha(enabled?1f:.4f);
        b.setOnClickListener(x->{if(enabled)open.run();});
        add(row,b,row.getChildCount(),n);
    }
    private void add(LinearLayout row,View v,int i,int n){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);if(i<n-1)p.rightMargin=px(ClimatePanelGeometry.GAP);row.addView(v,p);
    }

    // --- picker ---
    private void openPicker(String title,List<String> values,List<String> labels,String current,Consumer<String> pick){
        if(picker!=null)picker.dismiss();
        Theme t=Theme.current();
        FrameLayout box=new FrameLayout(activity){@Override public boolean dispatchTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN)touched();return super.dispatchTouchEvent(e);}};
        box.setBackground(Theme.card(t.surface,Theme.RADIUS*s));
        TextView head=text(22,t.text);head.setText(title);head.setGravity(Gravity.CENTER_VERTICAL);box.addView(head,box(ClimatePanelGeometry.PICKER_TITLE,0,0));
        MusicOverlay.IconButton close=new MusicOverlay.IconButton(activity,"mdi:close","Zamknij listę");box.addView(close,box(ClimatePanelGeometry.PICKER_CLOSE,0,0));close.style(t.raised,t.text,s);
        ScrollView scroll=new ScrollView(activity);scroll.setVerticalScrollBarEnabled(values.size()>4);scroll.setScrollbarFadingEnabled(false);
        LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);
        box.addView(scroll,box(ClimatePanelGeometry.PICKER_LIST,0,0));
        Dialog d=new Dialog(activity);d.requestWindowFeature(Window.FEATURE_NO_TITLE);picker=d;
        for(int i=0;i<values.size();i++){
            String value=values.get(i);boolean selected=value.equals(current);
            FrameLayout row=new FrameLayout(activity);row.setBackground(fill(selected?selectedFill(t):t.raised,selected,t));
            TextView l=text(21,t.text);l.setText(labels.get(i));l.setGravity(Gravity.CENTER_VERTICAL);l.setPadding(px(20),0,px(52),0);row.addView(l,new FrameLayout.LayoutParams(-1,-1));
            if(selected){IconView check=new IconView(activity);check.set("mdi:check",t.text);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(px(30),px(30),Gravity.END|Gravity.CENTER_VERTICAL);cp.rightMargin=px(16);row.addView(check,cp);}
            row.setClickable(true);row.setOnClickListener(v->{d.dismiss();if(!value.equals(current))pick.accept(value);});
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,px(ClimatePanelGeometry.ROW));rp.bottomMargin=px(ClimatePanelGeometry.ROW_STEP-ClimatePanelGeometry.ROW);list.addView(row,rp);
        }
        close.setOnClickListener(v->d.dismiss());
        Box p=ClimatePanelGeometry.PICKER;
        d.setContentView(box,new android.view.ViewGroup.LayoutParams(px(p.w),px(p.h)));
        d.setCanceledOnTouchOutside(true); // outside and back close the list only
        d.setOnDismissListener(x->{if(picker==d)picker=null;});
        Window win=d.getWindow();
        if(win!=null){
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));win.setGravity(Gravity.TOP|Gravity.START);
            WindowManager.LayoutParams lp=win.getAttributes();lp.x=px(p.x);lp.y=px(p.y);lp.width=px(p.w);lp.height=px(p.h);win.setAttributes(lp);
        }
        d.show();
    }

    // --- closing ---
    /** X, back, two idle minutes: a draft the user left is sent now, then the panel goes (SPEC 0.19 pkt 5.1). */
    void closeByUser(){
        Double d=flow.takeDraft(SystemClock.elapsedRealtime(),true);
        if(d!=null)host.send("set_temperature",d,error->{}); // the panel is gone; MainActivity reports a failure itself
        onUserClose.run();
    }
    /** Set by MainActivity: forget this panel and dismiss it (its closePanel path, which never flushes). */
    Runnable onUserClose=this::dismiss;
    private void dismiss(){dialog.dismiss();}
    private void touched(){main.removeCallbacks(idle);main.postDelayed(idle,IDLE_MS);}
    private void stopTimers(){main.removeCallbacks(draftCheck);main.removeCallbacks(confirmCheck);main.removeCallbacks(idle);flow.discard();if(picker!=null){picker.dismiss();picker=null;}}

    // --- small view helpers ---
    private int px(float units){return Math.round(units*s);}
    private FrameLayout.LayoutParams box(Box b){return box(b,ClimatePanelGeometry.WINDOW.x,ClimatePanelGeometry.WINDOW.y);}
    private FrameLayout.LayoutParams box(Box b,int ox,int oy){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(px(b.w),px(b.h));p.leftMargin=px(b.x-ox);p.topMargin=px(b.y-oy);return p;}
    private TextView text(float size,int color){TextView v=new TextView(activity);v.setTextSize(TypedValue.COMPLEX_UNIT_PX,size*s);v.setTextColor(color);v.setMaxLines(1);v.setEllipsize(TextUtils.TruncateAt.END);v.setIncludeFontPadding(false);return v;}
    private TextView label(String value,Box b,float size,int color){TextView v=text(size,color);v.setText(value);v.setGravity(Gravity.CENTER_VERTICAL);root.addView(v,box(b));return v;}
    private void card(Box b,Theme t){View v=new View(activity);v.setBackground(Theme.card(t.surface,Theme.RADIUS*s));root.addView(v,box(b));}
    private LinearLayout row(Box b){LinearLayout r=new LinearLayout(activity);r.setOrientation(LinearLayout.HORIZONTAL);root.addView(r,box(b));return r;}
    private void square(MusicOverlay.IconButton b,Theme t){b.style(t.raised,t.text,s);GradientDrawable d=new GradientDrawable();d.setCornerRadius(18*s);d.setColor(t.raised);b.setBackground(d);}
    private TextView chip(String label,boolean selected,float size,Theme t){
        TextView b=text(size,t.text);b.setText(label);b.setGravity(Gravity.CENTER);b.setClickable(true);
        b.setBackground(fill(selected?selectedFill(t):t.raised,selected,t));return b;
    }
    /** Selection is neutral (SPEC 0.19 pkt 5): a lighter surface and an inside border, never the accent - that means "working". */
    private static int selectedFill(Theme t){return Theme.composite(t.text,.10f,t.raised);}
    private GradientDrawable fill(int color,boolean selected,Theme t){
        GradientDrawable d=new GradientDrawable();d.setCornerRadius(14*s);d.setColor(color);
        if(selected)d.setStroke(Math.max(1,Math.round(2*s)),Theme.composite(t.text,.6f,color));
        return d;
    }
}
