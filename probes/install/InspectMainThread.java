import android.content.Context;
import android.os.Looper;
public class InspectMainThread {
    public static String run(Context ctx,String arg){
        Thread main=Looper.getMainLooper().getThread();StringBuilder out=new StringBuilder("main_state="+main.getState()+"\n");
        for(StackTraceElement frame:main.getStackTrace())out.append(frame.toString()).append('\n');
        return out.toString();
    }
}
