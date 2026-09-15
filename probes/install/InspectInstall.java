import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInstaller;
import android.provider.Settings;
public class InspectInstall {
    public static String run(Context ctx,String arg){
        StringBuilder out=new StringBuilder();
        ActivityManager manager=(ActivityManager)ctx.getSystemService(Context.ACTIVITY_SERVICE);
        out.append("lockTaskMode=").append(manager.getLockTaskModeState()).append(" overlay=").append(Settings.canDrawOverlays(ctx)).append(" packageInstalls=").append(ctx.getPackageManager().canRequestPackageInstalls()).append('\n');
        for(ActivityManager.AppTask task:manager.getAppTasks()){
            ActivityManager.RecentTaskInfo t=task.getTaskInfo();out.append("task id=").append(t.id).append(" base=").append(t.baseActivity).append(" top=").append(t.topActivity).append(" intent=").append(t.baseIntent).append('\n');
        }
        for(PackageInstaller.SessionInfo s:ctx.getPackageManager().getPackageInstaller().getMySessions()){
            out.append("session=").append(s.getSessionId()).append(" created=").append(s.getCreatedMillis()).append(" package=").append(s.getAppPackageName()).append(" active=").append(s.isActive()).append(" sealed=").append(s.isSealed()).append(" progress=").append(s.getProgress()).append('\n');
        }
        return out.toString();
    }
}
