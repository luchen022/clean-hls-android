package com.example.cleanhls;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Small VOD HLS client. Supports MPEG-TS, master playlists, byte ranges and AES-128. */
public final class Hls {
    public interface Progress { void update(int done, int total) throws IOException; }
    public interface Cancel { boolean cancelled(); }
    public static final class Segment {
        public URI uri;
        public URI keyUri;
        public byte[] iv;
        public long sequence;
        public long rangeStart = -1, rangeLength = -1;
        public Segment(URI uri) { this.uri = uri; }
    }
    public static final class Playlist {
        public final List<Segment> segments = new ArrayList<>();
        public URI variant;
        public URI init;
        public boolean endList;
    }
    private final Map<String,String> headers;
    private final Cancel cancel;
    public Hls(Map<String,String> headers, Cancel cancel) {
        this.headers = headers;
        this.cancel = cancel;
    }
    private HttpURLConnection open(URI uri, long start, long size) throws IOException {
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
            throw new IOException("仅支持 HTTP / HTTPS 地址");
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) CleanHLS/1.0");
        for (Map.Entry<String,String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (start >= 0) c.setRequestProperty("Range", "bytes=" + start + "-" + (start + size - 1));
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); throw new IOException("HTTP " + code + "：" + uri.getHost()); }
        if (start >= 0 && code != 206) { c.disconnect(); throw new IOException("服务器不支持分片 Range 请求"); }
        return c;
    }
    private byte[] fetch(URI uri, long start, long size, int max) throws IOException {
        HttpURLConnection c = open(uri,start,size);
        try (InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[16384]; int n;
            while ((n=in.read(b))!=-1) {
                if (cancel.cancelled()) throw new IOException("已取消");
                out.write(b,0,n);
                if (out.size()>max) throw new IOException("播放列表或密钥过大");
            }
            return out.toByteArray();
        } finally { c.disconnect(); }
    }
    private Playlist loadPlaylist(URI uri) throws IOException {
        HttpURLConnection c=open(uri,-1,-1);
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))) {
            try { return parseLines(c.getURL().toURI(),reader,cancel); }
            catch(URISyntaxException e) { throw new IOException("跳转后的地址无效",e); }
        } finally {c.disconnect();}
    }
    private static Map<String,String> attrs(String s) {
        Map<String,String> result=new HashMap<>();
        // Attribute commas inside quoted values are not separators.
        int i=0;
        while(i<s.length()) {
            while(i<s.length() && (s.charAt(i)==',' || Character.isWhitespace(s.charAt(i)))) i++;
            int eq=s.indexOf('=',i); if(eq<0) break;
            String k=s.substring(i,eq).trim(); i=eq+1;
            String v;
            if (i<s.length() && s.charAt(i)=='"') {
                int end=s.indexOf('"',++i); if(end<0) break;
                v=s.substring(i,end); i=end+1;
            } else {
                int end=s.indexOf(',',i); if(end<0) end=s.length();
                v=s.substring(i,end).trim(); i=end;
            }
            result.put(k,v);
        }
        return result;
    }
    private static byte[] iv(String hex, long sequence) throws IOException {
        byte[] result=new byte[16];
        if (hex == null) {
            for(int i=15;i>=8;i--) { result[i]=(byte)sequence; sequence >>>= 8; }
        } else {
            if(hex.startsWith("0x")||hex.startsWith("0X")) hex=hex.substring(2);
            if(hex.length()>32 || (hex.length()&1)!=0) throw new IOException("无效的 AES IV");
            for(int i=0;i<hex.length()/2;i++) {
                try { result[16-hex.length()/2+i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16); }
                catch(NumberFormatException e) { throw new IOException("无效的 AES IV",e); }
            }
        }
        return result;
    }
    public static Playlist parse(URI base, String content) throws IOException {
        return parseLines(base,new BufferedReader(new StringReader(content)),()->false);
    }
    private static Playlist parseLines(URI base, BufferedReader reader, Cancel cancel) throws IOException {
        String first=reader.readLine();
        if(first==null || !first.replace("\uFEFF","").trim().equals("#EXTM3U"))
            throw new IOException("不是有效的 M3U8 列表（地址可能返回网页或视频文件）");
        Playlist p=new Playlist();
        long sequence=0, nextRangeStart=0, rangeLength=-1, rangeStart=-1;
        long bestBandwidth=-1, pendingBandwidth=-1;
        URI key=null; String keyIv=null;
        String raw;
        while((raw=reader.readLine())!=null) {
            if(cancel.cancelled()) throw new IOException("已取消");
            String line=raw.trim(); if(line.isEmpty()) continue;
            if(line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) sequence=Long.parseLong(line.substring(22).trim());
            else if(line.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String,String> a=attrs(line.substring(18));
                try { pendingBandwidth=Long.parseLong(a.getOrDefault("BANDWIDTH","0")); }
                catch(NumberFormatException e) { pendingBandwidth=0; }
            } else if(line.startsWith("#EXT-X-KEY:")) {
                Map<String,String> a=attrs(line.substring(11)); String method=a.get("METHOD");
                if("NONE".equals(method)) { key=null; keyIv=null; }
                else if("AES-128".equals(method) && a.get("URI")!=null) {
                    key=base.resolve(a.get("URI")); keyIv=a.get("IV");
                } else throw new IOException("暂不支持的加密方式："+method+"（DRM 不支持）");
            } else if(line.startsWith("#EXT-X-MAP:")) {
                Map<String,String> a=attrs(line.substring(11));
                if(a.get("URI")!=null) p.init=base.resolve(a.get("URI"));
                if(a.containsKey("BYTERANGE")) throw new IOException("暂不支持初始化片段的 BYTERANGE");
            } else if(line.startsWith("#EXT-X-BYTERANGE:")) {
                String[] parts=line.substring(17).split("@");
                rangeLength=Long.parseLong(parts[0].trim());
                rangeStart=parts.length>1?Long.parseLong(parts[1].trim()):nextRangeStart;
            } else if(line.equals("#EXT-X-ENDLIST")) p.endList=true;
            else if(!line.startsWith("#")) {
                URI resolved=base.resolve(line);
                if(pendingBandwidth>=0) {
                    if(pendingBandwidth>bestBandwidth) { p.variant=resolved; bestBandwidth=pendingBandwidth; }
                    pendingBandwidth=-1;
                } else {
                    Segment s=new Segment(resolved); s.sequence=sequence++;
                    s.keyUri=key; s.iv=key==null?null:iv(keyIv,s.sequence);
                    s.rangeStart=rangeStart; s.rangeLength=rangeLength;
                    p.segments.add(s);
                    if(rangeLength>=0) nextRangeStart=rangeStart+rangeLength;
                    rangeLength=-1; rangeStart=-1;
                }
            }
        }
        return p;
    }
    public Playlist resolve(URI url) throws IOException {
        for(int depth=0;depth<5;depth++) {
            Playlist p=loadPlaylist(url);
            if(p.variant==null) {
                if(p.segments.isEmpty()) throw new IOException("列表内没有视频片段");
                return p;
            }
            url=p.variant;
        }
        throw new IOException("播放列表嵌套过深");
    }
    public void download(Playlist p, File output, Progress progress) throws IOException {
        Map<URI,byte[]> keys=new HashMap<>();
        try(OutputStream out=new BufferedOutputStream(new FileOutputStream(output))) {
            if(p.init!=null) write(p.init,-1,-1,null,null,out);
            int done=0;
            for(Segment s:p.segments) {
                if(cancel.cancelled()) throw new IOException("已取消");
                byte[] key=null;
                if(s.keyUri!=null) {
                    key=keys.get(s.keyUri);
                    if(key==null) { key=fetch(s.keyUri,-1,-1,1024); if(key.length!=16) throw new IOException("AES-128 密钥长度不正确"); keys.put(s.keyUri,key); }
                }
                write(s.uri,s.rangeStart,s.rangeLength,key,s.iv,out);
                progress.update(++done,p.segments.size());
            }
        }
    }
    private void write(URI uri,long start,long size,byte[] key,byte[] iv,OutputStream out) throws IOException {
        HttpURLConnection c=open(uri,start,size);
        try(InputStream raw=new BufferedInputStream(c.getInputStream())) {
            InputStream in=raw;
            if(key!=null) {
                try {
                    Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
                    cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
                    in=new CipherInputStream(raw,cipher);
                } catch(GeneralSecurityException e) { throw new IOException("AES 解密失败",e); }
            }
            byte[] buf=new byte[65536]; int n;
            while((n=in.read(buf))!=-1) {
                if(cancel.cancelled()) throw new IOException("已取消");
                out.write(buf,0,n);
            }
        } finally { c.disconnect(); }
    }
}
