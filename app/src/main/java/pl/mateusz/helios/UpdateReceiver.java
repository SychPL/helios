package pl.mateusz.helios;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Installer statuses (SPEC 0.10 pkt 7): a manifest receiver survives the process, so no result is lost; the state lives in prefs and in PackageInstaller. */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null||!Updater.AndroidHost.ACTION.equals(intent.getAction()))return;
        Updater updater=new Updater(new Updater.AndroidHost(context,text->Toast.makeText(context,text,Toast.LENGTH_LONG).show()),BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE);
        Intent confirm=intent.getParcelableExtra(Intent.EXTRA_INTENT);
        updater.onStatus(intent.getStringExtra("operation"),intent.getIntExtra(PackageInstaller.EXTRA_STATUS,-1),confirm,intent.getStringExtra("file"),intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    }
}
