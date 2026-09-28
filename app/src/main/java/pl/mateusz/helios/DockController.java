package pl.mateusz.helios;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.*;

/**
 * Dock lamp and charger state through the exported OEM accessory binder (see docs/lamp-control.md).
 * Everything unknown stays null until the OEM reports it; isLedOn=false never means "no dock".
 */
final class DockController {
    interface Listener { void onChanged(); default void onDiagnostic(String event,String detail){} }
    /** OEM factory default lamp level; shown as the setpoint until the first explicit setBrightness (docs/lamp-control.md). */
    static final int DEFAULT_LEVEL=7;
    private static final String SERVICE_DESCRIPTOR="com.google.assistant.IAssistantOemAccessoryService";
    private static final String CONNECTION_DESCRIPTOR="com.google.assistant.IAccessoryConnectionListener";
    private static final int OP_REGISTER_CONNECTION=2,OP_TURN_ON=3,OP_TURN_OFF=4,OP_SET_BRIGHTNESS=5,OP_IS_LED_ON=6,OP_REGISTER_CHARGER=7;
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile IBinder binder;
    private volatile boolean bound,stopped;
    private int backoffSeconds=5;
    private volatile Boolean dockConnected,charging,ledOn;
    private volatile Integer ledBrightness;
    private volatile String padVersion,unavailable="Łączenie z usługą docka…";
    /** SPEC 0.20 pkt 4b: the lamp blinks while an important warning lasts; this is not a lamp state, so HA never sees it. */
    static final long BLINK_ON_MS=800,BLINK_OFF_MS=1200;
    /**
     * Every lamp write (commands, blink ticks, restore, the binder's first read) happens under this lock, so a blink
     * phase can never be read back as the lamp's state and nothing interleaves with a restore.
     */
    private final Object lampLock=new Object();
    private android.os.HandlerThread lampThread;private Handler lamp;
    private volatile boolean blinking;
    /**
     * The state the lamp returns to after the blink; null while nobody could read it (no binder yet). It survives a
     * lost binder, and pendingRestore marks a restore that could not be sent and is owed to the next binder.
     */
    private Boolean restore;private boolean lit,pendingRestore;
    /** Each blink session gets its own tick and number: a tick already taken off the queue by an earlier session sees another number and ends. */
    private int session;private Runnable tick;
    private Runnable blinkTick(final int mine){
        return new Runnable(){public void run(){
            synchronized(lampLock){ // the next tick is posted under the same lock that stops it
                Handler h=lamp;
                if(!blinking||mine!=session||h==null)return;
                lit=!lit;
                if(binder!=null&&unavailable==null)try{
                    if(restore==null){restore=isLedOn();ledOn=restore;changed();} // unknown target (dock replugged, first binder): read it before the first phase touches the lamp, and report it
                    transact(lit?OP_TURN_ON:OP_TURN_OFF,null);
                }catch(Exception ignored){} // no dock just means no blink
                h.postDelayed(this,lit?BLINK_ON_MS:BLINK_OFF_MS); // a quit looper refuses the post quietly
            }
        }};
    }
    private final IBinder connectionListener=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code==2){data.enforceInterface(CONNECTION_DESCRIPTOR);int state=data.readInt();padVersion=data.readString();dockConnected=true;charging=null;listener.onDiagnostic("dock_connect","state="+state+" padVersion="+padVersion);}
            else if(code==3){data.enforceInterface(CONNECTION_DESCRIPTOR);int state=data.readInt();dockConnected=false;charging=null;ledOn=null;ledBrightness=null;listener.onDiagnostic("dock_disconnect","state="+state);}
            else{listener.onDiagnostic("dock_connection_code","code="+code);return super.onTransact(code,data,reply,flags);}
            if(reply!=null)reply.writeNoException();
            changed();return true;
        }
    };
    private final IBinder chargerListener=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code==2){charging=true;listener.onDiagnostic("dock_charge_start","");}else if(code==3){charging=false;listener.onDiagnostic("dock_charge_stop","");}else{listener.onDiagnostic("dock_charger_code","code="+code);return super.onTransact(code,data,reply,flags);}
            if(reply!=null)reply.writeNoException();
            changed();return true;
        }
    };
    private final IBinder.DeathRecipient death=()->{binder=null;unavailable="Usługa docka przerwana";resetUnknown();changed();scheduleBind();};
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder service){
            synchronized(lampLock){ // no blink tick between the new binder and its first read
                binder=service;backoffSeconds=5;
                try{
                    service.linkToDeath(death,0);
                    register(OP_REGISTER_CONNECTION,connectionListener);
                    register(OP_REGISTER_CHARGER,chargerListener);
                    if(blinking){if(restore==null)restore=isLedOn();ledOn=restore;} // no tick touched the lamp without a binder, so the first read is the real state; mid-blink the hardware shows a phase
                    else if(pendingRestore&&restore!=null){transact(restore?OP_TURN_ON:OP_TURN_OFF,null);ledOn=restore;pendingRestore=false;}
                    else ledOn=isLedOn();
                    if(ledBrightness==null)ledBrightness=DEFAULT_LEVEL;unavailable=null;listener.onDiagnostic("dock_service_connected","ledOn="+ledOn);
                }catch(Exception e){binder=null;unavailable="Binder OEM: "+e.getClass().getSimpleName();listener.onDiagnostic("dock_service_error",e.toString());}
            }
            changed();
        }
        @Override public void onServiceDisconnected(ComponentName name){binder=null;unavailable="Usługa docka rozłączona";resetUnknown();changed();}
    };

    DockController(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}
    void start(){stopped=false;bind();}
    void stop(){
        synchronized(lampLock){ // the lamp goes back to its state before the binder is released
            stopped=true;
            if(blinking){blinking=false;restoreLamp();}
            if(lampThread!=null){lampThread.quitSafely();lampThread=null;lamp=null;}
        }
        main.removeCallbacksAndMessages(null);
        IBinder b=binder;binder=null;if(b!=null)try{b.unlinkToDeath(death,0);}catch(Exception ignored){}
        if(bound){try{context.unbindService(connection);}catch(Exception ignored){}bound=false;}
        resetUnknown();
    }
    Boolean dockConnected(){return dockConnected;}
    Boolean charging(){return charging;}
    Boolean ledOn(){return ledOn;}
    Integer ledBrightness(){return ledBrightness;}
    String padVersion(){return padVersion;}
    /** Null when the OEM binder is usable; otherwise the reason the lamp is unavailable. */
    String unavailable(){return unavailable;}

    /** While blinking, on/off only sets the state the lamp returns to - the blink keeps going. */
    void turnOn() throws Exception {switchLamp(true);}
    void turnOff() throws Exception {switchLamp(false);}
    private void switchLamp(boolean on) throws Exception {
        synchronized(lampLock){
            if(blinking){restore=on;ledOn=on;} // the blink keeps going; this is where the lamp returns afterwards
            else if(pendingRestore){restore=on;transact(on?OP_TURN_ON:OP_TURN_OFF,null);pendingRestore=false;ledOn=isLedOn();} // the command replaces the owed restore; if it fails, it is what is owed now
            else{transact(on?OP_TURN_ON:OP_TURN_OFF,null);ledOn=isLedOn();}
        }
        changed();
    }
    boolean blinking(){return blinking;}
    /** Starts or stops the warning blink; stopping puts the lamp back to its state. Idempotent, never throws. */
    void setBlink(boolean on){
        Handler h;
        synchronized(lampLock){
            if(stopped||on==blinking)return;
            blinking=on;
            if(on){if(!pendingRestore)restore=ledOn;lit=false;pendingRestore=false;} // an owed restore is still the state to return to
            else restoreLamp();
            if(lamp==null){lampThread=new android.os.HandlerThread("helios-lamp");lampThread.start();lamp=new Handler(lampThread.getLooper());}
            h=lamp;
            session++;
            if(tick!=null){h.removeCallbacks(tick);tick=null;}
            if(on){tick=blinkTick(session);h.post(tick);}
        }
        listener.onDiagnostic("lamp_blink",on?"start":"stop");
    }
    /** Under lampLock: the lamp as it was before (or as set during) the blink; owed to the next binder when none is usable now. */
    private void restoreLamp(){
        if(restore==null)return; // never read, so never touched: no tick runs without a binder
        if(binder!=null&&unavailable==null)try{transact(restore?OP_TURN_ON:OP_TURN_OFF,null);ledOn=restore;return;}catch(Exception ignored){}
        pendingRestore=true;
    }
    /** Sets the level (1..10) and remembers it as the accepted setpoint; the OEM exposes no brightness readback. */
    void setBrightness(int level) throws Exception {
        if(level<1||level>10)throw new IllegalArgumentException("level");
        transact(OP_SET_BRIGHTNESS,level);ledBrightness=level;changed();
    }
    Boolean isLedOn() throws Exception {
        IBinder b=binder;if(b==null)throw new IllegalStateException("Brak usługi docka");
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(SERVICE_DESCRIPTOR);b.transact(OP_IS_LED_ON,data,reply,0);reply.readException();return reply.readInt()!=0;}
        finally{data.recycle();reply.recycle();}
    }
    private void transact(int code,Integer argument) throws Exception {
        IBinder b=binder;if(b==null)throw new IllegalStateException("Brak usługi docka");
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(SERVICE_DESCRIPTOR);if(argument!=null)data.writeInt(argument);b.transact(code,data,reply,0);reply.readException();}
        finally{data.recycle();reply.recycle();}
    }
    private void register(int code,IBinder callback) throws Exception {
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(SERVICE_DESCRIPTOR);data.writeStrongBinder(callback);binder.transact(code,data,reply,0);reply.readException();}
        finally{data.recycle();reply.recycle();}
    }
    private void bind(){
        if(stopped||bound)return;
        Intent intent=new Intent("com.google.assistant.START_OEM_ACCESSORY_SERVICE");
        intent.setComponent(new ComponentName("com.google.assistant.oemapp","com.google.assistant.oemapp.ScoriaAssistantOemAccessoryService"));
        try{bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);}
        catch(SecurityException e){bound=false;}
        if(!bound){unavailable="Brak fabrycznej usługi docka";listener.onDiagnostic("dock_bind_failed","");changed();scheduleBind();}
    }
    private void scheduleBind(){
        if(stopped)return;
        if(bound){try{context.unbindService(connection);}catch(Exception ignored){}bound=false;}
        main.postDelayed(this::bind,backoffSeconds*1000L);backoffSeconds=Math.min(60,backoffSeconds*2);
    }
    private void resetUnknown(){dockConnected=null;charging=null;ledOn=null;ledBrightness=null;}
    private void changed(){main.post(listener::onChanged);}
}
