package pl.mateusz.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Tomorrow, taken from Home Assistant's own daily forecast (`weather/subscribe_forecast`) instead of a helper
 * entity someone has to build first. The record is chosen by date, never by index: the first entry may be today,
 * or - late in the evening - already tomorrow.
 */
final class Tomorrow {
    final String condition,unit;final Double high,low;
    private Tomorrow(String condition,String unit,Double high,Double low){this.condition=condition;this.unit=unit;this.high=high;this.low=low;}

    /** The command that starts the feed; HA answers with one event now and another whenever the forecast changes. */
    static JSONObject subscribe(String entity){
        try{return new JSONObject().put("type","weather/subscribe_forecast").put("forecast_type","daily").put("entity_id",entity);}
        catch(Exception e){throw new IllegalStateException(e);}
    }

    /** null = nothing usable in this event (no daily list, no entry for tomorrow, no temperature). */
    static Tomorrow parse(JSONObject event,String unit,long nowMs,ZoneId zone){
        if(event==null)return null;
        JSONArray list=event.optJSONArray("forecast");
        if(list==null)return null;
        LocalDate wanted=Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1);
        for(int i=0;i<list.length();i++){
            JSONObject entry=list.optJSONObject(i);
            if(entry==null)continue;
            LocalDate date=date(entry.optString("datetime",null),zone);
            if(date==null||!date.equals(wanted))continue;
            Double high=number(entry,"temperature"),low=number(entry,"templow");
            if(high==null&&low==null)return null;
            String condition=entry.optString("condition",null);
            return new Tomorrow(condition==null||condition.trim().isEmpty()?null:condition,unit==null?"":unit,high,low);
        }
        return null;
    }
    /** "21°C" - whole degrees, the unit of the entity the forecast belongs to. */
    String value(){return high!=null?degrees(high)+unit:low!=null?degrees(low)+unit:"—";}
    /** "↓ 12°" - the night low beside the day's high; empty when HA sends only one number. */
    String detail(){return low==null||high==null?"":"↓ "+degrees(low)+"°";}
    String icon(){return WeatherLabels.icon(condition);}
    private static String degrees(double value){return String.format(Locale.ROOT,"%.0f",value).replace("-0","0");}
    private static Double number(JSONObject o,String key){
        if(!o.has(key)||o.isNull(key))return null;
        double v=o.optDouble(key,Double.NaN);
        return Double.isFinite(v)?v:null;
    }
    private static LocalDate date(String raw,ZoneId zone){
        if(raw==null)return null;
        try{return OffsetDateTime.parse(raw).atZoneSameInstant(zone).toLocalDate();}
        catch(Exception e){
            try{return LocalDate.parse(raw.length()>=10?raw.substring(0,10):raw);}
            catch(Exception ignored){return null;}
        }
    }
}
