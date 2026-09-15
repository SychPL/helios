package pl.mateusz.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Local "recently played from Helios" list: newest first, at most ten, replaying an item moves it to the front. */
final class RecentPlays {
    static final int LIMIT=10;
    static final class Entry {
        final String uri,name,mediaType,imagePath,imageProvider;
        Entry(String uri,String name,String mediaType,String imagePath,String imageProvider){this.uri=uri;this.name=name;this.mediaType=mediaType;this.imagePath=imagePath;this.imageProvider=imageProvider;}
    }
    private final LinkedList<Entry> entries=new LinkedList<>();
    List<Entry> entries(){return Collections.unmodifiableList(new ArrayList<>(entries));}
    void add(Entry entry){
        entries.removeIf(e->e.uri.equals(entry.uri));
        entries.addFirst(entry);
        while(entries.size()>LIMIT)entries.removeLast();
    }
    String serialize(){
        JSONArray out=new JSONArray();
        try{for(Entry e:entries)out.put(new JSONObject().put("uri",e.uri).put("name",e.name).put("media_type",e.mediaType).put("image",e.imagePath==null?JSONObject.NULL:e.imagePath).put("image_provider",e.imageProvider==null?JSONObject.NULL:e.imageProvider));}
        catch(Exception ignored){}
        return out.toString();
    }
    static RecentPlays parse(String json){
        RecentPlays r=new RecentPlays();
        if(json==null)return r;
        try{
            JSONArray in=new JSONArray(json);
            for(int i=in.length()-1;i>=0;i--){JSONObject o=in.getJSONObject(i);r.add(new Entry(o.getString("uri"),o.getString("name"),o.optString("media_type","unknown"),o.isNull("image")?null:o.optString("image",null),o.isNull("image_provider")?null:o.optString("image_provider",null)));}
        }catch(Exception ignored){}
        return r;
    }
}
