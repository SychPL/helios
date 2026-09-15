package pl.mateusz.helios;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** All navigation originates from a user tap in the foreground Activity. */
final class NavigationMenu {
    private final Activity activity;
    private Dialog dialog;
    NavigationMenu(Activity activity){this.activity=activity;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    void show(){
        if(dialog!=null&&dialog.isShowing())return;
        dialog=new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(12),dp(16),dp(12));panel.setBackgroundColor(0xFF242C25);
        TextView title=new TextView(activity);title.setText("Helios · "+BuildConfig.VERSION_NAME);title.setTextSize(20);title.setTextColor(0xFFF1EFE6);title.setPadding(dp(8),dp(6),0,dp(8));panel.addView(title);
        button(panel,"Zamknij menu",()->dialog.dismiss());
        ScrollView scroll=new ScrollView(activity);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);
        button(rows,"Ustawienia zegara",()->open(new Intent(Settings.ACTION_SETTINGS)));
        button(rows,"Dostępność / TalkBack",()->open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button(rows,"Wybór ekranu głównego",()->open(new Intent(Settings.ACTION_HOME_SETTINGS)));
        button(rows,"Przeglądarka",()->open(new Intent(Intent.ACTION_VIEW,Uri.parse("http://192.168.1.212/"))));
        button(rows,"Pobierz aktualizację Heliosa",()->{
            Uri provision=Uri.parse(BuildConfig.PROVISION_URL);
            if(provision.getScheme()==null||provision.getEncodedAuthority()==null){unavailable();return;}
            Uri apk=new Uri.Builder().scheme(provision.getScheme()).encodedAuthority(provision.getEncodedAuthority()).path("/helios.apk").build();
            open(new Intent(Intent.ACTION_VIEW,apk));
        });
        button(rows,"Aplikacje i launchery",this::apps);
        button(rows,"Agent zegara",()->openPackage("pl.mateusz.clockadbprobe"));
        button(rows,"SSH",()->openPackage("org.galexander.sshd"));
        dialog.setContentView(panel);
        Window window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.55f);
            window.setGravity(Gravity.RIGHT|Gravity.TOP);
            window.setWindowAnimations(pl.mateusz.helios.R.style.HeliosDrawerAnimation);
            window.setLayout(Math.min(dp(340),activity.getResources().getDisplayMetrics().widthPixels),WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        if(window!=null)window.setLayout(Math.min(dp(340),activity.getResources().getDisplayMetrics().widthPixels),WindowManager.LayoutParams.MATCH_PARENT);
    }
    private void button(LinearLayout parent,String label,Runnable action){
        Button b=new Button(activity);b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(0xFFF1EFE6);
        b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(dp(12),0,dp(12),0);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF354035));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(54));params.bottomMargin=dp(4);parent.addView(b,params);
        b.setOnClickListener(v->action.run());
    }
    private void open(Intent intent){
        try{activity.startActivity(intent);if(dialog!=null)dialog.dismiss();}
        catch(android.content.ActivityNotFoundException|SecurityException error){unavailable();}
    }
    private void openPackage(String name){
        Intent intent=activity.getPackageManager().getLaunchIntentForPackage(name);
        if(intent==null)unavailable();else open(intent);
    }
    private void apps(){
        Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved=activity.getPackageManager().queryIntentActivities(query,0);
        List<ResolveInfo> entries=new ArrayList<>();
        for(ResolveInfo info:resolved)if(!info.activityInfo.packageName.equals(activity.getPackageName()))entries.add(info);
        entries.sort((a,b)->a.loadLabel(activity.getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(activity.getPackageManager()).toString()));
        if(entries.isEmpty()){unavailable();return;}
        String[] labels=new String[entries.size()];
        for(int i=0;i<labels.length;i++)labels[i]=entries.get(i).loadLabel(activity.getPackageManager()).toString();
        new AlertDialog.Builder(activity).setTitle("Otwórz aplikację").setItems(labels,(d,index)->{
            ResolveInfo selected=entries.get(index);
            open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(selected.activityInfo.packageName,selected.activityInfo.name));
        }).setNegativeButton("Wróć",null).show();
    }
    private void unavailable(){Toast.makeText(activity,"Zegar nie udostępnia tego ekranu lub aplikacji.",Toast.LENGTH_LONG).show();}
    void close(){if(dialog!=null){dialog.dismiss();dialog=null;}}
}
