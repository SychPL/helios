package pl.mateusz.helios;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Line icons for the closed registry in DashboardSpec.ICONS plus the overlay's transport glyphs, drawn in code on a 32-unit grid. */
final class IconView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),fill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private String icon="information";
    private final Path path=new Path();
    IconView(Context context){super(context);paint.setStrokeWidth(1.8f);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);fill.setStyle(Paint.Style.FILL);}
    void set(String icon,int color){this.icon=icon;paint.setColor(color);fill.setColor(color);invalidate();}
    String icon(){return icon;}
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
                path.reset();path.moveTo(9,18);path.cubicTo(4,18,4,11,9,11);path.cubicTo(10,5,20,5,21,10);path.cubicTo(27,9,28,18,22,18);path.close();canvas.drawPath(path,paint);
                canvas.drawLine(11,22,9,27,paint);canvas.drawLine(16,22,14,27,paint);canvas.drawLine(21,22,19,27,paint);break;}
            case "music":
                canvas.drawCircle(10,24,4.5f,paint);canvas.drawCircle(24,21,4.5f,paint);canvas.drawLine(14.5f,24,14.5f,6,paint);canvas.drawLine(28.5f,21,28.5f,3,paint);canvas.drawLine(14.5f,6,28.5f,3,paint);canvas.drawLine(14.5f,10,28.5f,7,paint);break;
            case "note": // filled single note for the cover placeholder and the handle
                canvas.drawOval(6,19,17,27,fill);canvas.drawRect(14.5f,6,17,23,fill);path.reset();path.moveTo(14.5f,6);path.cubicTo(20,7,25,9,25,15);path.cubicTo(24,12,19,11,14.5f,11);path.close();canvas.drawPath(path,fill);break;
            case "play":
                path.reset();path.moveTo(10,6);path.lineTo(26,16);path.lineTo(10,26);path.close();canvas.drawPath(path,fill);break;
            case "pause":
                canvas.drawRect(8,6,13,26,fill);canvas.drawRect(19,6,24,26,fill);break;
            case "stop":
                canvas.drawRect(8,8,24,24,fill);break;
            case "next":
                path.reset();path.moveTo(6,7);path.lineTo(20,16);path.lineTo(6,25);path.close();canvas.drawPath(path,fill);canvas.drawRect(22,7,26,25,fill);break;
            case "previous":
                path.reset();path.moveTo(26,7);path.lineTo(12,16);path.lineTo(26,25);path.close();canvas.drawPath(path,fill);canvas.drawRect(6,7,10,25,fill);break;
            case "arrow-up":
                canvas.drawLine(16,26,16,7,paint);canvas.drawLine(8,15,16,7,paint);canvas.drawLine(24,15,16,7,paint);break;
            case "arrow-down":
                canvas.drawLine(16,6,16,25,paint);canvas.drawLine(8,17,16,25,paint);canvas.drawLine(24,17,16,25,paint);break;
            case "chevron-right":
                canvas.drawLine(12,8,20,16,paint);canvas.drawLine(20,16,12,24,paint);break;
            case "chevron-left":
                canvas.drawLine(20,8,12,16,paint);canvas.drawLine(12,16,20,24,paint);break;
            case "speaker":case "speaker-off":
                path.reset();path.moveTo(5,12);path.lineTo(10,12);path.lineTo(17,6);path.lineTo(17,26);path.lineTo(10,20);path.lineTo(5,20);path.close();canvas.drawPath(path,fill);
                if(icon.equals("speaker")){path.reset();path.addArc(14,8,26,24,-50,100);canvas.drawPath(path,paint);}
                else{canvas.drawLine(21,12,28,20,paint);canvas.drawLine(28,12,21,20,paint);}
                break;
            default:
                canvas.drawCircle(16,16,13,paint);canvas.drawLine(16,14,16,23,paint);canvas.drawCircle(16,9.5f,.9f,paint);break;
        }
        canvas.restore();
    }
}
