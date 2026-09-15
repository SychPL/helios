import android.app.*;
import android.content.*;
import android.os.*;
public class ShowAgent {
    public static String run(Context ctx,String arg){
        NotificationManager manager=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        String channel="helios_open_agent";
        NotificationChannel c=new NotificationChannel(channel,"Otwórz agenta do instalacji Heliosa",NotificationManager.IMPORTANCE_HIGH);
        c.setSound(null,null);manager.createNotificationChannel(c);
        Intent launch=new Intent().setClassName(ctx,"pl.mateusz.clockadbprobe.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending=PendingIntent.getActivity(ctx,8757,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(ctx,channel).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Helios jest gotowy do instalacji").setContentText("Dotknij, aby otworzyć agenta")
                .setContentIntent(pending).setFullScreenIntent(pending,true).setAutoCancel(true).setTimeoutAfter(60000).build();
        manager.notify(8757,n);
        return "notification_posted enabled="+manager.areNotificationsEnabled()+" keyguard="+((KeyguardManager)ctx.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked();
    }
}
