package pl.mateusz.helios;

import java.io.*;
import java.net.*;
import org.json.JSONObject;

/** The clock side of /api/helios/pair (SPEC 0.10 pkt 3-4): plain HttpURLConnection, no redirects, bounded bodies. */
final class PairingClient {
    static final class Result {final int status;final JSONObject json;Result(int status,JSONObject json){this.status=status;this.json=json;}}
    private PairingClient(){}
    /** 200 = the Helios integration >= 0.8 answers here, 404 = nothing (or an older integration), -1 = no answer. */
    static int probe(String baseUrl){
        HttpURLConnection c=null;
        try{c=(HttpURLConnection)new URL(baseUrl.replaceAll("/$","")+"/api/helios/pair").openConnection();c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);
            int status=c.getResponseCode();
            if(status==200){JSONObject body=read(c.getInputStream());return body!=null&&body.optInt("protocol")==2?200:404;}
            return status;
        }catch(Exception e){return -1;}finally{if(c!=null)c.disconnect();}
    }
    /** POST with the connect timeout of 8 s and a 60 s read: longer than the integration's 30 + 10 + 10 s transaction budget. */
    static Result pair(String baseUrl,JSONObject body){
        HttpURLConnection c=null;
        try{
            byte[] bytes=body.toString().getBytes("UTF-8");
            c=(HttpURLConnection)new URL(baseUrl.replaceAll("/$","")+"/api/helios/pair").openConnection();c.setConnectTimeout(8000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            int status=c.getResponseCode();
            InputStream in=status>=400?c.getErrorStream():c.getInputStream();
            return new Result(status,in==null?null:read(in));
        }catch(Exception e){return new Result(-1,null);}finally{if(c!=null)c.disconnect();}
    }
    private static JSONObject read(InputStream in){
        try(InputStream s=in;ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] chunk=new byte[4096];int n;while((n=s.read(chunk))!=-1){if(bytes.size()+n>65536)return null;bytes.write(chunk,0,n);}
            return new JSONObject(bytes.toString("UTF-8"));
        }catch(Exception e){return null;}
    }
    static String message(int status){
        switch(status){
            case 401:return "Kod nieprawidłowy lub wygasł";
            case 409:return "HA nie dokończył parowania - spróbuj ponownie";
            case 503:return "HA nie jest gotowy (brak pipeline Assist lub błąd tożsamości)";
            case -1:return "Brak połączenia z HA";
            default:return "HA odpowiedział błędem "+status;
        }
    }
}
