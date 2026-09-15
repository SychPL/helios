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
    private final java.util.function.Supplier<org.json.JSONObject> connection;
    private Dialog dialog;
    private MenuSpec spec;
    private String issue;
    private final Runnable talk,cancel;
    NavigationMenu(Activity activity,java.util.function.Supplier<org.json.JSONObject> connection,Runnable talk,Runnable cancel){this.activity=activity;this.connection=connection;this.talk=talk;this.cancel=cancel;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    void show(){
        if(dialog!=null&&dialog.isShowing())return;
        dialog=new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(12),dp(16),dp(12));panel.setBackgroundColor(0xFF242C25);
        TextView title=new TextView(activity);title.setText(spec==null?"Menu odzyskiwania":spec.title);title.setTextSize(20);title.setTextColor(0xFFF1EFE6);title.setPadding(dp(8),dp(6),0,dp(8));panel.addView(title);
        button(panel,"Zamknij menu",()->dialog.dismiss());
        ScrollView scroll=new ScrollView(activity);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);
        if(issue!=null){TextView error=new TextView(activity);error.setText(issue);error.setTextColor(0xFFE6BD7B);rows.addView(error);}
        if(spec==null){
            button(rows,"Ustawienia zegara",()->execute("helios://settings"));
            button(rows,"Konfiguracja w HA",()->execute("helios://dashboard"));
        }else{
            for(MenuSpec.Item item:spec.items)button(rows,item.name,()->execute(item.target));
            if(spec.items.isEmpty()){TextView empty=new TextView(activity);empty.setText("Menu jest puste. Dodaj przyciski w HA.");empty.setTextColor(0xFFF1EFE6);rows.addView(empty);}
        }
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
    void configure(MenuSpec spec){this.spec=spec;issue=null;refresh();}
    void error(String message){issue=message;refresh();}
    private void refresh(){if(dialog!=null&&dialog.isShowing()){close();show();}}
    private void execute(String target){
        try{MenuSpec.validateTarget(target);}catch(Exception error){unavailable();return;}
        if(target.startsWith("/")){
            org.json.JSONObject cfg=connection.get();if(cfg==null){unavailable();return;}
            open(new Intent(Intent.ACTION_VIEW,Uri.parse(cfg.optString("url","").replaceAll("/$","")+target)));return;
        }
        Uri uri=Uri.parse(target);
        if(!"helios".equals(uri.getScheme())){open(new Intent(Intent.ACTION_VIEW,uri));return;}
        switch(uri.getHost()){
            case "settings":open(new Intent(Settings.ACTION_SETTINGS));break;
            case "accessibility":open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));break;
            case "home":open(new Intent(Settings.ACTION_HOME_SETTINGS));break;
            case "dashboard":openHa(true);break;
            case "ha":openHa(false);break;
            case "apps":apps();break;
            case "app":openPackage(uri.getPath().substring(1));break;
            case "talk":close();talk.run();break;
            case "cancel":close();cancel.run();break;
            case "update":
                Uri provision=Uri.parse(BuildConfig.PROVISION_URL);
                if(provision.getScheme()==null||provision.getEncodedAuthority()==null){unavailable();return;}
                Uri apk=new Uri.Builder().scheme(provision.getScheme()).encodedAuthority(provision.getEncodedAuthority()).path("/helios.apk").build();
                open(new Intent(Intent.ACTION_VIEW,apk));break;
            default:unavailable();
        }
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
    private void openHa(boolean dashboard){
        org.json.JSONObject config=connection.get();if(config==null){unavailable();return;}
        String base=config.optString("url","").replaceAll("/$","");
        String path=dashboard?"/"+config.optString("dashboard_path","helios-clock"):"/";
        open(new Intent(Intent.ACTION_VIEW,Uri.parse(base+path)));
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
