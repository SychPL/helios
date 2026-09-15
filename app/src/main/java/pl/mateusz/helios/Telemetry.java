package pl.mateusz.helios;

import org.json.JSONObject;

/** Immutable device snapshot sent to HA. null means unknown and stays null in JSON; it never turns into false or 0. */
final class Telemetry {
    final String appVersion,voiceState,padVersion,musicState;
    final int versionCode;
    final long uptimeSeconds;
    final Boolean dockConnected,charging,ledOn;
    final Integer ledBrightness,volumePercent;
    Telemetry(String appVersion,int versionCode,String voiceState,Boolean dockConnected,Boolean charging,Boolean ledOn,Integer ledBrightness,String padVersion,Integer volumePercent,long uptimeSeconds,String musicState){
        this.musicState=musicState;this.appVersion=appVersion;this.versionCode=versionCode;this.voiceState=voiceState;this.dockConnected=dockConnected;this.charging=charging;this.ledOn=ledOn;this.ledBrightness=ledBrightness;this.padVersion=padVersion;this.volumePercent=volumePercent;this.uptimeSeconds=uptimeSeconds;
    }
    JSONObject toJson(){
        try{return fingerprint().put("uptime_seconds",uptimeSeconds);}catch(Exception e){throw new IllegalStateException(e);}
    }
    /** Everything except uptime: two snapshots with equal fingerprints need no new publish. */
    JSONObject fingerprint(){
        try{
            return new JSONObject().put("app_version",appVersion).put("version_code",versionCode).put("voice_state",voiceState)
                .put("dock_connected",nul(dockConnected)).put("charging",nul(charging)).put("led_on",nul(ledOn)).put("led_brightness",nul(ledBrightness))
                .put("pad_version",nul(padVersion)).put("volume_percent",nul(volumePercent)).put("music_state",musicState);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static Object nul(Object value){return value==null?JSONObject.NULL:value;}
    boolean sameAs(Telemetry other){return other!=null&&fingerprint().toString().equals(other.fingerprint().toString());}
}
