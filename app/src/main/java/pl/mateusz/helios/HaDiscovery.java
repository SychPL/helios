package pl.mateusz.helios;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

/** mDNS discovery of Home Assistant (SPEC 0.10 pkt 3): serial resolves, lost services dropped, results of an old generation ignored. */
final class HaDiscovery {
    static final class Server {
        final String name,host,version;final int port;
        Server(String name,String host,int port,String version){this.name=name;this.host=host;this.port=port;this.version=version;}
        String url(){return "http://"+host+":"+port;}
    }
    interface Listener {void onServers(List<Server> servers);}
    static final String TYPE="_home-assistant._tcp.";
    private final NsdManager nsd;private final WifiManager.MulticastLock lock;private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,Server> resolved=new LinkedHashMap<>();private final Set<String> known=new HashSet<>();
    private final Deque<NsdServiceInfo> queue=new ArrayDeque<>();private final Map<String,Integer> retries=new HashMap<>();
    private boolean resolving;private int gen;private NsdManager.DiscoveryListener discovery;

    HaDiscovery(Context context,Listener listener){
        nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        lock=wifi==null?null:wifi.createMulticastLock("helios-mdns");
        this.listener=listener;
    }
    /** Main thread. Idempotent: a second start never stacks a discovery or the multicast lock. */
    void start(){
        stop();
        final int g=++gen;resolved.clear();known.clear();queue.clear();retries.clear();resolving=false;
        if(lock!=null&&!lock.isHeld()){lock.setReferenceCounted(false);lock.acquire();}
        discovery=new NsdManager.DiscoveryListener(){
            public void onStartDiscoveryFailed(String t,int e){}public void onStopDiscoveryFailed(String t,int e){}
            public void onDiscoveryStarted(String t){}public void onDiscoveryStopped(String t){}
            public void onServiceFound(NsdServiceInfo info){main.post(()->{if(g!=gen)return;known.add(info.getServiceName());queue.add(info);drain(g);});}
            public void onServiceLost(NsdServiceInfo info){main.post(()->{if(g!=gen)return;known.remove(info.getServiceName());resolved.remove(info.getServiceName());publish();});}
        };
        if(nsd==null){discovery=null;return;}
        try{nsd.discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,discovery);}catch(Exception e){discovery=null;}
    }
    /** Main thread. Callbacks of the old generation return before touching any state (gen check first). */
    void stop(){
        gen++;queue.clear();resolving=false;
        if(discovery!=null)try{nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){}
        discovery=null;if(lock!=null&&lock.isHeld())lock.release();
    }
    // A resolve still running from the old generation may keep the system resolver busy for a moment after a quick
    // stop()/start(): the new generation's first resolveService then fails with FAILURE_ALREADY_ACTIVE and takes the 2 s retry.
    private void drain(final int g){
        if(resolving||queue.isEmpty()||g!=gen)return;
        final NsdServiceInfo next=queue.poll();resolving=true;
        nsd.resolveService(next,new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo info,int code){main.post(()->{if(g!=gen)return;resolving=false;int n=retries.merge(info.getServiceName(),1,Integer::sum);if(n<=1&&known.contains(info.getServiceName()))main.postDelayed(()->{if(g==gen){queue.add(info);drain(g);}},2000);else drain(g);});}
            public void onServiceResolved(NsdServiceInfo info){main.post(()->{if(g!=gen)return;resolving=false;if(!known.contains(info.getServiceName())){drain(g);return;}
                if(info.getHost()!=null&&info.getHost().getHostAddress()!=null&&!info.getHost().getHostAddress().contains(":")){
                    resolved.put(info.getServiceName(),new Server(txt(info,"location_name",info.getServiceName()),info.getHost().getHostAddress(),info.getPort(),txt(info,"version","")));publish();}
                drain(g);});}
        });
    }
    private static String txt(NsdServiceInfo info,String key,String fallback){byte[] v=info.getAttributes()==null?null:info.getAttributes().get(key);try{return v==null?fallback:new String(v,"UTF-8");}catch(Exception e){return fallback;}}
    private void publish(){listener.onServers(new ArrayList<>(resolved.values()));}
}
