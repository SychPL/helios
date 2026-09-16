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
 * Local playback overlay above the grid (SPEC 0.8a pkt 4, SPEC 0.11 pkt 3): a note handle at the right edge while a session
 * exists, an opaque right-half panel after a tap, and a full screen (cover left, clock/track/transport right) opened from the
 * panel. Handle and panel consume only their own touches; the full screen consumes everything below the bar.
 */
final class MusicOverlay extends FrameLayout {
    interface Actions {void command(String command);void seek(int seconds);void mute(boolean muted);}
    enum Layer {HANDLE,PANEL,FULL}
    /** Round (or pill) icon button: the drawn icon is smaller than the hitbox. */
    static final class IconButton extends FrameLayout {
        final IconView icon;int fillColor;
        IconButton(Context context,String glyph,String description){
            super(context);icon=new IconView(context);addView(icon);setContentDescription(description);setClickable(true);setFocusable(true);icon.set(glyph,0);
        }
        void style(int fill,int ink,float s){
            fillColor=fill;LayoutParams box=(LayoutParams)getLayoutParams();
            int w=box==null?Math.round(72*s):box.width,h=box==null?Math.round(72*s):box.height;
            GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.RECTANGLE);d.setCornerRadius(Math.min(w,h)/2f);d.setColor(fill);setBackground(d);icon.set(icon.icon(),ink);
            int size=Math.round(32*s);LayoutParams p=new LayoutParams(size,size);p.leftMargin=(w-size)/2;p.topMargin=(h-size)/2;icon.setLayoutParams(p);
        }
        void glyph(String glyph,int ink){icon.set(glyph,ink);}
        @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:.4f);}
    }
    /** One set of transport controls; the panel and the full screen each own one, both driven from the same snapshot. */
    private final class Controls {
        final IconButton previous,playPause,next,stop,mute;final SeekBar seek;
        Controls(Context context,FrameLayout parent){
            previous=button(context,parent,"previous","Poprzedni","previous");playPause=button(context,parent,"play","Odtwórz","play");
            next=button(context,parent,"next","Następny","next");stop=button(context,parent,"stop","Stop","stop");
            mute=new IconButton(context,"speaker","Wycisz");mute.setOnClickListener(v->{if(actions!=null&&snapshot!=null)actions.mute(!snapshot.muted);});parent.addView(mute);
            seek=new SeekBar(context);seek.setMax(1);seek.setContentDescription("Pozycja w utworze");bindSeek(seek);
        }
        void style(Theme t,float s){for(IconButton b:new IconButton[]{previous,next,stop,mute})b.style(t.raised,t.text,s);Theme.tint(seek);playPause.fillColor=0;}
        void refresh(Theme t,boolean playing,int accent,float s){
            if(playPause.fillColor!=accent)playPause.style(accent,t.onColor(accent),s);
            playPause.glyph(playing?"pause":"play",t.onColor(accent));playPause.setContentDescription(playing?"Pauza":"Odtwórz");
            java.util.List<String> commands=snapshot.commands;
            previous.setEnabled(commands.contains("previous")||snapshot.maConnected);next.setEnabled(commands.contains("next")||snapshot.maConnected);
            playPause.setEnabled(commands.contains(playing?"pause":"play")||snapshot.maConnected);stop.setEnabled(commands.contains("stop")||snapshot.maConnected);
            mute.setEnabled(snapshot.maConnected||snapshot.localConnected);
            mute.glyph(snapshot.muted?"speaker-off":"speaker",t.text);mute.setContentDescription(snapshot.muted?"Wyłącz wyciszenie":"Wycisz");
        }
    }
    private final FrameLayout handle,panel,full;
    private final IconView handleIcon,artworkPlaceholder,closeIcon,fullIcon,fullCloseIcon,fullPlaceholder;
    private final FrameLayout close,fullButton,fullClose;
    private final ImageView artwork,fullArtwork;private android.graphics.Bitmap shownArtwork;
    private final TextView title,artist,status,seekValue,fullClock,fullTitle,fullArtist,timeLeft,timeRight;
    private final Controls panelControls,fullControls;
    private final LinearLayout seekRow;
    private Actions actions;
    private HeliosService.MusicSnapshot snapshot;
    private Layer view=Layer.HANDLE;
    private boolean seeking;
    private long pendingSeekUntil; // after a seek the bar shows the user's position until the server's next progress (or 10 s)
    private final Runnable ticker=new Runnable(){public void run(){if(view!=Layer.HANDLE&&snapshot!=null&&snapshot.ui==MusicSession.Ui.PLAYING){showProgress();postDelayed(this,1000);}}};
    private float scale=1;
    private String clockText="";

    MusicOverlay(Context context,Typeface sans,Typeface mono){
        super(context);
        setVisibility(GONE);
        Theme t=Theme.current();
        handle=new FrameLayout(context);handle.setContentDescription("Muzyka: rozwiń panel");handle.setClickable(true);handle.setFocusable(true);
        handleIcon=new IconView(context);handle.addView(handleIcon);
        handle.setOnClickListener(v->{view=Layer.PANEL;refresh();animateOpen();});addView(handle);
        panel=new FrameLayout(context);panel.setOnTouchListener((v,e)->true);addView(panel);
        status=text(context,sans,t.muted,2);panel.addView(status);
        fullButton=new FrameLayout(context);fullButton.setContentDescription("Muzyka na pełnym ekranie");fullButton.setClickable(true);fullButton.setFocusable(true);
        fullIcon=new IconView(context);fullButton.addView(fullIcon);fullButton.setOnClickListener(v->{view=Layer.FULL;refresh();});panel.addView(fullButton);
        close=new FrameLayout(context);close.setContentDescription("Schowaj panel muzyki, muzyka gra dalej");close.setClickable(true);close.setFocusable(true);
        closeIcon=new IconView(context);close.addView(closeIcon);close.setOnClickListener(v->{view=Layer.HANDLE;refresh();});panel.addView(close);
        artwork=new ImageView(context);artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);artwork.setContentDescription("Okładka");artwork.setClipToOutline(true);panel.addView(artwork);
        artworkPlaceholder=new IconView(context);panel.addView(artworkPlaceholder);
        title=text(context,sans,t.text,3);artist=text(context,sans,t.muted,2);panel.addView(title);panel.addView(artist);
        panelControls=new Controls(context,panel);
        seekRow=new LinearLayout(context);seekRow.setOrientation(LinearLayout.HORIZONTAL);seekRow.setGravity(Gravity.CENTER_VERTICAL);panel.addView(seekRow);
        seekRow.addView(panelControls.seek,new LinearLayout.LayoutParams(0,-2,1));
        seekValue=text(context,sans,t.text,1);seekValue.setGravity(Gravity.CENTER);seekRow.addView(seekValue);
        // full screen (SPEC 0.11 pkt 3.1): third layer, consumes every touch below the bar
        full=new FrameLayout(context);full.setOnTouchListener((v,e)->true);addView(full);
        fullArtwork=new ImageView(context);fullArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);fullArtwork.setContentDescription("Okładka");fullArtwork.setClipToOutline(true);full.addView(fullArtwork);
        fullPlaceholder=new IconView(context);full.addView(fullPlaceholder);
        fullClose=new FrameLayout(context);fullClose.setContentDescription("Zamknij pełny ekran, muzyka gra dalej");fullClose.setClickable(true);fullClose.setFocusable(true);
        fullCloseIcon=new IconView(context);fullClose.addView(fullCloseIcon);fullClose.setOnClickListener(v->closeFullscreen());full.addView(fullClose);
        fullClock=text(context,mono,t.text,1);fullClock.setGravity(Gravity.CENTER_VERTICAL);full.addView(fullClock);
        fullTitle=text(context,sans,t.text,3);fullArtist=text(context,sans,t.muted,2);full.addView(fullTitle);full.addView(fullArtist);
        fullControls=new Controls(context,full);
        timeLeft=text(context,sans,t.muted,1);timeRight=text(context,sans,t.muted,1);timeRight.setGravity(Gravity.END);full.addView(timeLeft);full.addView(timeRight);
        full.addView(fullControls.seek);
        applyTheme();
    }
    private TextView text(Context context,Typeface face,int color,int lines){TextView t=new TextView(context);t.setTypeface(face);t.setTextColor(color);t.setMaxLines(lines);t.setEllipsize(TextUtils.TruncateAt.END);t.setIncludeFontPadding(false);return t;}
    private IconButton button(Context context,FrameLayout parent,String glyph,String description,String command){
        IconButton b=new IconButton(context,glyph,description);
        b.setOnClickListener(v->{if(actions!=null)actions.command(command.equals("play")&&snapshot!=null&&snapshot.ui==MusicSession.Ui.PLAYING?"pause":command);});
        parent.addView(b);return b;
    }
    /** Shared seek behaviour (SPEC 0.11 pkt 3.2): one MA request per release, the bar keeps the user's position until the server confirms. */
    private void bindSeek(SeekBar bar){
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean u){if(u)showTimes(p*1000L,snapshot==null?-1:snapshot.durationMs);}
            public void onStartTrackingTouch(SeekBar s){seeking=true;}
            public void onStopTrackingTouch(SeekBar s){
                seeking=false;pendingSeekUntil=android.os.SystemClock.elapsedRealtime()+10_000; // same window as the MA request timeout
                if(actions!=null)actions.seek(s.getProgress());
            }
        });
    }
    void setActions(Actions actions){this.actions=actions;}
    boolean isOpen(){return view!=Layer.HANDLE&&getVisibility()==VISIBLE;}
    Layer layer(){return view;}
    /** Hides the panel and the full screen (library screen takes precedence); the handle stays while the session lasts. */
    void closePanel(){view=Layer.HANDLE;refresh();}
    /** Back or the chevron: full screen returns to the panel. True when there was a full screen to close. */
    boolean closeFullscreen(){if(view!=Layer.FULL||getVisibility()!=VISIBLE)return false;view=Layer.PANEL;refresh();return true;}
    void setClock(String time){if(time.equals(clockText))return;clockText=time;fullClock.setText(time);fullClock.setContentDescription(time);}
    void setSnapshot(HeliosService.MusicSnapshot snapshot){
        this.snapshot=snapshot;
        if(snapshot.ui==MusicSession.Ui.NONE)view=Layer.HANDLE;
        refresh();
    }
    /** Colours only; geometry is untouched, so a theme change never rebuilds anything while music plays. */
    void applyTheme(){
        Theme t=Theme.current();float s=scale;
        handle.setBackground(rounded(t.raised,Theme.RADIUS*s,true));handleIcon.set("note",t.accent);
        panel.setBackground(rounded(t.surface,Theme.RADIUS*s,false));full.setBackgroundColor(t.surface);
        closeIcon.set("chevron-right",t.muted);fullIcon.set("fullscreen",t.muted);fullCloseIcon.set("chevron-down",t.muted);
        artwork.setBackground(Theme.card(t.raised,12*s));artworkPlaceholder.set("note",t.muted);
        fullArtwork.setBackground(Theme.card(t.raised,16*s));fullPlaceholder.set("note",t.muted);
        title.setTextColor(t.text);artist.setTextColor(t.muted);status.setTextColor(t.muted);seekValue.setTextColor(t.text);
        fullClock.setTextColor(t.text);fullTitle.setTextColor(t.text);fullArtist.setTextColor(t.muted);timeLeft.setTextColor(t.muted);timeRight.setTextColor(t.muted);
        panelControls.style(t,s);fullControls.style(t,s);
        refresh();
    }
    private static GradientDrawable rounded(int color,float r,boolean leftOnly){
        GradientDrawable d=new GradientDrawable();d.setColor(color);
        if(leftOnly)d.setCornerRadii(new float[]{r,r,0,0,0,0,r,r});else d.setCornerRadius(r);
        return d;
    }
    private void refresh(){
        if(snapshot==null||snapshot.ui==MusicSession.Ui.NONE){setVisibility(GONE);pendingSeekUntil=0;removeCallbacks(ticker);return;}
        setVisibility(VISIBLE);
        handle.setVisibility(view==Layer.HANDLE?VISIBLE:GONE);panel.setVisibility(view==Layer.PANEL?VISIBLE:GONE);full.setVisibility(view==Layer.FULL?VISIBLE:GONE);
        removeCallbacks(ticker);
        if(view==Layer.HANDLE)return;
        if(snapshot.ui==MusicSession.Ui.PLAYING)postDelayed(ticker,1000);
        Theme t=Theme.current();
        String titleText=snapshot.title==null||snapshot.title.isEmpty()?"Nieznany utwór":snapshot.title;
        String artistText=snapshot.artist==null?"":snapshot.artist;
        boolean playing=snapshot.ui==MusicSession.Ui.PLAYING;
        int accent=snapshot.accent!=0?snapshot.accent:t.accent;
        if(snapshot.issue!=null&&snapshot.issue.startsWith("Przewijanie:"))pendingSeekUntil=0; // failed seek: back to the server's position
        if(snapshot.artwork!=shownArtwork){shownArtwork=snapshot.artwork;artwork.setImageBitmap(shownArtwork);fullArtwork.setImageBitmap(shownArtwork);} // decoded once in the service; here only the reference changes
        if(view==Layer.PANEL){
            title.setText(titleText);title.setContentDescription(titleText);artist.setText(artistText);artist.setContentDescription(artistText);
            status.setText(snapshot.issue!=null?snapshot.issue:snapshot.ui==MusicSession.Ui.PAUSED?"Pauza":"Teraz gra");
            panelControls.refresh(t,playing,accent,scale);
            artworkPlaceholder.setVisibility(shownArtwork==null?VISIBLE:GONE);
        }else{
            fullTitle.setText(titleText);fullTitle.setContentDescription(titleText);
            String line=snapshot.issue!=null?snapshot.issue:artistText; // the issue takes the artist line, in accent (SPEC 0.11 pkt 3.2)
            fullArtist.setText(line);fullArtist.setContentDescription(line);fullArtist.setTextColor(snapshot.issue!=null?accent:t.muted);
            fullControls.refresh(t,playing,accent,scale);
            fullPlaceholder.setVisibility(shownArtwork==null?VISIBLE:GONE);
        }
        showProgress();
    }
    /** Position = last server progress plus the time elapsed since while playing; after a local seek the user's value stands until the server catches up. */
    private void showProgress(){
        if(snapshot==null)return;
        long now=android.os.SystemClock.elapsedRealtime();
        if(pendingSeekUntil!=0&&(now>=pendingSeekUntil||snapshot.progressAtMs>=pendingSeekUntil-10_000+500))pendingSeekUntil=0;
        boolean known=snapshot.durationMs>0;
        SeekBar seek=view==Layer.FULL?fullControls.seek:panelControls.seek;
        seek.setEnabled(known&&snapshot.maConnected);
        long position=snapshot.progressMs<0?-1:snapshot.progressMs+(snapshot.ui==MusicSession.Ui.PLAYING?now-snapshot.progressAtMs:0);
        seek.setMax(known?(int)(snapshot.durationMs/1000):1);
        if(!seeking&&pendingSeekUntil==0){seek.setProgress(known&&position>=0?(int)Math.min(snapshot.durationMs,position)/1000:0);showTimes(position,known?snapshot.durationMs:-1);}
    }
    private void showTimes(long position,long duration){
        seekValue.setText(clock(position)+(duration>0?" / "+clock(duration):""));
        timeLeft.setText(clock(position));timeRight.setText(duration>0?clock(duration):"");
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
    /** Geometry from OverlayGeometry and FullscreenGeometry in 800x480 units; s = px per unit. */
    void arrange(float s,float ox,float oy){
        scale=s;
        place(handle,HANDLE.x,HANDLE.y,HANDLE.w,HANDLE.h,s,ox,oy);
        int hi=Math.round(36*s);handleIcon.setLayoutParams(new LayoutParams(hi,hi,Gravity.CENTER));
        place(panel,PANEL.x,PANEL.y,PANEL.w,PANEL.h,s,ox,oy);
        inside(status,STATUS,s);size(status,16,s);
        int ci=Math.round(32*s);
        inside(fullButton,FULL,s);fullIcon.setLayoutParams(new LayoutParams(ci,ci,Gravity.CENTER));
        inside(close,CLOSE,s);closeIcon.setLayoutParams(new LayoutParams(ci,ci,Gravity.CENTER));
        inside(artwork,ART,s);inside(artworkPlaceholder,new Box(ART.x+40,ART.y+40,64,64),s);
        inside(title,TITLE,s);size(title,23,s);inside(artist,ARTIST,s);size(artist,18,s);
        inside(panelControls.previous,PREVIOUS,s);inside(panelControls.playPause,PLAY,s);inside(panelControls.next,NEXT,s);inside(panelControls.stop,STOP,s);inside(panelControls.mute,MUTE,s);
        inside(seekRow,SEEK,s);size(seekValue,16,s);seekValue.setLayoutParams(new LinearLayout.LayoutParams(Math.round(92*s),-2));
        place(full,FullscreenGeometry.AREA.x,FullscreenGeometry.AREA.y,FullscreenGeometry.AREA.w,FullscreenGeometry.AREA.h,s,ox,oy);
        inside(fullArtwork,FullscreenGeometry.ART,s);inside(fullPlaceholder,new Box(FullscreenGeometry.ART.x+136,FullscreenGeometry.ART.y+136,128,128),s);
        inside(fullClose,FullscreenGeometry.CLOSE,s);fullCloseIcon.setLayoutParams(new LayoutParams(ci,ci,Gravity.CENTER));
        inside(fullClock,FullscreenGeometry.CLOCK,s);size(fullClock,72,s);
        inside(fullTitle,FullscreenGeometry.TITLE,s);size(fullTitle,26,s);inside(fullArtist,FullscreenGeometry.ARTIST,s);size(fullArtist,20,s);
        inside(fullControls.previous,FullscreenGeometry.PREVIOUS,s);inside(fullControls.playPause,FullscreenGeometry.PLAY,s);inside(fullControls.next,FullscreenGeometry.NEXT,s);inside(fullControls.stop,FullscreenGeometry.STOP,s);inside(fullControls.mute,FullscreenGeometry.MUTE,s);
        inside(timeLeft,FullscreenGeometry.TIME_LEFT,s);size(timeLeft,16,s);inside(timeRight,FullscreenGeometry.TIME_RIGHT,s);size(timeRight,16,s);
        inside(fullControls.seek,FullscreenGeometry.SEEK,s);
        applyTheme();
    }
    private static void place(View v,float x,float y,float w,float h,float s,float ox,float oy){LayoutParams p=new LayoutParams(Math.round(w*s),Math.round(h*s));p.leftMargin=Math.round(ox+x*s);p.topMargin=Math.round(oy+y*s);v.setLayoutParams(p);}
    private static void inside(View v,Box b,float s){LayoutParams p=new LayoutParams(Math.round(b.w*s),Math.round(b.h*s));p.leftMargin=Math.round(b.x*s);p.topMargin=Math.round(b.y*s);v.setLayoutParams(p);}
    private static void size(TextView v,float px,float s){v.setTextSize(TypedValue.COMPLEX_UNIT_PX,px*s);}
    @Override public boolean onTouchEvent(MotionEvent event){return false;} // only the handle, the panel and the full screen consume; everything else falls through to the tiles
}
