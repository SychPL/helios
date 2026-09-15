package pl.mateusz.helios;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ForecastTest {
    static final long NOW=1_789_502_400_000L; // 2026-09-15T20:00:00Z
    static Map<String,String> good(){
        Map<String,String> a=new HashMap<>();
        a.put("forecast_date","2026-09-16");a.put("condition","cloudy");a.put("temperature","18.4");a.put("templow","9.2");a.put("temperature_unit","°C");
        a.put("fetched_at","2026-09-15T19:00:00+00:00");a.put("valid_until","2026-09-15T22:00:00+00:00");return a;
    }
    static EntityStates.Entity ready(Map<String,String> a){return new EntityStates.Entity("ready",a);}

    @Test public void validRecordParsesAndFormatsWholeDegrees(){
        Forecast f=Forecast.parse(ready(good()),NOW);
        assertNotNull(f);assertEquals("cloudy",f.condition);assertEquals("maks. 18°C",f.max());assertEquals("min. 9°C",f.min());
        assertEquals(1_789_509_600_000L,f.validUntilMs);
        Map<String,String> a=good();a.remove("templow");assertNull(Forecast.parse(ready(a),NOW).min());
        a=good();a.put("templow","");assertNull(Forecast.parse(ready(a),NOW).min());
        a=good();a.put("temperature","-2.6");a.put("temperature_unit","°F");assertEquals("maks. -3°F",Forecast.parse(ready(a),NOW).max());
        a=good();a.put("temperature","-0.2");assertEquals("maks. 0°C",Forecast.parse(ready(a),NOW).max());
        a=good();a.put("valid_until","2026-09-15 22:00:00+01:00");assertNotNull(Forecast.parse(ready(a),NOW)); // space-separated ISO from HA templates (21:00Z)
    }
    @Test public void anyBrokenFieldOrExpiryMeansNoForecast(){
        assertNull(Forecast.parse(null,NOW));
        assertNull(Forecast.parse(new EntityStates.Entity("none",good()),NOW));
        assertNull(Forecast.parse(new EntityStates.Entity("unavailable",good()),NOW));
        String[][] bad={{"condition",""},{"condition",null},{"temperature","warm"},{"temperature",null},{"temperature","NaN"},{"templow","x"},{"temperature_unit","C"},{"temperature_unit",null},
            {"forecast_date","16.09.2026"},{"forecast_date",null},{"fetched_at","yesterday"},{"fetched_at",null},{"valid_until","soon"},{"valid_until",null},{"valid_until","2026-09-15T19:59:59+00:00"}};
        for(String[] b:bad){Map<String,String> a=good();if(b[1]==null)a.remove(b[0]);else a.put(b[0],b[1]);assertNull(b[0]+"="+b[1],Forecast.parse(ready(a),NOW));}
        assertNull(Forecast.parse(ready(good()),1_789_509_600_000L)); // exactly at valid_until: expired
    }
    @Test public void modeFollowsTheLiveHelperState(){
        assertEquals(Forecast.Mode.TOMORROW,Forecast.mode(new EntityStates.Entity("on",new HashMap<>()),"on"));
        assertEquals(Forecast.Mode.TODAY,Forecast.mode(new EntityStates.Entity("off",new HashMap<>()),"on"));
        assertEquals(Forecast.Mode.UNKNOWN,Forecast.mode(new EntityStates.Entity("unknown",new HashMap<>()),"on"));
        assertEquals(Forecast.Mode.UNKNOWN,Forecast.mode(new EntityStates.Entity("unavailable",new HashMap<>()),"on"));
        assertEquals(Forecast.Mode.UNKNOWN,Forecast.mode(null,"on"));
    }
    @Test public void nullDeltaDropsTheMinimumThroughEntityStates() throws Exception {
        EntityStates states=new EntityStates(Collections.singletonMap("sensor.f",new HashSet<>(DashboardSpec.FORECAST_ATTRIBUTES)));
        org.json.JSONObject a=new org.json.JSONObject(good());
        states.apply(new org.json.JSONObject().put("a",new org.json.JSONObject().put("sensor.f",new org.json.JSONObject().put("s","ready").put("a",a))));
        assertEquals("min. 9°C",Forecast.parse(states.snapshot().get("sensor.f"),NOW).min());
        states.apply(new org.json.JSONObject("{\"c\":{\"sensor.f\":{\"+\":{\"a\":{\"templow\":null}}}}}"));
        Forecast f=Forecast.parse(states.snapshot().get("sensor.f"),NOW);assertNotNull(f);assertNull(f.min());
    }
}
