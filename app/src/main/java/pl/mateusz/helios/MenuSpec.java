package pl.mateusz.helios;

import org.json.*;
import java.net.URI;
import java.util.*;

/** A visual HA button-card view is the only normal source of menu entries. */
final class MenuSpec {
    final String title;
    final List<Item> items;
    static final class Item {
        final String name,target;
        Item(String name,String target){this.name=name;this.target=target;}
    }
    private MenuSpec(String title,List<Item> items){this.title=title;this.items=Collections.unmodifiableList(items);}
    static JSONObject view(JSONObject document) throws Exception {
        JSONArray views=document.optJSONArray("views");if(views==null)return null;
        JSONObject found=null;
        for(int i=0;i<views.length();i++){
            JSONObject view=views.getJSONObject(i);
            if(view.optString("path").equals("menu-zegara")){
                if(found!=null)throw new IllegalArgumentException("Powtórzona zakładka menu-zegara");found=view;
            }
        }
        return found;
    }
    static MenuSpec parse(JSONObject view) throws Exception {
        String title=view.optString("title","Helios");if(title.isEmpty()||title.length()>60)throw new IllegalArgumentException("Nieprawidłowy tytuł menu");
        if(view.has("sections")||view.has("visible")||view.has("visibility"))throw new IllegalArgumentException("Menu wymaga zwykłego widoku kart bez warunków widoczności");
        List<Item> items=new ArrayList<>();walk(view.optJSONArray("cards"),items,0);return new MenuSpec(title,items);
    }
    private static void walk(JSONArray cards,List<Item> items,int depth) throws Exception {
        if(cards==null)return;
        if(depth>4||cards.length()>40)throw new IllegalArgumentException("Menu jest zbyt zagnieżdżone lub zbyt duże");
        for(int i=0;i<cards.length();i++){
            JSONObject card=cards.getJSONObject(i);String type=card.getString("type");
            if(card.has("visibility"))throw new IllegalArgumentException("Warunki widoczności menu nie są obsługiwane");
            if(type.equals("markdown"))continue;
            if(Arrays.asList("grid","vertical-stack","horizontal-stack").contains(type)){walk(card.getJSONArray("cards"),items,depth+1);continue;}
            if(!type.equals("button"))throw new IllegalArgumentException("Menu obsługuje karty Przycisk");
            String name=card.getString("name");if(name.trim().isEmpty()||name.length()>60)throw new IllegalArgumentException("Nieprawidłowa nazwa pozycji");
            JSONObject action=card.getJSONObject("tap_action");String kind=action.getString("action"),target;
            if(action.has("confirmation"))throw new IllegalArgumentException("Potwierdzenia akcji nie są obsługiwane");
            if(kind.equals("url"))target=action.getString("url_path");
            else if(kind.equals("navigate"))target=action.getString("navigation_path");
            else throw new IllegalArgumentException("Wybierz akcję URL lub Nawiguj");
            validateTarget(target);
            if(kind.equals("navigate")&&!target.startsWith("/"))throw new IllegalArgumentException("Nawigacja wymaga lokalnej ścieżki HA");
            items.add(new Item(name,target));if(items.size()>20)throw new IllegalArgumentException("Menu może mieć do 20 pozycji");
        }
    }
    static void validateTarget(String value) throws Exception {
        if(value.isEmpty()||value.length()>2048||value.contains("\\")||value.startsWith("//"))throw new IllegalArgumentException("Nieprawidłowy adres");
        URI uri=new URI(value);
        if(value.startsWith("/")){if(uri.getRawAuthority()!=null)throw new IllegalArgumentException("Nieprawidłowa ścieżka HA");return;}
        String scheme=uri.getScheme();
        if("http".equals(scheme)||"https".equals(scheme)){
            if(uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("Nieprawidłowy adres WWW");return;
        }
        if(!"helios".equals(scheme)||uri.getPort()!=-1||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Nieobsługiwana akcja");
        String host=uri.getHost(),path=uri.getPath();
        if("app".equals(host)){
            if(path==null||!path.matches("/[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))throw new IllegalArgumentException("Nieprawidłowy pakiet aplikacji");
        }else if(!Arrays.asList("settings","accessibility","home","dashboard","ha","update","apps","talk","cancel").contains(host)||(path!=null&&!path.isEmpty()&&!path.equals("/")))throw new IllegalArgumentException("Nieobsługiwana akcja Heliosa");
    }
}
