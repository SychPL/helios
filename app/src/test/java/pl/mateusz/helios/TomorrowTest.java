package pl.mateusz.helios;

import org.json.JSONObject;
import org.junit.Test;
import java.time.ZoneId;
import static org.junit.Assert.*;

/** The daily forecast from HA: tomorrow is picked by date, never by position in the list. */
public class TomorrowTest {
    private static final ZoneId WARSAW=ZoneId.of("Europe/Warsaw");
    private static final long NOW=java.time.OffsetDateTime.parse("2026-09-22T23:40:00+02:00").toInstant().toEpochMilli();
    private static JSONObject event(String... entries) throws Exception {
        StringBuilder b=new StringBuilder("{\"type\":\"daily\",\"forecast\":[");
        for(int i=0;i<entries.length;i++)b.append(i==0?"":",").append(entries[i]);
        return new JSONObject(b.append("]}").toString());
    }
    private static String day(String date,String condition,String high,String low){
        return "{\"datetime\":\""+date+"T00:00:00+02:00\",\"condition\":\""+condition+"\""
            +(high==null?"":",\"temperature\":"+high)+(low==null?"":",\"templow\":"+low)+"}";
    }

    @Test public void picksTheEntryDatedTomorrowWhateverItsPosition() throws Exception {
        Tomorrow t=Tomorrow.parse(event(day("2026-09-22","rainy","14","9"),day("2026-09-23","partlycloudy","21","12"),day("2026-09-24","sunny","23","13")),"°C",NOW,WARSAW);
        assertNotNull(t);
        assertEquals("partlycloudy",t.condition);assertEquals("21°C",t.value());assertEquals("↓ 12°",t.detail());assertEquals("mdi:weather-partly-cloudy",t.icon());
        // late in the evening HA may already start the list at tomorrow
        t=Tomorrow.parse(event(day("2026-09-23","sunny","20","11")),"°C",NOW,WARSAW);
        assertNotNull(t);assertEquals("20°C",t.value());
    }
    @Test public void refusesWhatItCannotUse() throws Exception {
        assertNull(Tomorrow.parse(null,"°C",NOW,WARSAW));
        assertNull(Tomorrow.parse(new JSONObject("{\"type\":\"daily\"}"),"°C",NOW,WARSAW)); // no list
        assertNull(Tomorrow.parse(event(day("2026-09-22","rainy","14","9")),"°C",NOW,WARSAW)); // only today
        assertNull(Tomorrow.parse(event(day("2026-09-23","sunny",null,null)),"°C",NOW,WARSAW)); // no temperature at all
        assertNull(Tomorrow.parse(event("{\"datetime\":\"nonsense\",\"temperature\":20}"),"°C",NOW,WARSAW));
    }
    @Test public void survivesHalfARecord() throws Exception {
        Tomorrow t=Tomorrow.parse(event(day("2026-09-23","","19",null)),"°C",NOW,WARSAW);
        assertNotNull(t);assertNull(t.condition);assertNull(t.icon());
        assertEquals("19°C",t.value());assertEquals("",t.detail()); // one number is no range
        t=Tomorrow.parse(event(day("2026-09-23","snowy",null,"-3")),"°C",NOW,WARSAW);
        assertNotNull(t);assertEquals("-3°C",t.value());assertEquals("",t.detail());
    }
    @Test public void theSubscriptionNamesTheEntityAndTheDailyType(){
        JSONObject payload=Tomorrow.subscribe("weather.dom");
        assertEquals("weather/subscribe_forecast",payload.optString("type"));
        assertEquals("daily",payload.optString("forecast_type"));
        assertEquals("weather.dom",payload.optString("entity_id"));
    }
}
