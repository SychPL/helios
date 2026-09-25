package pl.mateusz.helios;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Page indicator above the cards: a row of dots, the current one full, the rest faint. Display only - never clickable, never focusable. */
final class PageDots extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int pages,current;

    PageDots(Context context){
        super(context);
        setClickable(false);setFocusable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);setAlpha(0f);
    }
    void set(int pages,int current){this.pages=pages;this.current=current;invalidate();}

    @Override protected void onDraw(Canvas canvas){
        if(pages<2)return;
        float r=getHeight()/2f,step=r*3.2f,x=getWidth()/2f-step*(pages-1)/2f,y=getHeight()/2f;
        int color=Theme.current().muted;
        for(int i=0;i<pages;i++){
            paint.setColor(color);paint.setAlpha(i==current?255:80);
            canvas.drawCircle(x+i*step,y,r,paint);
        }
    }
}
