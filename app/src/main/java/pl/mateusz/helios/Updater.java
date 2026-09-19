package pl.mateusz.helios;

import android.content.Intent;
import android.content.pm.PackageInstaller;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import org.json.JSONObject;

/** GitHub Releases updater (SPEC 0.10 pkt 7): all decisions here, every system call behind Host so the JVM tests drive it. */
final class Updater {
    interface Host {
        boolean canInstall();void openInstallSettings();
        JSONObject release(ReleaseInfo.Source source) throws IOException;
        void download(String url,File target,long expected) throws IOException;
        /** null or "ok" when the archive is pl.mateusz.helios with versionName==version and a higher versionCode; otherwise the message to show. */
        String check(File file,String version,String expectedPackage);
        int createSession(long size,String packageName) throws IOException;
        void write(int session,File file) throws IOException;
        void commit(int session,String operation,File file) throws IOException;
        void abandon(int session);
        /** null when PackageInstaller.getMySessions() has no such session. */
        Boolean sealed(int session);
        JSONObject record();boolean saveRecord(JSONObject record);
        File cacheDir();void deleteFile(String path);
        void status(String text);void launch(Intent intent);
    }
    interface HostPolicy {boolean allowed(String url);}
    private final Host host;private final String versionName;private final int versionCode;
    private volatile boolean active; // an operation running in this process (before commit)
    Updater(Host host,String versionName,int versionCode){this.host=host;this.versionName=versionName;this.versionCode=versionCode;}

    static String decision(String latest,String current){
        if(current==null||current.isEmpty())return "absent";            // nothing installed: a first install
        int c=Version.compare(latest,current);return c>0?"newer":c==0?"same":"older";
    }
    static String nextHop(String location,String base,HostPolicy policy){
        try{if(location==null)return null;String abs=new URL(new URL(base),location).toString();return policy.allowed(abs)?abs:null;}catch(Exception e){return null;}
    }
    static String restoreDecision(boolean hasRecord,Boolean sealed){
        if(!hasRecord)return "none";
        return Boolean.TRUE.equals(sealed)?"busy":"abandon"; // no session (null) or an unsealed one: nothing will ever finish it
    }
    /** Manual redirects (at most 5, each target through the policy), streaming with the hard cap; expected>0 must match exactly. */
    static void download(String url,File target,long expected,HostPolicy policy) throws IOException {download(url,target,expected,policy,ReleaseInfo.MAX_BYTES);}
    static void download(String url,File target,long expected,HostPolicy policy,long maxBytes) throws IOException {
        for(int hop=0;hop<=5;hop++){
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
            try{
                int status=c.getResponseCode();
                if(status/100==3){if(hop==5)break;String next=nextHop(c.getHeaderField("Location"),url,policy);if(next==null)throw new IOException("Przekierowanie poza GitHub");url=next;continue;}
                if(status!=200)throw new IOException("HTTP "+status);
                long total=0;byte[] buf=new byte[65536];
                try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(target)){
                    int n;while((n=in.read(buf))!=-1){total+=n;if(total>maxBytes||(expected>0&&total>expected))throw new IOException("Plik większy niż zapowiedziany");out.write(buf,0,n);}
                }
                if(expected>0&&total!=expected)throw new IOException("Niepełne pobranie");
                return;
            }finally{c.disconnect();}
        }
        throw new IOException("Za dużo przekierowań");
    }

    boolean busy(){
        if(active)return true;
        JSONObject record=host.record();
        return "busy".equals(restoreDecision(record!=null,record==null?null:host.sealed(record.optInt("session",-1))));
    }
    /** Menu entry; network thread. Updates Helios itself. */
    void run(){run(ReleaseInfo.HELIOS,versionName);}

    /**
     * Fetches and installs one release of the given source (SPEC 0.12 pkt 6.2). Helios updates itself with its own
     * version; another package is compared against whatever is installed, and "absent" means a first install.
     */
    void run(ReleaseInfo.Source source,String installedVersion){
        if(busy()){host.status("Aktualizacja w toku…");return;}
        if(!host.canInstall()){host.openInstallSettings();host.status("Zezwól Heliosowi na instalację, potem powtórz");return;}
        JSONObject release;
        try{release=host.release(source);}catch(IOException e){host.status("Brak połączenia z GitHub");return;}
        ReleaseInfo info=ReleaseInfo.parse(release,source);
        if(info==null){host.status("Brak wydań");return;}
        String decision=decision(info.version,installedVersion);
        if(!"newer".equals(decision)&&!"absent".equals(decision)){host.status("Masz najnowszą wersję ("+installedVersion+")");return;}
        active=true;
        byte[] rnd=new byte[8];new SecureRandom().nextBytes(rnd);StringBuilder id=new StringBuilder();for(byte b:rnd)id.append(String.format("%02x",b));
        File file=new File(host.cacheDir(),"update-"+id+".apk");
        JSONObject record;
        try{record=new JSONObject().put("id",id.toString()).put("started",System.currentTimeMillis()).put("file",file.getPath()).put("session",-1);}catch(Exception e){active=false;return;}
        if(!host.saveRecord(record)){host.status("Nie udało się zapisać stanu aktualizacji");active=false;return;} // nothing else happened yet
        host.status("Pobieram "+source.label+" "+info.version+"…");
        int session=-1;
        try{
            try{host.download(info.url,file,info.size);}catch(IOException e){fail(record,session,"Brak połączenia z GitHub");return;}
            String problem=host.check(file,info.version,source.expectedPackage);
            if(problem!=null&&!problem.equals("ok")){fail(record,session,problem);return;}
            try{session=host.createSession(file.length(),source.expectedPackage);}catch(IOException e){fail(record,session,"Instalacja nieudana");return;}
            try{record.put("session",session);}catch(Exception ignored){}
            if(!host.saveRecord(record)){fail(record,session,"Nie udało się zapisać stanu aktualizacji");return;}
            try{host.write(session,file);}catch(IOException e){fail(record,session,"Instalacja nieudana");return;}
            try{host.commit(session,record.optString("id"),file);}catch(IOException e){fail(record,session,"Instalacja nieudana");return;}
            // committed: from here the installer owns the session; the durable record + sealed session keep busy() true
        }finally{active=false;}
    }
    /**
     * Downloads and verifies a release without installing it (SPEC 0.12 pkt 5.8). The caller hands the file to the
     * bridge instead of the system installer, which is the whole point of a silent update.
     */
    File fetch(ReleaseInfo.Source source,String installedVersion){
        if(busy()){host.status("Aktualizacja w toku…");return null;}
        JSONObject release;
        try{release=host.release(source);}catch(IOException e){host.status("Brak połączenia z GitHub");return null;}
        ReleaseInfo info=ReleaseInfo.parse(release,source);
        if(info==null){host.status("Brak wydań");return null;}
        String decision=decision(info.version,installedVersion);
        if(!"newer".equals(decision)&&!"absent".equals(decision)){host.status("Masz najnowszą wersję ("+installedVersion+")");return null;}
        File file=new File(host.cacheDir(),"silent-"+info.version+".apk");
        host.status("Pobieram "+source.label+" "+info.version+"…");
        try{host.download(info.url,file,info.size);}
        catch(IOException e){host.status("Brak połączenia z GitHub");host.deleteFile(file.getPath());return null;}
        String problem=host.check(file,info.version,source.expectedPackage);
        if(problem!=null&&!problem.equals("ok")){host.status(problem);host.deleteFile(file.getPath());return null;}
        return file;
    }

    private void fail(JSONObject record,int session,String text){
        host.status(text);
        if(session>=0)host.abandon(session);
        cleanup(record);
    }
    private void cleanup(JSONObject record){
        host.deleteFile(record.optString("file"));
        if(!host.saveRecord(null))host.status("Nie udało się wyczyścić stanu aktualizacji"); // busy() may stay true until the next restore
    }
    /** Service start: a record without a sealed session can never finish; a sealed one is waited for. */
    void restore(){
        JSONObject record=host.record();
        if(record!=null){
            int session=record.optInt("session",-1);
            Boolean sealed=session>=0?host.sealed(session):null;
            if("abandon".equals(restoreDecision(true,sealed))){if(sealed!=null)host.abandon(session);cleanup(record);}
        }
        // both kinds of download: the installer path and the one handed to the clock tools
        File[] stale=host.cacheDir().listFiles((d,n)->(n.startsWith("helios-update-")||n.startsWith("silent-"))&&n.endsWith(".apk"));
        String keep=record==null?null:record.optString("file");
        if(stale!=null)for(File f:stale)if(!f.getPath().equals(keep))host.deleteFile(f.getPath());
    }
    /** UpdateReceiver: statuses of foreign or stale operations only drop their file; ours drive the record. message = EXTRA_STATUS_MESSAGE. */
    void onStatus(String operation,int status,Intent confirm,String file,String message){
        JSONObject record=host.record();
        if(record==null||operation==null||!operation.equals(record.optString("id"))){if(file!=null)host.deleteFile(file);return;}
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){if(confirm!=null)host.launch(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
        if(status!=PackageInstaller.STATUS_SUCCESS)host.status("Instalacja odrzucona: "+(message==null||message.isEmpty()?"status "+status:message));
        cleanup(record);
    }

    /** The real thing: thin calls into Android; nothing here decides anything. */
    static final class AndroidHost implements Host {
        static final String ACTION="pl.mateusz.helios.UPDATE_STATUS";
        private final android.content.Context context;private final java.util.function.Consumer<String> statusSink;
        AndroidHost(android.content.Context context,java.util.function.Consumer<String> statusSink){this.context=context.getApplicationContext();this.statusSink=statusSink;}
        private android.content.SharedPreferences prefs(){return context.getSharedPreferences("helios",android.content.Context.MODE_PRIVATE);}
        private PackageInstaller installer(){return context.getPackageManager().getPackageInstaller();}
        public boolean canInstall(){return context.getPackageManager().canRequestPackageInstalls();}
        public void openInstallSettings(){context.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:"+context.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        public JSONObject release(ReleaseInfo.Source source) throws IOException {
            HttpURLConnection c=(HttpURLConnection)new URL(source.apiUrl()).openConnection(); // the only metadata address; never a redirect
            c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/vnd.github+json");
            try{
                if(c.getResponseCode()!=200)return null; // 404 = no releases yet, 3xx = refused
                try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))!=-1){if(bytes.size()+n>262144)throw new IOException("Odpowiedź za duża");bytes.write(chunk,0,n);}
                    return new JSONObject(bytes.toString("UTF-8"));
                }
            }catch(org.json.JSONException e){throw new IOException("Nieprawidłowa odpowiedź GitHub");}
            finally{c.disconnect();}
        }
        public void download(String url,File target,long expected) throws IOException {Updater.download(url,target,expected,ReleaseInfo::allowedHost);}
        public String check(File file,String version,String expectedPackage){
            android.content.pm.PackageInfo pi=context.getPackageManager().getPackageArchiveInfo(file.getPath(),0);
            if(pi==null||!expectedPackage.equals(pi.packageName)||!version.equals(pi.versionName))return "Nieprawidłowy plik wydania";
            // only our own update has to move forward; another package may be installed for the first time
            if(context.getPackageName().equals(expectedPackage)&&pi.getLongVersionCode()<=BuildConfig.VERSION_CODE)return "Nieprawidłowy plik wydania";
            return "ok";
        }
        public int createSession(long size,String packageName) throws IOException {
            PackageInstaller.SessionParams p=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            // the package being installed, which is not always us: the installer refuses a session that names another
            p.setAppPackageName(packageName);p.setSize(size);
            return installer().createSession(p);
        }
        public void write(int session,File file) throws IOException {
            try(PackageInstaller.Session s=installer().openSession(session)){
                try(OutputStream out=s.openWrite("helios.apk",0,file.length());InputStream in=new FileInputStream(file)){
                    byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);s.fsync(out);
                }
            }
        }
        public void commit(int session,String operation,File file) throws IOException {
            Intent i=new Intent(context,UpdateReceiver.class).setAction(ACTION).putExtra("operation",operation).putExtra("file",file.getPath());
            android.app.PendingIntent pi=android.app.PendingIntent.getBroadcast(context,session,i,android.app.PendingIntent.FLAG_MUTABLE|android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            try(PackageInstaller.Session s=installer().openSession(session)){s.commit(pi.getIntentSender());}
        }
        public void abandon(int session){try{installer().abandonSession(session);}catch(Exception ignored){}}
        public Boolean sealed(int session){
            for(PackageInstaller.SessionInfo info:installer().getMySessions())if(info.getSessionId()==session)return info.isSealed();
            return null;
        }
        public JSONObject record(){String raw=prefs().getString("update_operation",null);if(raw==null)return null;try{return new JSONObject(raw);}catch(Exception e){return null;}}
        public boolean saveRecord(JSONObject record){return (record==null?prefs().edit().remove("update_operation"):prefs().edit().putString("update_operation",record.toString())).commit();}
        public File cacheDir(){return context.getCacheDir();}
        public void deleteFile(String path){if(path!=null)new File(path).delete();}
        public void status(String text){statusSink.accept(text);}
        public void launch(Intent intent){context.startActivity(intent);}
    }
}
