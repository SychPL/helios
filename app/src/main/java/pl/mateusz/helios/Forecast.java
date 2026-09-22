package pl.mateusz.helios;

import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * Tomorrow's forecast record published by HA (SPEC 0.9 pkt 6.2): validated whole, never guessed. The date choice and the
 * time of day belong to HA; the clock only checks types, the unit and that the record has not expired.
 */
final class Forecast {
    enum Mode {TODAY,TOMORROW,UNKNOWN}
    final String condition,unit;final double temperature;final Double templow;final long validUntilMs;
    private Forecast(String condition,String unit,double temperature,Double templow,long validUntilMs){this.condition=condition;this.unit=unit;this.temperature=temperature;this.templow=templow;this.validUntilMs=validUntilMs;}

    /** null = no usable forecast (missing entity, not ready, malformed field, wrong unit, expired). */
    static Forecast parse(EntityStates.Entity e,long nowMs){
        if(e==null||!e.known()||!e.state.equals("ready"))return null;
        String condition=e.attribute("condition"),unit=e.attribute("temperature_unit"),date=e.attribute("forecast_date");
        if(condition==null||condition.trim().isEmpty())return null;
        if(unit==null||!(unit.equals("°C")||unit.equals("°F")))return null;
        if(date==null||!date.matches("\\d{4}-\\d{2}-\\d{2}"))return null;
        Double temperature=number(e.attribute("temperature"));if(temperature==null)return null;
        String low=e.attribute("templow");Double templow=null;
        if(low!=null&&!low.trim().isEmpty()){templow=number(low);if(templow==null)return null;}
        Long fetched=iso(e.attribute("fetched_at")),until=iso(e.attribute("valid_until"));
        if(fetched==null||until==null||until<=nowMs)return null;
        return new Forecast(condition,unit,temperature,templow,until);
    }
    static Mode mode(EntityStates.Entity when,String expected){
        if(when==null||!when.known())return Mode.UNKNOWN;
        return when.state.equals(expected)?Mode.TOMORROW:Mode.TODAY;
    }
    /** "maks. 18°C" / "min. -3°C": whole degrees, unit from HA. */
    String max(){return "maks. "+degrees(temperature);}
    String min(){return templow==null?null:"min. "+degrees(templow);}
    /** "18°C / 9°C" for the detail row, where there is no room for words; just the high when HA sends no low. */
    String range(){return templow==null?degrees(temperature):degrees(temperature)+" / "+degrees(templow);}
    private String degrees(double value){return String.format(Locale.ROOT,"%.0f",value).replace("-0","0")+unit;}
    private static Double number(String raw){
        if(raw==null)return null;
        try{double v=Double.parseDouble(raw);return Double.isFinite(v)?v:null;}catch(NumberFormatException e){return null;}
    }
    private static Long iso(String raw){
        if(raw==null)return null;
        try{return OffsetDateTime.parse(raw.replace(" ","T")).toInstant().toEpochMilli();}catch(Exception e){return null;}
    }
}
