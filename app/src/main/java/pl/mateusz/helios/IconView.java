package pl.mateusz.helios;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Line icons for the closed registry in DashboardSpec.ICONS, drawn in code on a 32-unit grid. */
final class IconView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private String icon="information";
    private final Path cloud=new Path();
    IconView(Context context){super(context);paint.setStrokeWidth(1.8f);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
    void set(String icon,int color){this.icon=icon;paint.setColor(color);invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.save();canvas.scale(getWidth()/32f,getHeight()/32f);
        switch(icon){
            case "garage-open":
                canvas.drawLine(3,12,16,3,paint);canvas.drawLine(16,3,29,12,paint);canvas.drawLine(5,12,5,29,paint);canvas.drawLine(27,12,27,29,paint);
                canvas.drawLine(5,13,27,13,paint);canvas.drawLine(5,17,27,17,paint);canvas.drawLine(3,29,29,29,paint);break;
            case "lightbulb":
                canvas.drawCircle(16,12,8,paint);canvas.drawLine(12,20,12,25,paint);canvas.drawLine(20,20,20,25,paint);canvas.drawLine(12,25,20,25,paint);canvas.drawLine(14,29,18,29,paint);break;
            case "window-shutter":
                canvas.drawRect(5,4,27,28,paint);for(int y=8;y<=20;y+=4)canvas.drawLine(5,y,27,y,paint);canvas.drawLine(16,20,16,28,paint);break;
            case "weather-rainy":{
                cloud.reset();cloud.moveTo(9,18);cloud.cubicTo(4,18,4,11,9,11);cloud.cubicTo(10,5,20,5,21,10);cloud.cubicTo(27,9,28,18,22,18);cloud.close();canvas.drawPath(cloud,paint);
                canvas.drawLine(11,22,9,27,paint);canvas.drawLine(16,22,14,27,paint);canvas.drawLine(21,22,19,27,paint);break;}
            case "music":
                canvas.drawCircle(10,24,4.5f,paint);canvas.drawCircle(24,21,4.5f,paint);canvas.drawLine(14.5f,24,14.5f,6,paint);canvas.drawLine(28.5f,21,28.5f,3,paint);canvas.drawLine(14.5f,6,28.5f,3,paint);canvas.drawLine(14.5f,10,28.5f,7,paint);break;
            default:
                canvas.drawCircle(16,16,13,paint);canvas.drawLine(16,14,16,23,paint);canvas.drawCircle(16,9.5f,.9f,paint);break;
        }
        canvas.restore();
    }
}
