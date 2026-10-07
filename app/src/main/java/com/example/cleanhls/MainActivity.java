package com.example.cleanhls;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.net.URI;

public final class MainActivity extends Activity {
    private static final int CREATE_FILE=1;
    private EditText url, headers, filename;
    private Spinner format;
    private TextView state;
    private ProgressBar progress;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable() {
        @Override public void run() {
            if(state!=null) { state.setText(DownloadService.status); progress.setProgress(DownloadService.percent); }
            handler.postDelayed(this,450);
        }
    };
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(22),dp(20),dp(24)); scroll.addView(root);
        TextView title=label("清爽 M3U8 下载",25,true); root.addView(title);
        TextView desc=label("无广告 · 无会员 · 本地处理",14,false);
        desc.setTextColor(Color.DKGRAY); root.addView(desc);
        root.addView(label("播放列表地址",16,true));
        url=new EditText(this); url.setSingleLine(false); url.setMinLines(2);
        url.setInputType(17); url.setHint("https://example.com/video/index.m3u8"); root.addView(url);
        root.addView(label("保存文件名",16,true));
        filename=new EditText(this); filename.setSingleLine(true); filename.setText("视频"); root.addView(filename);
        root.addView(label("输出格式",16,true));
        format=new Spinner(this);
        format.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"MP4（无损封装）","TS（原始视频流）"}));
        root.addView(format);
        root.addView(label("可选请求头（每行 Key: Value）",16,true));
        headers=new EditText(this); headers.setMinLines(3);
        headers.setGravity(Gravity.TOP); headers.setHint("Referer: https://example.com/\nCookie: session=..."); root.addView(headers);
        Button start=new Button(this); start.setText("选择位置并开始下载"); root.addView(start);
        start.setOnClickListener(v->choose());
        Button cancel=new Button(this); cancel.setText("取消当前下载"); root.addView(cancel);
        cancel.setOnClickListener(v->{ if(DownloadService.running) { Intent i=new Intent(this,DownloadService.class); i.setAction("cancel"); startService(i); }});
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(18)));
        state=label("准备就绪",14,false); root.addView(state);
        TextView note=label("支持点播 M3U8、相对地址、AES-128、最高码率线路和需 Referer/Cookie 的片段。DRM、直播录制和独立音轨暂未支持。",13,false);
        note.setTextColor(Color.DKGRAY); root.addView(note);
        setContentView(scroll);
        Intent incoming=getIntent();
        if(Intent.ACTION_SEND.equals(incoming.getAction()) && "text/plain".equals(incoming.getType())) {
            String shared=incoming.getStringExtra(Intent.EXTRA_TEXT); if(shared!=null) url.setText(shared);
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=getPackageManager().PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2);
    }
    private int dp(int n) { return (int)(n*getResources().getDisplayMetrics().density+0.5f); }
    private TextView label(String text,int sp,boolean bold) {
        TextView t=new TextView(this); t.setText(text); t.setTextSize(sp);
        if(bold) t.setTypeface(null,1);
        t.setPadding(0,dp(12),0,dp(8)); return t;
    }
    private void choose() {
        if(DownloadService.running) { Toast.makeText(this,"已有下载任务",Toast.LENGTH_SHORT).show(); return; }
        String address=url.getText().toString().trim();
        try {
            URI uri=URI.create(address);
            if(!"http".equalsIgnoreCase(uri.getScheme())&&!"https".equalsIgnoreCase(uri.getScheme())) throw new Exception();
        } catch(Exception e) { url.setError("请输入有效的 HTTP / HTTPS M3U8 地址"); return; }
        String name=filename.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]","_");
        if(name.isEmpty()) {filename.setError("请输入文件名");return;}
        boolean mp4=format.getSelectedItemPosition()==0;
        String ext=mp4?".mp4":".ts";
        if(!name.toLowerCase().endsWith(ext)) name+=ext;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mp4?"video/mp4":"video/mp2t");
        intent.putExtra(Intent.EXTRA_TITLE,name);
        startActivityForResult(intent,CREATE_FILE);
    }
    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if(req!=CREATE_FILE||result!=RESULT_OK||data==null||data.getData()==null) return;
        Uri dest=data.getData();
        Intent service=new Intent(this,DownloadService.class);
        service.putExtra("url",url.getText().toString().trim());
        service.putExtra("headers",headers.getText().toString());
        service.putExtra("format",format.getSelectedItemPosition()==0?"mp4":"ts");
        service.putExtra("dest",dest);
        startForegroundService(service);
    }
    @Override protected void onResume() {super.onResume(); handler.post(refresh);}
    @Override protected void onPause() {handler.removeCallbacks(refresh); super.onPause();}
}
