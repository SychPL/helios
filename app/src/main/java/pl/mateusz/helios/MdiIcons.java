package pl.mateusz.helios;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The bundled Material Design Icons catalogue: `mdi:<name>` -> codepoint in the packaged webfont (tools/prepare_mdi_assets.py). Pure; installed once per process before the first config parse. */
final class MdiIcons {
    static final String FILE="mdi-codepoints.txt",FONT="materialdesignicons-webfont.ttf";
    private static volatile MdiIcons installed;
    private final Map<String,Integer> codepoints;
    private MdiIcons(Map<String,Integer> codepoints){this.codepoints=Collections.unmodifiableMap(codepoints);}
    /** One `name HEX` per line; a malformed line is a broken asset, not a config error. */
    static MdiIcons load(InputStream in) throws IOException {
        Map<String,Integer> map=new HashMap<>(16384);
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            String line;
            while((line=reader.readLine())!=null){
                int space=line.indexOf(' ');
                if(space<=0)throw new IOException("mdi-codepoints: "+line);
                map.put(line.substring(0,space),Integer.parseInt(line.substring(space+1),16));
            }
        }
        return new MdiIcons(map);
    }
    static void install(MdiIcons icons){installed=icons;}
    /** Reads the asset once; a second call is a no-op. Fails loudly: a clock without the catalogue would reject every v6 layout. */
    static void install(android.content.Context context){
        if(installed!=null)return;
        try{install(load(context.getAssets().open(FILE)));}catch(IOException e){throw new IllegalStateException(e);}
    }
    /** null until installed - DashboardSpec then refuses `mdi:` icons instead of guessing. */
    static MdiIcons installed(){return installed;}
    /** "mdi:lightbulb" -> "lightbulb"; null for anything else. */
    static String name(String icon){return icon!=null&&icon.startsWith("mdi:")?icon.substring(4):null;}
    boolean has(String name){return codepoints.containsKey(name);}
    Integer codepoint(String name){return codepoints.get(name);}
    int size(){return codepoints.size();}
}
