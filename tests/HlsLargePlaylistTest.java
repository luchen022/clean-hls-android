import com.example.cleanhls.Hls;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public final class HlsLargePlaylistTest {
    public static void main(String[] args) throws Exception {
        final int count = 200000;
        StringBuilder text = new StringBuilder("#EXTM3U\n");
        for (int i=0; i<count; i++) text.append("#EXTINF:1,\nsegment").append(i).append(".ts\n");
        text.append("#EXT-X-ENDLIST\n");
        byte[] playlist = text.toString().getBytes(StandardCharsets.UTF_8);
        if (playlist.length <= 4 * 1024 * 1024) throw new AssertionError("test playlist too small");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/large.m3u8", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
            exchange.sendResponseHeaders(200,playlist.length);
            try (java.io.OutputStream out=exchange.getResponseBody()) { out.write(playlist); }
        });
        server.createContext("/bad", exchange -> {
            byte[] bad="<html>wrong URL</html>\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bad.length);
            try (java.io.OutputStream out=exchange.getResponseBody()) { out.write(bad); }
        });
        server.start();
        try {
            URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
            Hls hls=new Hls(Collections.emptyMap(),()->false);
            Hls.Playlist p=hls.resolve(base.resolve("large.m3u8"));
            if (p.segments.size()!=count || !p.endList) throw new AssertionError("large playlist was truncated");
            if (!p.segments.get(count-1).uri.equals(base.resolve("segment"+(count-1)+".ts")))
                throw new AssertionError("last segment URI is incorrect");
            try {
                hls.resolve(base.resolve("bad"));
                throw new AssertionError("HTML was accepted as a playlist");
            } catch (java.io.IOException expected) {
                if (!expected.getMessage().contains("不是有效的 M3U8")) throw expected;
            }
            System.out.println("Large playlist and invalid response checks passed");
        } finally { server.stop(0); }
    }
}
