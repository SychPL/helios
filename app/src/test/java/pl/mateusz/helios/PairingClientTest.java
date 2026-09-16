package pl.mateusz.helios;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PairingClientTest {
    @Test public void probeDistinguishesIntegrationFromNothing() throws Exception {
        try(TestHttp s=new TestHttp(r->r.path.equals("/api/helios/pair")&&r.method.equals("GET")?TestHttp.Response.json(200,"{\"protocol\":2}"):TestHttp.Response.json(405,"{}"))){
            assertEquals(200,PairingClient.probe(s.url("")));
            assertEquals("a trailing slash is fine",200,PairingClient.probe(s.url("/")));
        }
        try(TestHttp none=new TestHttp(r->TestHttp.Response.json(404,"{}"))){assertEquals(404,PairingClient.probe(none.url("")));}
        try(TestHttp old=new TestHttp(r->TestHttp.Response.json(200,"{\"protocol\":1}"))){assertEquals("an older integration is as good as none",404,PairingClient.probe(old.url("")));}
        assertEquals(-1,PairingClient.probe("http://127.0.0.1:1"));
    }
    @Test public void pairPostsJsonAndReturnsStatusWithBody() throws Exception {
        try(TestHttp s=new TestHttp(r->TestHttp.Response.json(200,"{\"protocol\":2,\"token\":\"tok\",\"pipeline\":\"p\",\"dashboard_path\":\"helios-clock\"}"))){
            PairingClient.Result r=PairingClient.pair(s.url(""),new JSONObject().put("installation_id","abcdefgh-1").put("code","123456").put("app_version","0.9.0").put("version_code",28));
            assertEquals(200,r.status);assertEquals("tok",r.json.getString("token"));
            TestHttp.Request seen=s.seen.get(0);String body=new String(seen.body,"UTF-8");
            assertEquals("POST",seen.method);assertEquals("/api/helios/pair",seen.path);assertTrue(body,body.contains("\"code\":\"123456\""));assertEquals("application/json",seen.headers.get("content-type"));
        }
        try(TestHttp bad=new TestHttp(r->TestHttp.Response.json(401,"{\"error\":\"unauthorized\"}"))){
            PairingClient.Result r=PairingClient.pair(bad.url(""),new JSONObject());
            assertEquals(401,r.status);assertEquals("unauthorized",r.json.getString("error"));assertEquals("Kod nieprawidłowy lub wygasł",PairingClient.message(r.status));
        }
        assertEquals("HA nie dokończył parowania - spróbuj ponownie",PairingClient.message(409));
        assertEquals("HA nie jest gotowy (brak pipeline Assist lub błąd tożsamości)",PairingClient.message(503));
        assertEquals("Brak połączenia z HA",PairingClient.message(-1));
        assertEquals(-1,PairingClient.pair("http://127.0.0.1:1",new JSONObject()).status);
    }
}
