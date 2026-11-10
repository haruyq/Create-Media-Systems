package org.haruyq.createmediasystems.playback;

import java.net.URI;
import java.util.function.DoubleFunction;

/** Rebase the actual HTTP request together with its media timestamp, never just the displayed clock. */
final class StreamRequestTimeline implements DoubleFunction<String> {
    private final DoubleFunction<String> origin;
    private final double offset;
    private StreamRequestTimeline(DoubleFunction<String> origin,double offset){this.origin=origin;this.offset=offset;}
    static DoubleFunction<String> resume(DoubleFunction<String> request,double from,double to) {
        return request instanceof StreamRequestTimeline previous
                ?new StreamRequestTimeline(previous.origin,previous.offset+to-from)
                :new StreamRequestTimeline(request,to-from);
    }
    @Override public String apply(double seconds){return origin.apply(offset+seconds);}
    static double requestedPosition(URI uri) {
        String query=uri.getRawQuery();if(query==null)return 0;
        for(String field:query.split("&"))if(field.startsWith("start=")) {
            try{return Double.parseDouble(field.substring(6));}catch(NumberFormatException ignored){return -1;}
        }
        return 0;
    }
}
