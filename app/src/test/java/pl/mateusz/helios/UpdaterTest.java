package pl.mateusz.helios;

import org.json.*;
import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class UpdaterTest {
    @Test public void decisionRedirectPolicyAndRestore(){
        assertEquals("newer",Updater.decision("0.9.1","0.9.0"));assertEquals("same",Updater.decision("0.9.0","0.9.0"));assertEquals("older",Updater.decision("0.8.18","0.9.0"));
        assertEquals("https://objects.githubusercontent.com/a?b=1",Updater.nextHop("https://objects.githubusercontent.com/a?b=1","https://github.com/x",ReleaseInfo::allowedHost));
        assertEquals("https://github.com/rel/x",Updater.nextHop("/rel/x","https://github.com/a/b",ReleaseInfo::allowedHost));
        assertNull(Updater.nextHop("http://github.com/x","https://github.com/a",ReleaseInfo::allowedHost));assertNull(Updater.nextHop("https://evil.com/x","https://github.com/a",ReleaseInfo::allowedHost));
        assertNull(Updater.nextHop("https://githubusercontent.com.evil/x","https://github.com/a",ReleaseInfo::allowedHost));assertNull(Updater.nextHop(null,"https://github.com/a",ReleaseInfo::allowedHost));
        assertEquals("none",Updater.restoreDecision(false,null));assertEquals("busy",Updater.restoreDecision(true,Boolean.TRUE));
        assertEquals("abandon",Updater.restoreDecision(true,Boolean.FALSE));assertEquals("abandon",Updater.restoreDecision(true,null));
    }
    @Test public void downloadFollowsAtMostFiveAllowedRedirectsAndEnforcesSizes() throws Exception {
        byte[] payload=new byte[100_000];new Random(1).nextBytes(payload);
        try(TestHttp s=new TestHttp(r->{
            if(r.path.startsWith("/hop")){int n=Integer.parseInt(r.path.substring(r.path.indexOf('?')+1));return TestHttp.Response.redirect(n>0?"/hop?"+(n-1):"/file");}
            if(r.path.equals("/short"))return new TestHttp.Response(200,Arrays.copyOf(payload,50));
            return new TestHttp.Response(200,payload);
        })){
            File target=File.createTempFile("helios-update-","apk");
            try{
                Updater.download(s.url("/hop?4"),target,payload.length,u->true);assertEquals(payload.length,target.length());
                try{Updater.download(s.url("/hop?5"),target,payload.length,u->true);fail();}catch(IOException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("przekierowań"));}
                try{Updater.download(s.url("/hop?1"),target,payload.length,u->!u.contains("/file"));fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("poza GitHub"));}
                try{Updater.download(s.url("/file"),target,50,u->true);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("większy"));}
                try{Updater.download(s.url("/short"),target,payload.length,u->true);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("Niepełne"));}
                Updater.download(s.url("/file"),target,0,u->true);assertEquals("unknown size: only the hard cap applies",payload.length,target.length());
                try{Updater.download(s.url("/file"),target,0,u->true,50_000L);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("większy"));} // the hard cap, injected small
            }finally{target.delete();}
        }
    }
    /** Fake Android surface: every stateful path of run()/restore()/onStatus() is driven through it. */
    static final class Host implements Updater.Host {
        final List<String> log=new ArrayList<>();JSONObject record;boolean canInstall=true,saveOk=true,clearOk=true,downloadOk=true,createOk=true,writeOk=true,commitOk=true;int saveFailAt=-1,saves;JSONObject release;String check="ok";Boolean sealed;int nextSession=41;
        public boolean canInstall(){return canInstall;}
        public void openInstallSettings(){log.add("settings");}
        public JSONObject release(ReleaseInfo.Source source) throws IOException {if(release==null)throw new IOException("offline");return release;}
        public void download(String url,File target,long expected) throws IOException {log.add("download:"+url);if(!downloadOk)throw new IOException("net");}
        public String check(File file,String version,String expectedPackage){return check;}
        public int createSession(long size,String packageName) throws IOException {log.add("create");if(!createOk)throw new IOException("create");return nextSession;}
        public void write(int session,File file) throws IOException {log.add("write:"+session);if(!writeOk)throw new IOException("write");}
        public void commit(int session,String operation,File file) throws IOException {log.add("commit:"+session+":"+operation);if(!commitOk)throw new IOException("commit");}
        public void abandon(int session){log.add("abandon:"+session);}
        public Boolean sealed(int session){return sealed;}
        public JSONObject record(){return record;}
        public boolean saveRecord(JSONObject r){
            if(r==null){log.add("clear");if(clearOk)record=null;return clearOk;}
            log.add("save");saves++;boolean ok=saveOk&&saves!=saveFailAt;if(ok)record=r;return ok;
        }
        public File cacheDir(){return new File(System.getProperty("java.io.tmpdir"));}
        public void deleteFile(String path){log.add("delete");}
        public void status(String text){log.add("status:"+text);}
        public void launch(android.content.Intent intent){log.add("launch");}
    }
    private static final String DL="download:https://github.com/SychPL/helios/releases/download/v0.9.1/helios-0.9.1.apk";
    private static JSONObject newer() throws Exception {return new JSONObject().put("tag_name","v0.9.1").put("draft",false).put("prerelease",false).put("assets",new JSONArray().put(new JSONObject().put("name","helios-0.9.1.apk").put("browser_download_url","https://github.com/SychPL/helios/releases/download/v0.9.1/helios-0.9.1.apk").put("size",6_000_000)));}
    @Test public void runCoversPermissionReleaseVersionAndFailures() throws Exception {
        Host h=new Host();Updater u=new Updater(h,"0.9.0",28);
        h.canInstall=false;u.run();assertEquals(Arrays.asList("settings","status:Zezwól Heliosowi na instalację, potem powtórz"),h.log);assertNull("no record without permission",h.record);h.log.clear();h.canInstall=true;
        u.run();assertEquals(Arrays.asList("status:Brak połączenia z GitHub"),h.log);h.log.clear();
        h.release=new JSONObject().put("tag_name","v0.9.0").put("draft",false).put("prerelease",false).put("assets",new JSONArray().put(new JSONObject().put("name","helios-0.9.0.apk").put("browser_download_url","https://github.com/x").put("size",1)));
        u.run();assertEquals(Arrays.asList("status:Masz najnowszą wersję (0.9.0)"),h.log);h.log.clear();
        h.release=newer();h.check="Nieprawidłowy plik wydania";
        u.run();assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"status:Nieprawidłowy plik wydania","delete","clear"),h.log);assertNull(h.record);assertFalse(u.busy());h.log.clear();
        h.check="ok";h.writeOk=false;
        u.run();assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"create","save","write:41","status:Instalacja nieudana","abandon:41","delete","clear"),h.log);assertFalse(u.busy());h.log.clear();
        h.writeOk=true;h.saveOk=false;
        u.run();assertEquals("the first record save fails: nothing else happens",Arrays.asList("save","status:Nie udało się zapisać stanu aktualizacji"),h.log);assertFalse(u.busy());h.log.clear();h.saveOk=true;
    }
    @Test public void everyLaterFailureAbandonsAndCleansUp() throws Exception {
        Host h=new Host();Updater u=new Updater(h,"0.9.0",28);h.release=newer();
        h.downloadOk=false;
        u.run();assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"status:Brak połączenia z GitHub","delete","clear"),h.log);assertFalse(u.busy());h.log.clear();h.downloadOk=true;
        h.createOk=false;
        u.run();assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"create","status:Instalacja nieudana","delete","clear"),h.log);assertFalse(u.busy());h.log.clear();h.createOk=true;
        h.saves=0;h.saveFailAt=2; // the second save (session id) fails
        u.run();assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"create","save","status:Nie udało się zapisać stanu aktualizacji","abandon:41","delete","clear"),h.log);assertFalse(u.busy());h.log.clear();h.saveFailAt=-1;
        h.commitOk=false;
        u.run();String commit=h.log.stream().filter(e->e.startsWith("commit:41:")).findFirst().orElse("none"); // the record is gone after the cleanup, the id lives only in the log
        assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"create","save","write:41",commit,"status:Instalacja nieudana","abandon:41","delete","clear"),h.log);assertFalse(u.busy());h.log.clear();h.commitOk=true;
        h.clearOk=false;h.writeOk=false;
        u.run();assertEquals("a failed clear is reported, not hidden",Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"create","save","write:41","status:Instalacja nieudana","abandon:41","delete","clear","status:Nie udało się wyczyścić stanu aktualizacji"),h.log);
        h.sealed=null;assertFalse("an unsealed leftover record is not busy",u.busy());
    }
    @Test public void successfulCommitLeavesADurableOperationAndTheReceiverClearsIt() throws Exception {
        Host h=new Host();Updater u=new Updater(h,"0.9.0",28);h.release=newer();
        u.run();
        assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"create","save","write:41","commit:41:"+h.record.getString("id")),h.log);
        assertEquals(41,h.record.getInt("session"));h.sealed=Boolean.TRUE;assertTrue("busy from the durable record + sealed session",u.busy());h.log.clear();
        String op=h.record.getString("id");
        u.onStatus("other-op",android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION,null,"/tmp/x.apk",null);assertEquals("a foreign pending status is ignored",Arrays.asList("delete"),h.log);h.log.clear();
        u.onStatus(op,android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION,new android.content.Intent(),"/tmp/x.apk",null);assertEquals(Arrays.asList("launch"),h.log);assertTrue(u.busy());h.log.clear();
        u.onStatus(op,android.content.pm.PackageInstaller.STATUS_SUCCESS,null,"/tmp/x.apk",null);assertEquals(Arrays.asList("delete","clear"),h.log);assertNull(h.record);h.sealed=null;assertFalse(u.busy());h.log.clear();
        u.run();h.log.clear();h.sealed=Boolean.TRUE;String op2=h.record.getString("id");
        u.onStatus(op,android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED,null,"/tmp/x.apk","aborted");assertEquals("a stale operation id never clears the current record",Arrays.asList("delete"),h.log);assertNotNull(h.record);h.log.clear();
        u.onStatus(op2,android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED,null,"/tmp/y.apk","INSTALL_FAILED_UPDATE_INCOMPATIBLE");assertEquals("a final non-success status reports the installer's reason and clears",Arrays.asList("status:Instalacja odrzucona: INSTALL_FAILED_UPDATE_INCOMPATIBLE","delete","clear"),h.log);assertNull(h.record);assertFalse(u.busy());
    }
    @Test public void restoreAfterProcessDeath() throws Exception {
        Host h=new Host();h.record=new JSONObject().put("id","a").put("session",-1).put("file","/tmp/a.apk").put("started",0);
        new Updater(h,"0.9.0",28).restore();assertEquals(Arrays.asList("delete","clear"),h.log);h.log.clear();
        h.record=new JSONObject().put("id","b").put("session",7).put("file","/tmp/b.apk").put("started",0);h.sealed=Boolean.FALSE;
        new Updater(h,"0.9.0",28).restore();assertEquals(Arrays.asList("abandon:7","delete","clear"),h.log);h.log.clear();
        h.record=new JSONObject().put("id","c").put("session",8).put("file","/tmp/c.apk").put("started",0);h.sealed=Boolean.TRUE;
        Updater u=new Updater(h,"0.9.0",28);u.restore();assertTrue(h.log.isEmpty());assertTrue(u.busy());
        u.run();assertEquals(Arrays.asList("status:Aktualizacja w toku…"),h.log);
    }
}
