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
            binder=service;backoffSeconds=5;
            try{
                service.linkToDeath(death,0);
                register(OP_REGISTER_CONNECTION,connectionListener);
                register(OP_REGISTER_CHARGER,chargerListener);
                ledOn=isLedOn();if(ledBrightness==null)ledBrightness=DEFAULT_LEVEL;unavailable=null;listener.onDiagnostic("dock_service_connected","ledOn="+ledOn);
            }catch(Exception e){binder=null;unavailable="Binder OEM: "+e.getClass().getSimpleName();listener.onDiagnostic("dock_service_error",e.toString());}
            changed();
        }
        @Override public void onServiceDisconnected(ComponentName name){binder=null;unavailable="Usługa docka rozłączona";resetUnknown();changed();}
    };

    DockController(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}
    void start(){stopped=false;bind();}
    void stop(){
        stopped=true;main.removeCallbacksAndMessages(null);
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

    void turnOn() throws Exception {transact(OP_TURN_ON,null);ledOn=isLedOn();changed();}
    void turnOff() throws Exception {transact(OP_TURN_OFF,null);ledOn=isLedOn();changed();}
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
