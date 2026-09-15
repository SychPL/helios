package pl.mateusz.helios;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/**
 * Library and remote control screen opened from the music tile (SPEC 0.6 pkt 5.2): player choice, search, recent plays,
 * results and the selected player's transport. Everything goes through the MA API; closing never stops music.
 */
final class MusicLibraryDialog {
    private final Activity activity;
    private final HeliosService service;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private final List<JSONObject> players=new ArrayList<>();
    private String selectedPlayerId,selectedQueueId;
    private final List<RecentPlays.Entry> results=new ArrayList<>();
    private ArrayAdapter<String> resultsAdapter,playersAdapter;
    private TextView nowPlaying,status;
    private EditText query;
    private Spinner playerSpinner;
    private boolean showingRecent=true;

    MusicLibraryDialog(Activity activity,HeliosService service){this.activity=activity;this.service=service;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    boolean isShowing(){return dialog!=null&&dialog.isShowing();}
    void close(){if(dialog!=null){dialog.dismiss();dialog=null;}}

    void show(){
        dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xFF1B201D);root.setPadding(dp(10),dp(8),dp(10),dp(8));
        LinearLayout top=new LinearLayout(activity);top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);root.addView(top);
        playerSpinner=new Spinner(activity);playersAdapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new ArrayList<>());playerSpinner.setAdapter(playersAdapter);
        top.addView(playerSpinner,new LinearLayout.LayoutParams(0,dp(44),1));
        playerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){if(position<players.size())selectPlayer(players.get(position));}
            public void onNothingSelected(AdapterView<?> parent){}
        });
        Button close=new Button(activity);close.setText("Zamknij");close.setAllCaps(false);close.setOnClickListener(v->close());top.addView(close,new LinearLayout.LayoutParams(dp(110),dp(44)));
        LinearLayout searchRow=new LinearLayout(activity);searchRow.setOrientation(LinearLayout.HORIZONTAL);root.addView(searchRow);
        query=new EditText(activity);query.setHint("Szukaj w bibliotece (2-100 znaków)");query.setTextColor(0xFFF1EFE6);query.setHintTextColor(0xFF9EA59B);query.setSingleLine(true);query.setInputType(InputType.TYPE_CLASS_TEXT);query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v,actionId,event)->{search();return true;});
        searchRow.addView(query,new LinearLayout.LayoutParams(0,dp(44),1));
        Button go=new Button(activity);go.setText("Szukaj");go.setAllCaps(false);go.setOnClickListener(v->search());searchRow.addView(go,new LinearLayout.LayoutParams(dp(110),dp(44)));
        status=new TextView(activity);status.setTextColor(0xFF9EA59B);status.setText("Ostatnio odtwarzane");root.addView(status);
        ListView list=new ListView(activity);resultsAdapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,new ArrayList<>());list.setAdapter(resultsAdapter);
        list.setOnItemClickListener((parent,view,position,id)->{if(position<results.size())play(results.get(position));});
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bottom=new LinearLayout(activity);bottom.setOrientation(LinearLayout.HORIZONTAL);bottom.setGravity(Gravity.CENTER_VERTICAL);root.addView(bottom);
        nowPlaying=new TextView(activity);nowPlaying.setTextColor(0xFFF1EFE6);nowPlaying.setMaxLines(1);bottom.addView(nowPlaying,new LinearLayout.LayoutParams(0,-2,1));
        for(String[] b:new String[][]{{"◀◀","previous"},{"▶","play"},{"❚❚","pause"},{"▶▶","next"},{"■","stop"}}){
            Button button=new Button(activity);button.setText(b[0]);button.setTextSize(18);button.setContentDescription(b[1]);
            button.setOnClickListener(v->command(b[1]));bottom.addView(button,new LinearLayout.LayoutParams(dp(56),dp(48)));
        }
        SeekBar volume=new SeekBar(activity);volume.setMax(100);volume.setContentDescription("Głośność wybranego gracza");bottom.addView(volume,new LinearLayout.LayoutParams(dp(140),dp(40)));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean u){}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){MusicAssistantClient ma=service.ma();if(ma!=null&&selectedPlayerId!=null)ma.volume(selectedPlayerId,s.getProgress(),r->{},e->toast(e));}
        });
        dialog.setContentView(root);
        Window window=dialog.getWindow();
        if(window!=null){window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);}
        dialog.setOnDismissListener(d->hideKeyboard());
        dialog.show();
        showRecent();loadPlayers();
    }
    private void toast(String text){main.post(()->Toast.makeText(activity,text,Toast.LENGTH_SHORT).show());}
    private void hideKeyboard(){
        InputMethodManager imm=(InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if(imm!=null&&query!=null)imm.hideSoftInputFromWindow(query.getWindowToken(),0);
    }
    private void loadPlayers(){
        MusicAssistantClient ma=service.ma();
        if(ma==null||!ma.connected()){status.setText("Brak połączenia z Music Assistant");return;}
        ma.players(list->main.post(()->{
            players.clear();playersAdapter.clear();int select=0;
            for(int i=0;i<list.length();i++){
                JSONObject p=list.optJSONObject(i);if(p==null||!p.optBoolean("available",true)||p.optBoolean("hide_in_ui",false))continue;
                players.add(p);playersAdapter.add(p.optString("name","?"));
                if(p.optString("player_id").equals(service.lenovoPlayerId()))select=players.size()-1;
            }
            playersAdapter.notifyDataSetChanged();
            if(!players.isEmpty()){playerSpinner.setSelection(select);selectPlayer(players.get(select));}
        }),e->toast(e));
    }
    private void selectPlayer(JSONObject player){
        selectedPlayerId=player.optString("player_id");selectedQueueId=player.optString("active_group","").isEmpty()?selectedPlayerId:player.optString("active_group");
        JSONObject media=player.optJSONObject("current_media");
        nowPlaying.setText(player.optString("name","")+": "+(media==null||media.isNull("title")?"—":media.optString("title","")));
    }
    private void showRecent(){
        showingRecent=true;results.clear();resultsAdapter.clear();
        for(RecentPlays.Entry e:service.recent().entries()){results.add(e);resultsAdapter.add(label(e));}
        status.setText(results.isEmpty()?"Ostatnio odtwarzane: pusto":"Ostatnio odtwarzane");resultsAdapter.notifyDataSetChanged();
    }
    private static String label(RecentPlays.Entry e){
        switch(e.mediaType){case "album":return "Album: "+e.name;case "playlist":return "Playlista: "+e.name;case "radio":return "Radio: "+e.name;default:return e.name;}
    }
    private void search(){
        MusicAssistantClient ma=service.ma();
        if(ma==null){toast("Brak połączenia z Music Assistant");return;}
        hideKeyboard();status.setText("Szukam…");
        ma.search(query.getText().toString(),found->main.post(()->{
            showingRecent=false;results.clear();resultsAdapter.clear();
            for(String type:new String[]{"tracks","albums","playlists","radio"}){
                JSONArray items=found.optJSONArray(type);if(items==null)continue;
                for(int i=0;i<items.length();i++){
                    JSONObject item=items.optJSONObject(i);if(item==null||item.isNull("uri"))continue;
                    String mediaType=item.optString("media_type",type.replaceAll("s$",""));
                    JSONObject image=item.optJSONObject("image");
                    results.add(new RecentPlays.Entry(item.optString("uri"),item.optString("name","?")+artists(item),mediaType,image==null?null:image.optString("path",null),image==null?null:image.optString("provider",null)));
                    resultsAdapter.add(label(results.get(results.size()-1)));
                }
            }
            status.setText(results.isEmpty()?"Brak wyników":"Wyniki: "+results.size());resultsAdapter.notifyDataSetChanged();
        }),e->main.post(()->{if(!"superseded".equals(e))status.setText(e);}));
    }
    private static String artists(JSONObject item){
        JSONArray artists=item.optJSONArray("artists");if(artists==null||artists.length()==0)return "";
        JSONObject first=artists.optJSONObject(0);return first==null?"":" · "+first.optString("name","");
    }
    private void play(RecentPlays.Entry entry){
        MusicAssistantClient ma=service.ma();
        if(ma==null||selectedQueueId==null){toast("Wybierz odtwarzacz");return;}
        boolean replace=!entry.mediaType.equals("track");
        ma.playMedia(selectedQueueId,entry.uri,replace,r->main.post(()->{service.rememberPlay(entry);if(showingRecent)showRecent();status.setText("Odtwarzanie: "+entry.name);}),e->toast(e));
    }
    private void command(String command){
        MusicAssistantClient ma=service.ma();
        if(ma==null||selectedPlayerId==null){toast("Wybierz odtwarzacz");return;}
        ma.playerCommand(selectedPlayerId,command,r->{},e->toast(e));
    }
}
