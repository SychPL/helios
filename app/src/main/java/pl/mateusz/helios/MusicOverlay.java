package pl.mateusz.helios;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import static pl.mateusz.helios.OverlayGeometry.*;

/**
 * Local playback overlay above the grid (SPEC 0.8a pkt 4): a note handle at the right edge while a local session exists,
 * an opaque right-half panel after a tap. Handle and panel consume only their own touches; the left half stays live.
 */
final class MusicOverlay extends FrameLayout {
    interface Actions {void command(String command);void seek(int seconds);void mute(boolean muted);}
    /** Round icon button: the drawn icon is smaller than the 72x72 hitbox. */
    static final class IconButton extends FrameLayout {
        final IconView icon;int fillColor;
        IconButton(Context context,String glyph,String description){
            super(context);icon=new IconView(context);addView(icon);setContentDescription(description);setClickable(true);setFocusable(true);icon.set(glyph,0);
        }
        void style(int fill,int ink,float s){fillColor=fill;GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(fill);setBackground(d);icon.set(icon.icon(),ink);
            int size=Math.round(32*s),inset=Math.round(20*s);LayoutParams p=new LayoutParams(size,size);p.leftMargin=p.topMargin=inset;icon.setLayoutParams(p);}
        void glyph(String glyph,int ink){icon.set(glyph,ink);}
        @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:.4f);}
    }
    private final FrameLayout handle,panel;
    private final IconView handleIcon,artworkPlaceholder,closeIcon;
    private final FrameLayout close;
    private final ImageView artwork;private android.graphics.Bitmap shownArtwork;
    private final TextView title,artist,status,seekValue;
    private final IconButton previous,playPause,next,stop,mute;
    private final LinearLayout seekRow;
    private final SeekBar seek;
    private Actions actions;
    private HeliosService.MusicSnapshot snapshot;
    private boolean open,seeking;
    private long pendingSeekUntil; // after a seek the bar shows the user's position until the server's next progress (or 10 s)
    private final Runnable ticker=new Runnable(){public void run(){if(open&&snapshot!=null&&snapshot.ui==MusicSession.Ui.PLAYING){showProgress();postDelayed(this,1000);}}};
    private float scale=1;
    private int shownAccent;

    MusicOverlay(Context context,Typeface sans){
        super(context);
        setVisibility(GONE);
        Theme t=Theme.current();
        handle=new FrameLayout(context);handle.setContentDescription("Muzyka: rozwiń panel");handle.setClickable(true);handle.setFocusable(true);
        handleIcon=new IconView(context);handle.addView(handleIcon);
        handle.setOnClickListener(v->{open=true;refresh();animateOpen();});addView(handle);
        panel=new FrameLayout(context);panel.setOnTouchListener((v,e)->true);addView(panel);
        status=text(context,sans,t.muted,2);panel.addView(status);
        close=new FrameLayout(context);close.setContentDescription("Schowaj panel muzyki, muzyka gra dalej");close.setClickable(true);close.setFocusable(true);
        closeIcon=new IconView(context);close.addView(closeIcon);close.setOnClickListener(v->{open=false;refresh();});panel.addView(close);
        artwork=new ImageView(context);artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);artwork.setContentDescription("Okładka");artwork.setClipToOutline(true);panel.addView(artwork);
        artworkPlaceholder=new IconView(context);panel.addView(artworkPlaceholder);
        title=text(context,sans,t.text,3);artist=text(context,sans,t.muted,2);panel.addView(title);panel.addView(artist);
        previous=button(context,"previous","Poprzedni","previous");playPause=button(context,"play","Odtwórz","play");next=button(context,"next","Następny","next");stop=button(context,"stop","Stop","stop");
        mute=new IconButton(context,"speaker","Wycisz");mute.setOnClickListener(v->{if(actions!=null&&snapshot!=null)actions.mute(!snapshot.muted);});panel.addView(mute);
        seekRow=new LinearLayout(context);seekRow.setOrientation(LinearLayout.HORIZONTAL);seekRow.setGravity(Gravity.CENTER_VERTICAL);panel.addView(seekRow);
        seek=new SeekBar(context);seek.setMax(1);seek.setContentDescription("Pozycja w utworze");seekRow.addView(seek,new LinearLayout.LayoutParams(0,-2,1));
        seekValue=text(context,sans,t.text,1);seekValue.setGravity(Gravity.CENTER);seekRow.addView(seekValue);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean u){if(u)seekValue.setText(clock(p*1000L)+" / "+clock(snapshot==null?-1:snapshot.durationMs));}
            public void onStartTrackingTouch(SeekBar s){seeking=true;}
            public void onStopTrackingTouch(SeekBar s){
                seeking=false;pendingSeekUntil=android.os.SystemClock.elapsedRealtime()+10_000; // same window as the MA request timeout
                if(actions!=null)actions.seek(s.getProgress());
            }
        });
        applyTheme();
    }
    private TextView text(Context context,Typeface face,int color,int lines){TextView t=new TextView(context);t.setTypeface(face);t.setTextColor(color);t.setMaxLines(lines);t.setEllipsize(TextUtils.TruncateAt.END);t.setIncludeFontPadding(false);return t;}
    private IconButton button(Context context,String glyph,String description,String command){
        IconButton b=new IconButton(context,glyph,description);
        b.setOnClickListener(v->{if(actions!=null)actions.command(command.equals("play")&&snapshot!=null&&snapshot.ui==MusicSession.Ui.PLAYING?"pause":command);});
        panel.addView(b);return b;
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
    /** Colours only; geometry is untouched, so a theme change never rebuilds anything while music plays. */
    void applyTheme(){
        Theme t=Theme.current();float s=scale;
        handle.setBackground(rounded(t.raised,Theme.RADIUS*s,true));handleIcon.set("note",t.accent);
        panel.setBackground(rounded(t.surface,Theme.RADIUS*s,false));
        closeIcon.set("chevron-right",t.muted);
        artwork.setBackground(Theme.card(t.raised,12*s));artworkPlaceholder.set("note",t.muted);
        title.setTextColor(t.text);artist.setTextColor(t.muted);status.setTextColor(t.muted);seekValue.setTextColor(t.text);Theme.tint(seek);
        for(IconButton b:new IconButton[]{previous,next,stop,mute})b.style(t.raised,t.text,s);
        shownAccent=0;refresh();
    }
    private static GradientDrawable rounded(int color,float r,boolean leftOnly){
        GradientDrawable d=new GradientDrawable();d.setColor(color);
        if(leftOnly)d.setCornerRadii(new float[]{r,r,0,0,0,0,r,r});else d.setCornerRadius(r);
        return d;
    }
    private void refresh(){
        if(snapshot==null||snapshot.ui==MusicSession.Ui.NONE){setVisibility(GONE);pendingSeekUntil=0;removeCallbacks(ticker);return;}
        setVisibility(VISIBLE);
        handle.setVisibility(open?GONE:VISIBLE);panel.setVisibility(open?VISIBLE:GONE);
        removeCallbacks(ticker);
        if(!open)return;
        if(snapshot.ui==MusicSession.Ui.PLAYING)postDelayed(ticker,1000);
        Theme t=Theme.current();
        String titleText=snapshot.title==null||snapshot.title.isEmpty()?"Nieznany utwór":snapshot.title;
        title.setText(titleText);title.setContentDescription(titleText);
        artist.setText(snapshot.artist==null?"":snapshot.artist);artist.setContentDescription(snapshot.artist==null?"":snapshot.artist);
        status.setText(snapshot.issue!=null?snapshot.issue:snapshot.ui==MusicSession.Ui.PAUSED?"Pauza":"Teraz gra");
        boolean playing=snapshot.ui==MusicSession.Ui.PLAYING;
        int accent=snapshot.accent!=0?snapshot.accent:t.accent;
        if(accent!=shownAccent||playPause.fillColor!=accent){shownAccent=accent;playPause.style(accent,t.onColor(accent),scale);}
        playPause.glyph(playing?"pause":"play",t.onColor(accent));playPause.setContentDescription(playing?"Pauza":"Odtwórz");
        java.util.List<String> commands=snapshot.commands;
        previous.setEnabled(commands.contains("previous")||snapshot.maConnected);next.setEnabled(commands.contains("next")||snapshot.maConnected);
        playPause.setEnabled(commands.contains(playing?"pause":"play")||snapshot.maConnected);stop.setEnabled(commands.contains("stop")||snapshot.maConnected);
        mute.setEnabled(snapshot.maConnected||snapshot.localConnected);
        if(snapshot.issue!=null&&snapshot.issue.startsWith("Przewijanie:"))pendingSeekUntil=0; // failed seek: back to the server's position
        showProgress();
        mute.glyph(snapshot.muted?"speaker-off":"speaker",t.text);mute.setContentDescription(snapshot.muted?"Wyłącz wyciszenie":"Wycisz");
        if(snapshot.artwork!=shownArtwork){shownArtwork=snapshot.artwork;artwork.setImageBitmap(shownArtwork);} // decoded once in the service; here only the reference changes
        artworkPlaceholder.setVisibility(shownArtwork==null?VISIBLE:GONE);
    }
    /** Position = last server progress plus the time elapsed since while playing; after a local seek the user's value stands until the server catches up. */
    private void showProgress(){
        if(snapshot==null)return;
        long now=android.os.SystemClock.elapsedRealtime();
        if(pendingSeekUntil!=0&&(now>=pendingSeekUntil||snapshot.progressAtMs>=pendingSeekUntil-10_000+500))pendingSeekUntil=0;
        boolean known=snapshot.durationMs>0;
        seek.setEnabled(known&&snapshot.maConnected);
        long position=snapshot.progressMs<0?-1:snapshot.progressMs+(snapshot.ui==MusicSession.Ui.PLAYING?now-snapshot.progressAtMs:0);
        if(known)seek.setMax((int)(snapshot.durationMs/1000));else{seek.setMax(1);}
        if(!seeking&&pendingSeekUntil==0){seek.setProgress(known&&position>=0?(int)Math.min(snapshot.durationMs,position)/1000:0);seekValue.setText(clock(position)+(known?" / "+clock(snapshot.durationMs):""));}
    }
    static String clock(long ms){if(ms<0)return "–:––";long s=ms/1000;return s/60+":"+(s%60<10?"0":"")+s%60;}
    /** Slides the existing panel surface in over ~180 ms; instant when the system animator scale is 0. */
    private void animateOpen(){
        float durationScale=Settings.Global.getFloat(getContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f);
        panel.animate().cancel();
        if(durationScale<=0){panel.setTranslationX(0);return;}
        panel.setTranslationX(panel.getLayoutParams().width);
        panel.animate().translationX(0).setDuration(Math.round(180*durationScale)).start();
    }
    /** Geometry from OverlayGeometry in 800x480 units; s = px per unit. */
    void arrange(float s,float ox,float oy){
        scale=s;
        place(handle,HANDLE.x,HANDLE.y,HANDLE.w,HANDLE.h,s,ox,oy);
        int hi=Math.round(36*s);LayoutParams hp=new LayoutParams(hi,hi,Gravity.CENTER);handleIcon.setLayoutParams(hp);
        place(panel,PANEL.x,PANEL.y,PANEL.w,PANEL.h,s,ox,oy);
        inside(status,STATUS,s);size(status,16,s);
        inside(close,CLOSE,s);int ci=Math.round(32*s);LayoutParams cp=new LayoutParams(ci,ci,Gravity.CENTER);closeIcon.setLayoutParams(cp);
        inside(artwork,ART,s);inside(artworkPlaceholder,new Box(ART.x+40,ART.y+40,64,64),s);
        inside(title,TITLE,s);size(title,23,s);inside(artist,ARTIST,s);size(artist,18,s);
        inside(previous,PREVIOUS,s);inside(playPause,PLAY,s);inside(next,NEXT,s);inside(stop,STOP,s);inside(mute,MUTE,s);
        inside(seekRow,SEEK,s);size(seekValue,16,s);seekValue.setLayoutParams(new LinearLayout.LayoutParams(Math.round(92*s),-2));
        applyTheme();
    }
    private static void place(View v,float x,float y,float w,float h,float s,float ox,float oy){LayoutParams p=new LayoutParams(Math.round(w*s),Math.round(h*s));p.leftMargin=Math.round(ox+x*s);p.topMargin=Math.round(oy+y*s);v.setLayoutParams(p);}
    private static void inside(View v,Box b,float s){LayoutParams p=new LayoutParams(Math.round(b.w*s),Math.round(b.h*s));p.leftMargin=Math.round(b.x*s);p.topMargin=Math.round(b.y*s);v.setLayoutParams(p);}
    private static void size(TextView v,float px,float s){v.setTextSize(TypedValue.COMPLEX_UNIT_PX,px*s);}
    @Override public boolean onTouchEvent(MotionEvent event){return false;} // only the handle and the panel consume; everything else falls through to the tiles
}
