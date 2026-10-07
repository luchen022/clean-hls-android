package com.example.cleanhls;

import android.app.*;
import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;

public final class DownloadService extends Service {
    public static volatile String status="准备就绪";
    public static volatile int percent=0;
    public static volatile boolean running=false;
    private static final String CHANNEL="download";
    private volatile boolean cancelled;
    private Thread worker;

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel=new NotificationChannel(CHANNEL,"视频下载",NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private Notification note(String message,int progress) {
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,CHANNEL)
            .setContentTitle("清爽 M3U8 下载").setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(pi)
            .setOngoing(running);
        if(running) b.setProgress(100,progress,progress==0);
        return b.build();
    }
    private void update(String message,int progress) {
        status=message; percent=progress;
        getSystemService(NotificationManager.class).notify(1,note(message,progress));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null) return START_NOT_STICKY;
        if("cancel".equals(intent.getAction())) { cancelled=true; return START_NOT_STICKY; }
        if(running) return START_NOT_STICKY;
        final String url=intent.getStringExtra("url");
        final String headerText=intent.getStringExtra("headers");
        final String format=intent.getStringExtra("format");
        final Uri dest=intent.getParcelableExtra("dest");
        if(url==null||dest==null) { stopSelf(startId); return START_NOT_STICKY; }
        running=true; cancelled=false;
        startForeground(1,note("正在读取播放列表",0));
        worker=new Thread(() -> run(url,headerText,format,dest,startId),"HlsDownloader");
        worker.start();
        return START_NOT_STICKY;
    }
    private void run(String url,String headerText,String format,Uri dest,int startId) {
        File ts=new File(getCacheDir(),"download-"+System.currentTimeMillis()+".ts");
        File mp4=new File(getCacheDir(),"output-"+System.currentTimeMillis()+".mp4");
        try {
            Map<String,String> headers=parseHeaders(headerText);
            Hls client=new Hls(headers,()->cancelled);
            update("正在读取播放列表",0);
            Hls.Playlist playlist=client.resolve(URI.create(url));
            if(!playlist.endList) throw new IOException("当前是直播流，暂不支持持续录制");
            client.download(playlist,ts,(done,total)->update("正在下载："+done+" / "+total,done*90/total));
            if(cancelled) throw new IOException("已取消");
            File result=ts;
            if("mp4".equals(format)) {
                update("下载完成，正在封装 MP4",92);
                mux(ts,mp4);
                result=mp4;
            }
            try(InputStream in=new BufferedInputStream(new FileInputStream(result));
                OutputStream out=getContentResolver().openOutputStream(dest,"w")) {
                if(out==null) throw new IOException("无法打开保存文件");
                byte[] buf=new byte[65536]; int n;
                while((n=in.read(buf))!=-1) { if(cancelled) throw new IOException("已取消"); out.write(buf,0,n); }
            }
            running=false;
            update("已保存到你选择的位置",100);
        } catch(Exception e) {
            try { getContentResolver().delete(dest,null,null); } catch(Exception ignored) {}
            running=false;
            update("失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),0);
        } finally {
            ts.delete(); mp4.delete();
            running=false;
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf(startId);
        }
    }
    public static Map<String,String> parseHeaders(String text) throws IOException {
        Map<String,String> result=new LinkedHashMap<>();
        if(text==null||text.trim().isEmpty()) return result;
        for(String line:text.split("\n")) {
            if(line.trim().isEmpty()) continue;
            int pos=line.indexOf(':');
            if(pos<=0 || line.substring(0,pos).matches(".*[\\r\\n\\s].*"))
                throw new IOException("请求头格式应为每行 Key: Value");
            String key=line.substring(0,pos).trim(), value=line.substring(pos+1).trim();
            if(value.contains("\r")) throw new IOException("请求头格式错误");
            result.put(key,value);
        }
        return result;
    }
    private void mux(File source,File target) throws IOException {
        MediaExtractor ex=new MediaExtractor(); MediaMuxer mx=null; boolean started=false;
        try {
            ex.setDataSource(source.getAbsolutePath());
            mx=new MediaMuxer(target.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] mapping=new int[ex.getTrackCount()]; Arrays.fill(mapping,-1);
            int valid=0;
            for(int i=0;i<ex.getTrackCount();i++) {
                MediaFormat f=ex.getTrackFormat(i); String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null && (mime.startsWith("video/")||mime.startsWith("audio/"))) {
                    mapping[i]=mx.addTrack(f); ex.selectTrack(i); valid++;
                }
            }
            if(valid==0) throw new IOException("没有可封装成 MP4 的音视频轨道，请选择 TS 格式重试");
            mx.start(); started=true;
            ByteBuffer data=ByteBuffer.allocateDirect(2*1024*1024);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(true) {
                if(cancelled) throw new IOException("已取消");
                int track=ex.getSampleTrackIndex(); if(track<0) break;
                long size=ex.getSampleSize();
                if(size>32*1024*1024) throw new IOException("单个视频帧过大");
                if(size>data.capacity()) data=ByteBuffer.allocateDirect((int)size+4096);
                data.clear(); int count=ex.readSampleData(data,0);
                if(count<0) break;
                if(mapping[track]>=0) {
                    info.offset=0; info.size=count; info.presentationTimeUs=ex.getSampleTime();
                    info.flags=ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC;
                    mx.writeSampleData(mapping[track],data,info);
                }
                ex.advance();
            }
        } catch(RuntimeException e) {
            throw new IOException("MP4 封装失败，请选择 TS 格式重试",e);
        } finally {
            ex.release();
            if(mx!=null) { if(started) try{mx.stop();}catch(RuntimeException ignored){} mx.release(); }
        }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
