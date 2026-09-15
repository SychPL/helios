import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.os.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Recover the exact existing Helios session and observe its installer callback. */
public class ResumeHeliosInstall {
    private static final String EXPECTED="a1a00900829b9451e7fcdb248b51482a8f967fb45f17dee83586aec2f03f9a2a";
    private static void log(Context ctx,String line){
        try(FileOutputStream out=new FileOutputStream(new File(ctx.getFilesDir(),"helios-install-resume.txt"),true)){
            out.write((System.currentTimeMillis()+" "+line+"\n").getBytes("UTF-8"));
        }catch(Exception ignored){}
    }
    public static String run(Context ctx,String arg) throws Exception {
        StringBuilder report=new StringBuilder();PackageInstaller installer=ctx.getPackageManager().getPackageInstaller();int matched=-1;
        for(PackageInstaller.SessionInfo info:installer.getMySessions()){
            try(PackageInstaller.Session session=installer.openSession(info.getSessionId())){
                for(String name:session.getNames()){
                    MessageDigest md=MessageDigest.getInstance("SHA-256");long total=0;
                    try(InputStream in=session.openRead(name)){byte[] bytes=new byte[16384];int n;while((n=in.read(bytes))!=-1){md.update(bytes,0,n);total+=n;}}
                    StringBuilder hash=new StringBuilder();for(byte b:md.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
                    boolean equal=EXPECTED.equals(hash.toString());report.append("session=").append(info.getSessionId()).append(" bytes=").append(total).append(" helios_match=").append(equal).append('\n');
                    if(equal)matched=info.getSessionId();
                }
            }catch(Exception e){report.append("session=").append(info.getSessionId()).append(" read_error=").append(e.getClass().getSimpleName()).append('\n');}
        }
        if(!"resume".equals(arg)||matched<0)return report.toString();
        final String action="pl.mateusz.clockadbprobe.HELIOS_INSTALL_"+UUID.randomUUID();
        BroadcastReceiver receiver=new BroadcastReceiver(){
            public void onReceive(Context context,Intent result){
                int status=result.getIntExtra(PackageInstaller.EXTRA_STATUS,999);
                log(context,"status="+status+" message="+result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
                    Intent confirmation=result.getParcelableExtra(Intent.EXTRA_INTENT);
                    if(confirmation==null){log(context,"missing_confirmation");return;}
                    try{confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(confirmation);log(context,"confirmation_requested component="+confirmation.getComponent());}
                    catch(Exception e){log(context,"confirmation_error="+e);}
                }
            }
        };
        ctx.registerReceiver(receiver,new IntentFilter(action));
        new Handler(Looper.getMainLooper()).postDelayed(()->{try{ctx.unregisterReceiver(receiver);}catch(Exception ignored){}},120000);
        PendingIntent callback=PendingIntent.getBroadcast(ctx,matched,new Intent(action).setPackage(ctx.getPackageName()),PendingIntent.FLAG_UPDATE_CURRENT);
        try(PackageInstaller.Session session=installer.openSession(matched)){session.commit(callback.getIntentSender());}
        log(ctx,"recommitted_existing_session="+matched);
        return report.append("RESUMED session=").append(matched).toString();
    }
}
