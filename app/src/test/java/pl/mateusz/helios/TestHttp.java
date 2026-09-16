package pl.mateusz.helios;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Minimal HTTP/1.1 server for JVM tests (com.sun.net.httpserver is not on the Android unit-test classpath): one request per connection, Content-Length bodies. */
final class TestHttp implements AutoCloseable {
    static final class Request {final String method,path;final Map<String,String> headers;final byte[] body;Request(String method,String path,Map<String,String> headers,byte[] body){this.method=method;this.path=path;this.headers=headers;this.body=body;}}
    static final class Response {
        final int status;final byte[] body;final Map<String,String> headers=new LinkedHashMap<>();Integer declaredLength;
        Response(int status,byte[] body){this.status=status;this.body=body;}
        static Response json(int status,String json){Response r=new Response(status,json.getBytes(StandardCharsets.UTF_8));r.headers.put("Content-Type","application/json");return r;}
        static Response redirect(String location){Response r=new Response(302,new byte[0]);r.headers.put("Location",location);return r;}
        /** Announces a different length than it sends (a truncated or oversized transfer). */
        Response declare(int length){declaredLength=length;return this;}
    }
    interface Handler {Response handle(Request request) throws IOException;}
    private final ServerSocket socket;private final ExecutorService pool=Executors.newCachedThreadPool();private volatile boolean closed;
    final List<Request> seen=new CopyOnWriteArrayList<>();
    TestHttp(Handler handler) throws IOException {
        socket=new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));
        pool.execute(()->{while(!closed){try{Socket c=socket.accept();pool.execute(()->serve(c,handler));}catch(IOException e){return;}}});
    }
    String url(String path){return "http://127.0.0.1:"+socket.getLocalPort()+path;}
    int port(){return socket.getLocalPort();}
    private void serve(Socket c,Handler handler){
        try(Socket s=c){
            s.setSoTimeout(10000);
            InputStream in=new BufferedInputStream(s.getInputStream());
            String line=readLine(in);if(line==null)return;
            String[] parts=line.split(" ");Map<String,String> headers=new LinkedHashMap<>();
            for(String h=readLine(in);h!=null&&!h.isEmpty();h=readLine(in)){int i=h.indexOf(':');if(i>0)headers.put(h.substring(0,i).trim().toLowerCase(Locale.ROOT),h.substring(i+1).trim());}
            int length=Integer.parseInt(headers.getOrDefault("content-length","0"));
            byte[] body=new byte[length];int off=0;while(off<length){int n=in.read(body,off,length-off);if(n<0)break;off+=n;}
            Request request=new Request(parts[0],parts.length>1?parts[1]:"/",headers,body);seen.add(request);
            Response r=handler.handle(request);
            OutputStream out=s.getOutputStream();
            StringBuilder head=new StringBuilder("HTTP/1.1 "+r.status+" "+(r.status==200?"OK":r.status==302?"Found":"Error")+"\r\n");
            for(Map.Entry<String,String> e:r.headers.entrySet())head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            head.append("Content-Length: ").append(r.declaredLength==null?r.body.length:r.declaredLength).append("\r\nConnection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));out.write(r.body);out.flush();
        }catch(IOException ignored){}
    }
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();int ch;
        while((ch=in.read())!=-1){if(ch=='\n')break;if(ch!='\r')b.write(ch);}
        if(ch==-1&&b.size()==0)return null;
        return b.toString("ISO-8859-1");
    }
    @Override public void close(){closed=true;try{socket.close();}catch(IOException ignored){}pool.shutdownNow();}
}
