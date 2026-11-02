package pl.mateusz.helios;

import org.junit.Test;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import static org.junit.Assert.assertEquals;

public class TranslationsTest {
    private static Translations sample() {
        Map<String,String> en=new HashMap<>(),de=new HashMap<>();
        en.put("Zamknij", "Close");
        de.put("Zamknij", "Schließen");
        return new Translations(en,de);
    }

    @Test public void manualChoiceOverridesSystemLanguage() {
        assertEquals("Schließen",sample().text("de",Locale.US,"Zamknij"));
        assertEquals("Close",sample().text("en",Locale.GERMANY,"Zamknij"));
    }

    @Test public void automaticChoiceUsesSupportedSystemLanguage() {
        assertEquals("Schließen",sample().text("auto",Locale.GERMANY,"Zamknij"));
        assertEquals("Close",sample().text("auto",Locale.UK,"Zamknij"));
        assertEquals("Zamknij",sample().text("auto",Locale.FRANCE,"Zamknij"));
    }

    @Test public void missingTranslationFallsBackToPolish() {
        assertEquals("Brak danych",sample().text("de",Locale.GERMANY,"Brak danych"));
        assertEquals("Zamknij",sample().text("pl",Locale.UK,"Zamknij"));
    }

    @Test public void readsCataloguesWithEscapedText() throws Exception {
        Translations catalogue=Translations.fromJson("{\"Głos\\nHA\":\"Voice\\nHA\"}",
                "{\"Głos\\nHA\":\"Stimme\\nHA\"}");
        assertEquals("Stimme\nHA",catalogue.text("de",Locale.US,"Głos\nHA"));
    }

    @Test public void cyclesThroughAutomaticAndThreeLanguages() {
        assertEquals("pl",Translations.nextChoice("auto"));
        assertEquals("en",Translations.nextChoice("pl"));
        assertEquals("de",Translations.nextChoice("en"));
        assertEquals("auto",Translations.nextChoice("de"));
    }

    @Test public void numberFormattingFollowsChosenLanguage() {
        assertEquals("1.5",String.format(Translations.locale("en",Locale.GERMANY),"%.1f",1.5));
        assertEquals("1,5",String.format(Translations.locale("de",Locale.US),"%.1f",1.5));
        assertEquals("1,5",String.format(Translations.locale("pl",Locale.US),"%.1f",1.5));
    }
}
