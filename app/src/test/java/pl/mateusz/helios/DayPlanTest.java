package pl.mateusz.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import java.time.*;
import java.util.Collections;
import static org.junit.Assert.*;

public class DayPlanTest {
    private static final ZoneId WARSAW=ZoneId.of("Europe/Warsaw");
    private static final ZonedDateTime NOW=ZonedDateTime.parse("2026-11-02T09:00:00+01:00[Europe/Warsaw]");
    @BeforeClass public static void icons() throws Exception {MdiIconsTest.install();}

    @Test public void cardRequiresCalendarAndRestrictsOptionalTodo() throws Exception {
        JSONObject card=DashboardSpecV6Test.item("today","day_plan",1,1,2,2).put("calendar_entity","calendar.home").put("todo_entity","todo.home");
        DashboardSpec spec=DashboardSpec.parse(new JSONObject().put("version",6).put("pages",new JSONArray().put(DashboardSpecV6Test.page("main",null,card))));
        assertEquals("calendar.home",spec.items.get(0).calendarEntity);
        assertEquals("todo.home",spec.items.get(0).todoEntity);
        assertFalse(spec.items.get(0).interactive());
        assertTrue(spec.entities().contains("calendar.home"));
        assertTrue(spec.entities().contains("todo.home"));
        try {DashboardSpec.parse(new JSONObject().put("version",6).put("pages",new JSONArray().put(DashboardSpecV6Test.page("main",null,
                DashboardSpecV6Test.item("bad","day_plan",1,1,2,2).put("calendar_entity","sensor.wrong")))));fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("calendar_entity"));}
    }

    @Test public void readRequestsTargetOnlyConfiguredEntitiesAndToday() throws Exception {
        JSONObject calendar=DayPlan.calendarRequest("calendar.home",NOW);
        assertEquals("call_service",calendar.getString("type"));
        assertEquals("calendar",calendar.getString("domain"));
        assertEquals("get_events",calendar.getString("service"));
        assertTrue(calendar.getBoolean("return_response"));
        assertEquals("calendar.home",calendar.getJSONObject("target").getString("entity_id"));
        assertEquals("2026-11-03T00:00:00+01:00",calendar.getJSONObject("service_data").getString("end_date_time"));
        JSONObject todo=DayPlan.todoRequest("todo.home");
        assertEquals("todo",todo.getString("domain"));
        assertEquals("get_items",todo.getString("service"));
        assertEquals("needs_action",todo.getJSONObject("service_data").getString("status"));
    }

    @Test public void picksNearestRemainingEventAndTwoOpenTasks() throws Exception {
        JSONObject calendar=new JSONObject("{\"success\":true,\"result\":{\"response\":{\"calendar.home\":{\"events\":[{\"summary\":\"Later\",\"start\":\"2026-11-02T15:00:00+01:00\",\"end\":\"2026-11-02T16:00:00+01:00\"},{\"summary\":\"Meeting\",\"start\":\"2026-11-02T10:00:00+01:00\",\"end\":\"2026-11-02T11:00:00+01:00\"},{\"summary\":\"Past\",\"start\":\"2026-11-02T07:00:00+01:00\",\"end\":\"2026-11-02T08:00:00+01:00\"}]}}}}");
        JSONObject todo=new JSONObject("{\"success\":true,\"result\":{\"response\":{\"todo.home\":{\"items\":[{\"summary\":\"Milk\",\"status\":\"needs_action\"},{\"summary\":\"Old\",\"status\":\"completed\"},{\"summary\":\"Bread\",\"status\":\"needs_action\"},{\"summary\":\"Eggs\",\"status\":\"needs_action\"}]}}}}");
        DayPlan plan=DayPlan.fromResponses("calendar.home","todo.home",calendar,todo,NOW.toInstant(),WARSAW);
        assertEquals("Meeting",plan.event.summary);
        assertEquals("10:00",plan.event.time);
        assertEquals(2,plan.tasks.size());
        assertEquals("Milk",plan.tasks.get(0));assertEquals("Bread",plan.tasks.get(1));
    }

    @Test public void allDayAndPartialFailureRemainReadable() throws Exception {
        JSONObject calendar=new JSONObject("{\"success\":true,\"result\":{\"response\":{\"calendar.home\":{\"events\":[{\"summary\":\"Holiday\",\"start\":\"2026-11-02\",\"end\":\"2026-11-03\"}]}}}}");
        DayPlan plan=DayPlan.fromResponses("calendar.home","todo.home",calendar,HaDashboardClient.failure("offline"),NOW.toInstant(),WARSAW);
        assertEquals("Holiday",plan.event.summary);assertTrue(plan.event.allDay);
        assertFalse(plan.todoAvailable);
        assertTrue(plan.calendarAvailable);
        DashboardSpec.Item card=DashboardSpec.parse(new JSONObject().put("version",6).put("pages",new JSONArray().put(DashboardSpecV6Test.page("main",null,
            DashboardSpecV6Test.item("today","day_plan",1,1,2,2).put("calendar_entity","calendar.home").put("todo_entity","todo.home"))))).items.get(0);
        CardBodies.CardContent content=CardBodies.FOR.get("day_plan").render(card,Collections.emptyMap(),true,new CardBodies.Env(){
            public String time(){return "";}public String weekday(){return "";}public String date(){return "";}public String musicInfo(){return "";}public long now(){return NOW.toInstant().toEpochMilli();}
            public DayPlan dayPlan(String id){return plan;}
        });
        assertEquals("Cały dzień",content.rows.get(0).label);
        assertEquals("Holiday",content.rows.get(0).value);
        assertEquals("Brak danych",content.rows.get(1).value);
    }

    @Test public void calendarOnlyCardHasNoTaskPlaceholder() throws Exception {
        DashboardSpec.Item card=DashboardSpec.parse(new JSONObject().put("version",6).put("pages",new JSONArray().put(DashboardSpecV6Test.page("main",null,
            DashboardSpecV6Test.item("today","day_plan",1,1,2,2).put("calendar_entity","calendar.home"))))).items.get(0);
        assertNull(card.todoEntity);
        DayPlan plan=DayPlan.fromResponses("calendar.home",null,new JSONObject("{\"success\":true,\"result\":{\"response\":{\"calendar.home\":{\"events\":[]}}}}"),null,NOW.toInstant(),WARSAW);
        CardBodies.CardContent content=CardBodies.FOR.get("day_plan").render(card,Collections.emptyMap(),true,new CardBodies.Env(){
            public String time(){return "";}public String weekday(){return "";}public String date(){return "";}public String musicInfo(){return "";}public long now(){return NOW.toInstant().toEpochMilli();}
            public DayPlan dayPlan(String id){return plan;}
        });
        assertEquals(1,content.rows.size());
        assertEquals("Brak wydarzeń",content.rows.get(0).value);
    }
    @Test public void malformedActionRepliesAreMissingDataNotEmptyLists() throws Exception {
        JSONObject malformed=new JSONObject("{\"success\":true,\"result\":{\"response\":{\"calendar.home\":{},\"todo.home\":{}}}}");
        DayPlan plan=DayPlan.fromResponses("calendar.home","todo.home",malformed,malformed,NOW.toInstant(),WARSAW);
        assertFalse(plan.calendarAvailable);
        assertFalse(plan.todoAvailable);
    }
}
