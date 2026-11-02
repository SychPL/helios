package pl.mateusz.helios;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;
import java.util.Iterator;

/** Polish source text is the fallback when a locale or an entry is unavailable. */
final class Translations {
    private final Map<String,String> english,german;

    Translations(Map<String,String> english,Map<String,String> german) {
        this.english=Collections.unmodifiableMap(new HashMap<>(english));
        this.german=Collections.unmodifiableMap(new HashMap<>(german));
    }

    static Translations fromJson(String english,String german) throws Exception {
        return new Translations(parse(english),parse(german));
    }

    private static Map<String,String> parse(String json) throws Exception {
        JSONObject source=new JSONObject(json);
        Map<String,String> result=new HashMap<>();
        for(Iterator<String> keys=source.keys();keys.hasNext();) {
            String key=keys.next();result.put(key,source.getString(key));
        }
        return result;
    }

    static String effective(String choice,Locale system) {
        String language="auto".equals(choice)?system.getLanguage():choice;
        return "en".equals(language)||"de".equals(language)?language:"pl";
    }

    static Locale locale(String choice,Locale system) {
        switch(effective(choice,system)) {
            case "en":return Locale.UK;
            case "de":return Locale.GERMANY;
            default:return new Locale("pl","PL");
        }
    }

    static String nextChoice(String choice) {
        switch(choice) {
            case "auto":return "pl";
            case "pl":return "en";
            case "en":return "de";
            default:return "auto";
        }
    }

    String text(String choice,Locale system,String polish) {
        String language=effective(choice,system);
        String translated="en".equals(language)?english.get(polish):"de".equals(language)?german.get(polish):null;
        return translated==null?polish:translated;
    }
}
