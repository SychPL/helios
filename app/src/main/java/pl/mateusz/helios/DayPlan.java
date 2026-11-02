package pl.mateusz.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** A bounded, read-only snapshot from HA calendar.get_events and todo.get_items. */
final class DayPlan {
    static final class Event {
        final String summary,time;final boolean allDay;
        Event(String summary,String time,boolean allDay){this.summary=summary;this.time=time;this.allDay=allDay;}
    }
    final Event event;
    final List<String> tasks;
    final boolean calendarAvailable,todoAvailable;
    private DayPlan(Event event,List<String> tasks,boolean calendarAvailable,boolean todoAvailable){
        this.event=event;this.tasks=Collections.unmodifiableList(tasks);this.calendarAvailable=calendarAvailable;this.todoAvailable=todoAvailable;
    }
    static JSONObject calendarRequest(String entity,ZonedDateTime now){
        try{return new JSONObject().put("type","call_service").put("domain","calendar").put("service","get_events")
            .put("target",new JSONObject().put("entity_id",entity)).put("return_response",true)
            .put("service_data",new JSONObject()
                .put("start_date_time",now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .put("end_date_time",now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    static JSONObject todoRequest(String entity){
        try{return new JSONObject().put("type","call_service").put("domain","todo").put("service","get_items")
            .put("target",new JSONObject().put("entity_id",entity)).put("return_response",true)
            .put("service_data",new JSONObject().put("status","needs_action"));
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    static DayPlan fromResponses(String calendarEntity,String todoEntity,JSONObject calendarResult,JSONObject todoResult,Instant now,ZoneId zone){
        JSONObject calendar=entry(calendarResult,calendarEntity),todo=todoEntity==null?null:entry(todoResult,todoEntity);
        JSONArray calendarEvents=calendar==null?null:calendar.optJSONArray("events");
        JSONArray todoItems=todo==null?null:todo.optJSONArray("items");
        Event event=null;Instant earliest=null;
        if(calendarEvents!=null){
            for(int i=0;i<calendarEvents.length();i++){
                JSONObject row=calendarEvents.optJSONObject(i);if(row==null)continue;
                String summary=summary(row);if(summary==null)continue;
                String raw=row.optString("start",null),endRaw=row.optString("end",null);
                Instant start=date(raw,zone),end=date(endRaw,zone);
                if(start==null||end==null||!end.isAfter(now)||!start.isBefore(now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()))continue;
                if(earliest==null||start.isBefore(earliest)){
                    boolean allDay=raw.length()==10;
                    event=new Event(summary,allDay?"":DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT).format(start.atZone(zone)),allDay);
                    earliest=start;
                }
            }
        }
        List<String> tasks=new ArrayList<>();
        if(todoItems!=null){
            for(int i=0;i<todoItems.length()&&tasks.size()<2;i++){
                JSONObject row=todoItems.optJSONObject(i);if(row==null||!"needs_action".equals(row.optString("status")))continue;
                String summary=summary(row);if(summary!=null)tasks.add(summary);
            }
        }
        return new DayPlan(event,tasks,calendarEvents!=null,todoEntity==null||todoItems!=null);
    }
    private static JSONObject entry(JSONObject message,String entity){
        if(message==null||!message.optBoolean("success"))return null;
        JSONObject result=message.optJSONObject("result"),response=result==null?null:result.optJSONObject("response");
        return response==null?null:response.optJSONObject(entity);
    }
    private static String summary(JSONObject row){
        String s=row.optString("summary","").trim();return s.isEmpty()?null:s.length()>120?s.substring(0,120):s;
    }
    private static Instant date(String raw,ZoneId zone){
        if(raw==null)return null;
        try{return OffsetDateTime.parse(raw).toInstant();}catch(Exception ignored){}
        try{return LocalDateTime.parse(raw).atZone(zone).toInstant();}catch(Exception ignored){}
        try{return LocalDate.parse(raw).atStartOfDay(zone).toInstant();}catch(Exception ignored){}
        return null;
    }
}
