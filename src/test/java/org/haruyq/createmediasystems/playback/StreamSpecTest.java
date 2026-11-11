package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.haruyq.createmediasystems.stream.Streams;
import org.junit.jupiter.api.Test;
import net.minecraft.sounds.SoundSource;

class StreamSpecTest {
    private static final StreamSpec.Emitter HEARD = new StreamSpec.Emitter(null, null, () -> true, () -> true, () -> 1, null);

    static StreamSpec spec(double start) {
        return new StreamSpec("https://media.invalid/song.mp3", start, Streams.Channel.MIX, SoundSource.RECORDS, HEARD, s -> {});
    }

    /** The {@code start} a stream request asks for; 0 without one. */
    static double start(URI request) {
        for (String field : request.getRawQuery().split("&")) if (field.startsWith("start=")) return Double.parseDouble(field.substring(6));
        return 0;
    }

    static final class Api implements AutoCloseable {
        final HttpServer server;final AtomicInteger requests=new AtomicInteger();
        Api() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/stream",exchange->{
                requests.incrementAndGet();double start=start(exchange.getRequestURI());
                var pcm=ByteBuffer.allocate(Pcm.BYTES_PER_SECOND*3).order(ByteOrder.LITTLE_ENDIAN);
                for(int frame=0;frame<Pcm.RATE*3;frame++)pcm.putShort((short)Math.round((start+frame/(double)Pcm.RATE)*100));
                exchange.sendResponseHeaders(200,pcm.capacity());try(var out=exchange.getResponseBody()){out.write(pcm.array());}
            });server.start();
        }
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        Feed.Reader read(String key,StreamSpec spec) {
            var reader=Feed.read(key,spec.startSeconds(),offset->URI.create(spec.request(base(),offset)).toURL().openStream(),reason->{});
            reader.prebuffer(Pcm.BYTES_PER_SECOND*3,System.currentTimeMillis()+5000);return reader;
        }
        public void close(){server.stop(0);}
    }
    static short sample(Feed.Reader reader){return ByteBuffer.wrap(reader.read(2)).order(ByteOrder.LITTLE_ENDIAN).getShort();}

    @Test void lateJoinAndRetryFetchTheActualAudioAtTheirNewPositionsInsteadOfRelabelingTheBeginning() throws Exception {
        try(var api=new Api()) {
            var spec=spec(31);var first=api.read(UUID.randomUUID().toString(),spec);
            assertEquals(3100,sample(first),"The real HTTP response must contain the 31-second samples, not samples from zero");first.close();
            var second=api.read(UUID.randomUUID().toString(),spec.startingAt(42.75));
            assertEquals(4275,sample(second),"Retry must update the HTTP start parameter as well as the media clock");second.close();
            assertEquals(2,api.requests.get());
        }
    }
    @Test void separateEmitterPositionsShareOnlyDownloadBytesWithoutSharingTheirReadHeads() throws Exception {
        try(var api=new Api()) {
            String key=spec(31).feedKey(api.base());
            assertEquals(key,spec(32).feedKey(api.base()),"One download for every stream of the track, whatever its start");
            var first=api.read(key,spec(31));var second=api.read(key,spec(32));
            assertEquals(3100,sample(first));assertEquals(3200,sample(second));
            assertEquals(1,api.requests.get(),"Independent physical CDs must not require duplicate downloads of bytes already buffered");
            first.close();second.close();
        }
    }
    @Test void repeatedRetriesKeepTheirAbsoluteRequestOrigin() {
        StreamSpec spec=spec(31.25);
        for(int i=1;i<=12000;i++)spec=spec.startingAt(i*.125);
        assertEquals(1500,start(URI.create(spec.request("http://localhost",0))),1e-9);
        assertEquals(0,start(URI.create(spec.feedKey("http://localhost"))),1e-9,"The download cache origin stays the top of the same track");
    }
}
