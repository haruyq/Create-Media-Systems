package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleFunction;
import org.junit.jupiter.api.Test;

class StreamRequestTimelineTest {
    static final class Api implements AutoCloseable {
        final HttpServer server;final AtomicInteger requests=new AtomicInteger();
        Api() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/stream",exchange->{
                requests.incrementAndGet();double start=StreamRequestTimeline.requestedPosition(exchange.getRequestURI());
                var pcm=ByteBuffer.allocate(Pcm.BYTES_PER_SECOND*3).order(ByteOrder.LITTLE_ENDIAN);
                for(int frame=0;frame<Pcm.RATE*3;frame++)pcm.putShort((short)Math.round((start+frame/(double)Pcm.RATE)*100));
                exchange.sendResponseHeaders(200,pcm.capacity());try(var out=exchange.getResponseBody()){out.write(pcm.array());}
            });server.start();
        }
        DoubleFunction<String> from(double start){return offset->"http://127.0.0.1:"+server.getAddress().getPort()+"/stream?start="+(start+offset);}
        Feed.Reader read(String key,double start,DoubleFunction<String> request) {
            var reader=Feed.read(key,start,offset->URI.create(request.apply(offset)).toURL().openStream(),reason->{});
            reader.prebuffer(Pcm.BYTES_PER_SECOND*3,System.currentTimeMillis()+5000);return reader;
        }
        public void close(){server.stop(0);}
    }
    static short sample(Feed.Reader reader){return ByteBuffer.wrap(reader.read(2)).order(ByteOrder.LITTLE_ENDIAN).getShort();}

    @Test void lateJoinAndRetryFetchTheActualAudioAtTheirNewPositionsInsteadOfRelabelingTheBeginning() throws Exception {
        try(var api=new Api()) {
            var request=api.from(31);var first=api.read(UUID.randomUUID().toString(),31,request);
            assertEquals(3100,sample(first),"The real HTTP response must contain the 31-second samples, not samples from zero");first.close();
            var resumed=StreamRequestTimeline.resume(request,31,42.75);var second=api.read(UUID.randomUUID().toString(),42.75,resumed);
            assertEquals(4275,sample(second),"Retry must update the HTTP start parameter as well as the media clock");second.close();
            assertEquals(2,api.requests.get());
        }
    }
    @Test void separateEmitterPositionsShareOnlyDownloadBytesWithoutSharingTheirReadHeads() throws Exception {
        try(var api=new Api()) {
            String key=UUID.randomUUID().toString();var first=api.read(key,31,api.from(31));var second=api.read(key,32,api.from(32));
            assertEquals(3100,sample(first));assertEquals(3200,sample(second));
            assertEquals(1,api.requests.get(),"Independent physical CDs must not require duplicate downloads of bytes already buffered");
            first.close();second.close();
        }
    }
    @Test void repeatedRetriesKeepTheirAbsoluteRequestOriginWithoutBuildingANestedCallbackChain() {
        DoubleFunction<String> request=offset->"http://localhost/stream?start="+(31.25+offset);double from=31.25;
        for(int i=1;i<=12000;i++){double to=i*.125;request=StreamRequestTimeline.resume(request,from,to);from=to;}
        assertEquals(1500,StreamRequestTimeline.requestedPosition(URI.create(request.apply(0))),1e-9);
        assertEquals(0,StreamRequestTimeline.requestedPosition(URI.create(request.apply(-1500))),1e-9,"The download cache origin stays the top of the same track");
    }
}
