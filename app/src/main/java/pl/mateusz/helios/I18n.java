package pl.mateusz.helios;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

/** App-wide language choice; Polish source strings remain the fallback. */
final class I18n {
    private static volatile Translations catalogue=new Translations(Collections.emptyMap(),Collections.emptyMap());
    private static volatile String choice="pl";
    private I18n(){}

    static void init(Context context) {
        choice=context.getSharedPreferences("helios",Context.MODE_PRIVATE).getString("language","auto");
        try {
            catalogue=Translations.fromJson(read(context,"i18n/en.json"),read(context,"i18n/de.json"));
        } catch(Exception error) {
            android.util.Log.e("Helios", "Could not load translations", error);
        }
    }

    private static String read(Context context,String path) throws Exception {
        try(InputStream in=context.getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int count;
            while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }

    static String t(String polish) {return catalogue.text(choice,Locale.getDefault(),polish);}
    static String choice(){return choice;}
    static Locale locale() {return Translations.locale(choice,Locale.getDefault());}
    static void choose(Context context,String language) {
        if(!"auto".equals(language)&&!"pl".equals(language)&&!"en".equals(language)&&!"de".equals(language))
            throw new IllegalArgumentException("Unsupported language");
        context.getSharedPreferences("helios",Context.MODE_PRIVATE).edit().putString("language",language).apply();
        choice=language;
    }
}
