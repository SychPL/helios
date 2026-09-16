package pl.mateusz.helios;

import java.net.URI;

/** Typed HA address to a base URL: host, host:port, [v6], or a full http URL; port 8123 when missing; never anything but http and never more than a base url (SPEC 0.10 pkt 3). */
final class AddressInput {
    private AddressInput(){}
    static String parse(String typed){
        String t=typed==null?"":typed.trim();
        if(t.isEmpty())return null;
        String withScheme=t.contains("://")?t:"http://"+t;
        try{
            URI u=new URI(withScheme);
            String scheme=u.getScheme(),host=u.getHost();int port=u.getPort();
            if(host==null||host.isEmpty()||!"http".equals(scheme))return null;
            if(u.getUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||!(u.getRawPath().isEmpty()||u.getRawPath().equals("/")))return null; // a base url only
            if(port==-1)port=8123;
            if(port<1||port>65535)return null;
            return scheme+"://"+host+":"+port; // URI.getHost() keeps the brackets of an IPv6 literal
        }catch(Exception e){return null;}
    }
}
