package pl.mateusz.helios;

import android.app.Activity;
import android.app.Dialog;
import android.widget.*;
import java.util.*;

/** The `details` intent of a tile: the entity's current value plus one button per intent its domain allows. Buttons only ask; MainActivity confirms and calls. */
final class DetailsDialog {
    interface Runner {void run(ActionPolicy.Intent intent);}
    final Dialog dialog;
    private final DashboardSpec.Item item;
    private final TextView state;
    private final List<Button> actions=new ArrayList<>();private final List<ActionPolicy.Intent> intents=new ArrayList<>();
    DetailsDialog(Activity a,DashboardSpec.Item item,Map<String,EntityStates.Entity> states,boolean live,Runner runner){
        this.item=item;
        String domain=item.entity.substring(0,item.entity.indexOf('.'));
        dialog=new Dialog(a);dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout column=Theme.dialogColumn(a,20);column.setMinimumWidth(Theme.dp(a,360));
        EntityStates.Entity e=states.get(item.entity);
        String label=item.title!=null?item.title:e!=null&&e.attribute("friendly_name")!=null?e.attribute("friendly_name"):CardBodies.defaultLabel(item);
        TextView title=Theme.label(a,label,20,false);column.addView(title);
        state=Theme.label(a,"",18,true);state.setPadding(0,Theme.dp(a,4),0,Theme.dp(a,16));column.addView(state);
        LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);column.addView(row);
        for(ActionPolicy.Intent i:ActionPolicy.allowed(domain)){
            String verb=ActionPolicy.verb(i);if(verb==null)continue;
            Button b=Theme.button(a,verb,i!=ActionPolicy.Intent.CONTROLS,Theme.dp(a,16),Theme.dp(a,Theme.RADIUS));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,Theme.dp(a,56),1);p.rightMargin=Theme.dp(a,8);row.addView(b,p);
            b.setOnClickListener(v->runner.run(i));actions.add(b);intents.add(i);
        }
        Button close=Theme.button(a,"Zamknij",false,Theme.dp(a,17),Theme.dp(a,Theme.RADIUS));close.setOnClickListener(v->dialog.cancel());
        LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(-1,Theme.dp(a,56));c.topMargin=Theme.dp(a,actions.isEmpty()?0:12);column.addView(close,c);
        dialog.setContentView(column);dialog.setCanceledOnTouchOutside(true);
        if(dialog.getWindow()!=null)dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        refresh(states,live);
    }
    /** Re-reads the value from the latest snapshot; buttons work only on a live, known state. */
    void refresh(Map<String,EntityStates.Entity> states,boolean live){
        CardBodies.CardContent c=CardBodies.FOR.get("tile").render(item,states,live,null); // the tile body never touches the environment
        state.setText(c.value+(live?"":" (offline)"));
        EntityStates.Entity e=states.get(item.entity);
        for(int n=0;n<actions.size();n++)actions.get(n).setEnabled(live&&ActionPolicy.usable(ActionPolicy.name(intents.get(n)),e));
    }
}
