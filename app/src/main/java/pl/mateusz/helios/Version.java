package pl.mateusz.helios;

/** Dotted numeric versions ("0.9.10" > "0.9.9"); missing components count as zero. */
final class Version {
    private Version(){}
    static int compare(String a,String b){
        String[] x=a.split("\\."),y=b.split("\\.");
        for(int i=0;i<Math.max(x.length,y.length);i++){
            int p=i<x.length?part(x[i]):0,q=i<y.length?part(y[i]):0;
            if(p!=q)return Integer.compare(p,q);
        }
        return 0;
    }
    private static int part(String s){if(!s.matches("[0-9]{1,9}"))throw new IllegalArgumentException("Nieprawidłowa wersja: "+s);return Integer.parseInt(s);}
}
