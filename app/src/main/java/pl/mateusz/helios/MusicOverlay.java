package pl.mateusz.helios;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

/**
 * Local playback overlay above the grid (SPEC 0.6 pkt 5.3-5.4): a note handle at the right edge while a local session exists,
 * a right-half panel after a tap. Both consume touches so covered tiles never fire; the grid underneath is never rebuilt.
 */
final class MusicOverlay extends FrameLayout {
    interface Actions {void command(String command);void volume(int level);void mute(boolean muted);}
    static final int HANDLE=72;
    private final Button handle;
    private final LinearLayout panel;
    private final ImageView artwork;
    private final TextView title,artist,status;
    private final Button previous,playPause,next,stop,hide;
    private final SeekBar volume;
    private final CheckBox mute;
    private Actions actions;
    private HeliosService.MusicSnapshot snapshot;
    private boolean open,seeking;
    private int volumeOnPanel=-1;

    MusicOverlay(Context context,Typeface sans){
        super(context);
        setVisibility(GONE);
        handle=new Button(context);handle.setText("♪");handle.setTextSize(26);handle.setContentDescription("Muzyka: rozwiń panel");
        handle.setOnClickListener(v->{open=true;refresh();});addView(handle);
        panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);panel.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable shape=new GradientDrawable();shape.setColor(0xF2242C25);shape.setCornerRadius(12);panel.setBackground(shape);
        panel.setOnTouchListener((v,e)->true);addView(panel);
        artwork=new ImageView(context);artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);artwork.setBackgroundColor(0xFF30382D);artwork.setContentDescription("Okładka");panel.addView(artwork);
        title=text(context,sans,0xFFF1EFE6);artist=text(context,sans,0xFF9EA59B);status=text(context,sans,0xFF9EA59B);
        panel.addView(title);panel.addView(artist);panel.addView(status);
        LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER);panel.addView(row);
        previous=button(context,row,"◀◀","Poprzedni","previous");playPause=button(context,row,"▶","Odtwórz","play");next=button(context,row,"▶▶","Następny","next");stop=button(context,row,"■","Stop","stop");
        LinearLayout volumeRow=new LinearLayout(context);volumeRow.setOrientation(LinearLayout.HORIZONTAL);volumeRow.setGravity(Gravity.CENTER_VERTICAL);panel.addView(volumeRow);
        volume=new SeekBar(context);volume.setMax(100);volume.setContentDescription("Głośność muzyki");volumeRow.addView(volume,new LinearLayout.LayoutParams(0,-2,1));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean u){}
            public void onStartTrackingTouch(SeekBar s){seeking=true;}
            public void onStopTrackingTouch(SeekBar s){seeking=false;volumeOnPanel=s.getProgress();if(actions!=null)actions.volume(s.getProgress());}
        });
        mute=new CheckBox(context);mute.setText("Wycisz");mute.setTextColor(0xFFF1EFE6);mute.setOnClickListener(v->{if(actions!=null)actions.mute(mute.isChecked());});volumeRow.addView(mute);
        hide=new Button(context);hide.setText("Schowaj");hide.setAllCaps(false);hide.setContentDescription("Schowaj panel muzyki, muzyka gra dalej");hide.setOnClickListener(v->{open=false;refresh();});panel.addView(hide);
    }
    private TextView text(Context context,Typeface face,int color){TextView t=new TextView(context);t.setTypeface(face);t.setTextColor(color);t.setMaxLines(1);t.setEllipsize(TextUtils.TruncateAt.END);t.setGravity(Gravity.CENTER);return t;}
    private Button button(Context context,LinearLayout row,String symbol,String description,String command){
        Button b=new Button(context);b.setText(symbol);b.setContentDescription(description);b.setTextSize(22);b.setAllCaps(false);
        b.setOnClickListener(v->{if(actions!=null)actions.command(command.equals("play")&&snapshot!=null&&snapshot.ui==MusicSession.Ui.PLAYING?"pause":command);});
        row.addView(b);return b;
    }
    void setActions(Actions actions){this.actions=actions;}
    boolean isOpen(){return open&&getVisibility()==VISIBLE;}
    /** Hides the panel (library screen takes precedence); the handle stays while the session lasts. */
    void closePanel(){open=false;refresh();}
    void setSnapshot(HeliosService.MusicSnapshot snapshot){
        this.snapshot=snapshot;
        if(snapshot.ui==MusicSession.Ui.NONE)open=false;
        refresh();
    }
    private void refresh(){
        if(snapshot==null||snapshot.ui==MusicSession.Ui.NONE){setVisibility(GONE);return;}
        setVisibility(VISIBLE);
        handle.setVisibility(open?GONE:VISIBLE);panel.setVisibility(open?VISIBLE:GONE);
        if(!open)return;
        title.setText(snapshot.title==null?"—":snapshot.title);artist.setText(snapshot.artist==null?"":snapshot.artist);
        status.setText(snapshot.issue!=null?snapshot.issue:snapshot.ui==MusicSession.Ui.PAUSED?"Pauza":"Teraz gra");
        boolean playing=snapshot.ui==MusicSession.Ui.PLAYING;
        playPause.setText(playing?"❚❚":"▶");playPause.setContentDescription(playing?"Pauza":"Odtwórz");
        java.util.List<String> commands=snapshot.commands;
        previous.setEnabled(commands.contains("previous")||snapshot.maConnected);next.setEnabled(commands.contains("next")||snapshot.maConnected);
        playPause.setEnabled(commands.contains(playing?"pause":"play")||snapshot.maConnected);stop.setEnabled(commands.contains("stop")||snapshot.maConnected);
        volume.setEnabled(snapshot.maConnected);
        if(!seeking&&(volumeOnPanel<0||volumeOnPanel==snapshot.volume)){volume.setProgress(snapshot.volume);volumeOnPanel=-1;}
        mute.setChecked(snapshot.muted);
        if(snapshot.artwork!=null){try{artwork.setImageBitmap(BitmapFactory.decodeByteArray(snapshot.artwork,0,snapshot.artwork.length));}catch(Exception e){artwork.setImageBitmap(null);}}
        else artwork.setImageBitmap(null);
    }
    /** Geometry in 800x480 units: handle at the right edge, vertically centered under the bar; panel covers the right half. */
    void arrange(float s,float ox,float oy){
        int bar=DashboardView.BAR,height=DashboardView.HEIGHT,width=DashboardView.WIDTH;
        place(handle,width-HANDLE-DashboardView.GAP,bar+(height-bar-HANDLE)/2f,HANDLE,HANDLE,s,ox,oy);
        place(panel,width/2f,bar+DashboardView.GAP,width/2f-DashboardView.GAP,height-bar-2*DashboardView.GAP,s,ox,oy);
        int pad=Math.round(10*s);panel.setPadding(pad,pad,pad,pad);
        artwork.setLayoutParams(new LinearLayout.LayoutParams(Math.round(150*s),Math.round(150*s)));
        size(title,18,s);size(artist,14,s);size(status,13,s);
        for(Button b:new Button[]{previous,playPause,next,stop}){b.setLayoutParams(new LinearLayout.LayoutParams(Math.round(72*s),Math.round(56*s)));b.setTextSize(TypedValue.COMPLEX_UNIT_PX,20*s);}
        hide.setLayoutParams(new LinearLayout.LayoutParams(-1,Math.round(40*s)));
        handle.setTextSize(TypedValue.COMPLEX_UNIT_PX,26*s);
    }
    private static void place(View v,float x,float y,float w,float h,float s,float ox,float oy){LayoutParams p=new LayoutParams(Math.round(w*s),Math.round(h*s));p.leftMargin=Math.round(ox+x*s);p.topMargin=Math.round(oy+y*s);v.setLayoutParams(p);}
    private static void size(TextView v,float px,float s){v.setTextSize(TypedValue.COMPLEX_UNIT_PX,px*s);}
    @Override public boolean onTouchEvent(MotionEvent event){return open;} // touches on the open panel never reach the tiles underneath
}
