package pl.mateusz.helios;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Binds cover fetches to a generation: a late result for an older URL, a cleared cover or an earlier session is dropped,
 * the same URL is fetched once per session, and a failed fetch is retried only after RETRY_AFTER_MS. Pure logic, no threads.
 */
final class ArtworkLoader<T> {
    interface Fetcher {void fetch(String url,int generation);}
    static long RETRY_AFTER_MS=30_000;
    private final Fetcher fetcher;private final Consumer<T> onResult;private final LongSupplier clock;private final boolean clearOnChange;
    private int generation;private String currentUrl,failedUrl;private long failedAt;

    ArtworkLoader(Fetcher fetcher,Consumer<T> onResult,LongSupplier clock){this(fetcher,onResult,clock,true);}
    /** clearOnChange=false keeps the last delivered value visible while the next one loads (backgrounds: never a flash of plain colour). */
    ArtworkLoader(Fetcher fetcher,Consumer<T> onResult,LongSupplier clock,boolean clearOnChange){this.fetcher=fetcher;this.onResult=onResult;this.clock=clock;this.clearOnChange=clearOnChange;}

    /** New URL: publishes an empty cover at once (never the previous track's image next to the new title) and starts one fetch. */
    synchronized void request(String url){
        if(url==null||url.isEmpty()){clear();return;}
        if(url.equals(currentUrl))return;
        if(url.equals(failedUrl)&&clock.getAsLong()-failedAt<RETRY_AFTER_MS)return;
        currentUrl=url;failedUrl=null;int gen=++generation;
        if(clearOnChange)onResult.accept(null);fetcher.fetch(url,gen);
    }
    /** Session ended, stop, disconnect or explicit null: nothing in flight is valid any more; the same URL fetches again later. */
    synchronized void clear(){generation++;currentUrl=null;failedUrl=null;onResult.accept(null);}
    /** Fetcher result; null means failure. Ignored unless it belongs to the current generation. */
    synchronized void deliver(int generation,T value){
        if(generation!=this.generation)return;
        if(value==null){failedUrl=currentUrl;failedAt=clock.getAsLong();currentUrl=null;return;}
        onResult.accept(value);
    }
}
