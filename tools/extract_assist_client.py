"""One-time migration of the tested transport into the native prototype."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
source=(ROOT/'probes/assist/AssistButtonProbe.java').read_text(encoding='utf-8')
methods=source[source.index('    private final class Socket'):source.index('    public static String run')]
header='''package pl.mateusz.helios;
import android.content.Context;
import android.media.*;
import android.os.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One user-triggered Assist session; audio is never captured while idle. */
public final class AssistClient {
    public interface Listener { void onState(String text); void onEvent(String event,String detail); }
    private final Context ctx;
    private final JSONObject config;
    private final Listener listener;
    private volatile boolean closed, finishAudio;
    public AssistClient(Context ctx,JSONObject config,Listener listener){this.ctx=ctx.getApplicationContext();this.config=config;this.listener=listener;}
    public void run(){runVoice();}
    public void cancel(){closed=true;finishAudio=true;}
    public void finishSpeech(){finishAudio=true;}
    private void state(String text){listener.onState(text);}
    private void log(String event,String detail){
        String token=config.optString("token", "");
        if(!token.isEmpty())detail=detail.replace(token,"[REDACTED]");
        listener.onEvent(event,detail);
    }
'''
target=ROOT/'app/src/main/java/pl/mateusz/helios/AssistClient.java'
target.parent.mkdir(parents=True,exist_ok=True)
target.write_text(header+methods+'}\n',encoding='utf-8')
(ROOT/'local.properties').write_bytes((ROOT.parent/'local.properties').read_bytes())
