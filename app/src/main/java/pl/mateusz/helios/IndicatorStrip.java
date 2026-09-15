package pl.mateusz.helios;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;

final class IndicatorStrip extends LinearLayout {
    IndicatorStrip(Context context){super(context);setOrientation(HORIZONTAL);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    void render(DashboardSpec spec,Map<String,String> states,boolean live,String issue){
        removeAllViews();
        if(issue!=null){tile(issue,"alert",0xFFE6BD7B);return;}
        if(spec==null)return;
        for(DashboardSpec.Indicator indicator:spec.indicators){
            String state=states.get(indicator.entity),label=indicator.display(state,live);
            if(label!=null){boolean active=indicator.isActive(state,live);tile(label,active?indicator.icon:"alert",active?color(indicator.color):0xFFE6BD7B);}
        }
    }
    private static int color(String color){
        switch(color){case "red":return 0xFFFF998B;case "yellow":return 0xFFE5D77C;case "green":return 0xFFB6D19B;case "blue":return 0xFF99C6E2;default:return 0xFFF1BD7C;}
    }
    private void tile(String label,String icon,int color){
        LinearLayout tile=new LinearLayout(getContext());tile.setGravity(Gravity.CENTER_VERTICAL);tile.setPadding(dp(10),dp(4),dp(10),dp(4));
        GradientDrawable shape=new GradientDrawable();shape.setColor(0xFF30382D);shape.setCornerRadius(dp(8));tile.setBackground(shape);
        LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,-1,1);if(getChildCount()>0)cell.leftMargin=dp(8);addView(tile,cell);
        View symbol=new Symbol(getContext(),icon,color);tile.addView(symbol,new LinearLayout.LayoutParams(dp(32),dp(32)));
        TextView text=new TextView(getContext());text.setText(label);text.setTextSize(17);text.setTextColor(color);text.setMaxLines(2);text.setTypeface(Typeface.createFromAsset(getContext().getAssets(),"Geist.ttf"));
        LinearLayout.LayoutParams t=new LinearLayout.LayoutParams(0,-2,1);t.leftMargin=dp(10);tile.addView(text,t);tile.setContentDescription(label);
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    private static final class Symbol extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final String icon;
        Symbol(Context context,String icon,int color){super(context);this.icon=icon;paint.setColor(color);paint.setStrokeWidth(1.8f);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);canvas.save();canvas.scale(getWidth()/32f,getHeight()/32f);
            if(icon.equals("garage-open")){
                canvas.drawLine(3,12,16,3,paint);canvas.drawLine(16,3,29,12,paint);canvas.drawLine(5,12,5,29,paint);canvas.drawLine(27,12,27,29,paint);
                canvas.drawLine(5,13,27,13,paint);canvas.drawLine(5,17,27,17,paint);canvas.drawLine(3,29,29,29,paint);
            }else if(icon.equals("door-open")){
                canvas.drawRect(8,4,25,29,paint);Path p=new Path();p.moveTo(8,4);p.lineTo(19,8);p.lineTo(19,29);p.lineTo(8,29);canvas.drawPath(p,paint);canvas.drawCircle(16,18,.8f,paint);
            }else if(icon.equals("window-open")){
                canvas.drawRect(4,5,28,28,paint);canvas.drawLine(16,5,16,28,paint);canvas.drawLine(4,16,28,16,paint);canvas.drawLine(16,5,23,9,paint);canvas.drawLine(23,9,23,24,paint);canvas.drawLine(23,24,16,28,paint);
            }else if(icon.equals("lightbulb")){
                canvas.drawCircle(16,12,8,paint);canvas.drawLine(12,20,12,25,paint);canvas.drawLine(20,20,20,25,paint);canvas.drawLine(12,25,20,25,paint);canvas.drawLine(14,29,18,29,paint);
            }else{
                Path p=new Path();p.moveTo(16,3);p.lineTo(30,28);p.lineTo(2,28);p.close();canvas.drawPath(p,paint);canvas.drawLine(16,12,16,19,paint);canvas.drawCircle(16,24,.8f,paint);
            }
            canvas.restore();
        }
    }
}
