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

/** Fixed local menu defined in the app; never sourced from HA, always usable without it. */
final class NavigationMenu {
    private final Activity activity;
    private final java.util.function.Supplier<org.json.JSONObject> connection;
    private Dialog dialog;
    private String status="";
    interface Actions {void talk();void cancel();void pair();void device();void refresh();}
    private final Actions actions;
    NavigationMenu(Activity activity,java.util.function.Supplier<org.json.JSONObject> connection,Actions actions){this.activity=activity;this.connection=connection;this.actions=actions;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    void show(){
        if(dialog!=null&&dialog.isShowing())return;
        dialog=new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(12),dp(16),dp(12));panel.setBackgroundColor(Theme.current().surface);
        TextView title=Theme.label(activity,"Menu Heliosa",20,false);title.setPadding(dp(8),dp(6),0,dp(8));panel.addView(title);
        button(panel,"Zamknij menu",()->dialog.dismiss());
        ScrollView scroll=new ScrollView(activity);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);
        TextView diagnostics=Theme.label(activity,"",13,true);diagnostics.setPadding(dp(8),0,dp(8),dp(8));
        org.json.JSONObject cfg=connection.get();
        diagnostics.setText("Helios "+BuildConfig.VERSION_NAME+"\nHA: "+(cfg==null?"brak parowania":cfg.optString("url",""))+"\n"+status);rows.addView(diagnostics);
        button(rows,"Rozmowa z Nabu",()->{close();actions.talk();});
        button(rows,"Anuluj rozmowę",()->{close();actions.cancel();});
        button(rows,"Urządzenie: głośność i lampka",()->{close();actions.device();});
        button(rows,"Paruj z HA (kod)",()->{close();actions.pair();});
        button(rows,"Odśwież parowanie",()->{close();actions.refresh();});
        button(rows,"Konfiguracja ekranu w HA",()->openHa(true));
        button(rows,"Strona główna HA",()->openHa(false));
        button(rows,"Ustawienia zegara",()->open(new Intent(Settings.ACTION_SETTINGS)));
        button(rows,"Dostępność",()->open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button(rows,"Ekran główny",()->open(new Intent(Settings.ACTION_HOME_SETTINGS)));
        button(rows,"Aplikacje",this::apps);
        button(rows,"Aktualizacja Heliosa",this::update);
        dialog.setContentView(panel);
        Window window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.55f);
            window.setGravity(Gravity.RIGHT|Gravity.TOP);
            window.setWindowAnimations(pl.mateusz.helios.R.style.HeliosDrawerAnimation);
        }
        dialog.show();
        if(window!=null)window.setLayout(Math.min(dp(340),activity.getResources().getDisplayMetrics().widthPixels),WindowManager.LayoutParams.MATCH_PARENT);
    }
    /** Connection diagnostics shown inside the menu; safe to call at any time. */
    void status(String text){status=text==null?"":text;}
    private void button(LinearLayout parent,String label,Runnable action){
        Button b=Theme.button(activity,label,false,dp(16),dp(Theme.RADIUS));
        b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(dp(16),0,dp(12),0);
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
    private void update(){
        Uri provision=Uri.parse(BuildConfig.PROVISION_URL);
        if(provision.getScheme()==null||provision.getEncodedAuthority()==null){unavailable();return;}
        open(new Intent(Intent.ACTION_VIEW,new Uri.Builder().scheme(provision.getScheme()).encodedAuthority(provision.getEncodedAuthority()).path("/helios.apk").build()));
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
