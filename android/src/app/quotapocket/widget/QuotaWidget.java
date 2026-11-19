package app.quotapocket.widget;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class QuotaWidget extends AppWidgetProvider {
 public static final String REFRESH="app.quotapocket.widget.REFRESH";
 private static final String MODE="widget_display_mode";
 private static final int BOTH_CARDS=-1;
 private static final long SNAPSHOT_MAX_AGE=14L*86400000;

 @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){SyncJob.schedule(c);renderAll(c);SyncJob.refresh(c);}
 @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager manager,int id,Bundle options){renderAll(c);}
 @Override public void onDisabled(Context c){
  AppWidgetManager manager=AppWidgetManager.getInstance(c);
  if(manager.getAppWidgetIds(new ComponentName(c,QuotaWidget.class)).length==0
     && manager.getAppWidgetIds(new ComponentName(c,SingleQuotaWidget.class)).length==0)SyncJob.cancel(c);
 }
 @Override public void onReceive(Context c,Intent intent){super.onReceive(c,intent);if(REFRESH.equals(intent.getAction())){QuotaStore.prefs(c).edit().putString("status","更新中").apply();renderAll(c);SyncJob.refresh(c);}}

 static WidgetDisplayMode displayMode(Context c){
  return WidgetDisplayMode.fromPreference(QuotaStore.prefs(c).getString(MODE,WidgetDisplayMode.AUTO.name()));
 }

 static void setDisplayMode(Context c,WidgetDisplayMode mode){
  QuotaStore.prefs(c).edit().putString(MODE,mode.name()).apply();
  renderAll(c);
 }

 static boolean prefersSingle(Context c){
  WidgetDisplayMode mode=displayMode(c);
  return mode.usesSingleWidget(mode==WidgetDisplayMode.AUTO?availableSources(c):0);
 }

 static int availableSources(Context c){
  int sources=0;
  boolean codex=CodexAccount.connected(c),claude=ClaudeAccount.connected(c);
  if(codex&&!"請重新登入".equals(CodexAccount.status(c)))sources|=WidgetDisplayMode.CHATGPT_SOURCE;
  if(claude&&!"請重新登入".equals(ClaudeAccount.status(c)))sources|=WidgetDisplayMode.CLAUDE_SOURCE;
  SharedPreferences prefs=QuotaStore.prefs(c);
  if(sources==(WidgetDisplayMode.CHATGPT_SOURCE|WidgetDisplayMode.CLAUDE_SOURCE)
     ||!prefs.contains("connection")||"請重新連接".equals(prefs.getString("status","")))return sources;

  long now=System.currentTimeMillis();
  JSONArray snapshots=cloudSnapshots(prefs);
  for(int i=0;i<snapshots.length();i++){
   JSONObject snapshot=snapshots.optJSONObject(i);
   if(snapshot==null||!validAge(now-snapshot.optLong("observedAt",0)))continue;
   JSONArray windows=snapshot.optJSONArray("windows");
   if(windows==null||windows.length()==0)continue;
   switch(snapshot.optString("provider")){
    case "chatgpt": if(!codex)sources|=WidgetDisplayMode.CHATGPT_SOURCE; break;
    case "claude": if(!claude)sources|=WidgetDisplayMode.CLAUDE_SOURCE; break;
    default: break;
   }
  }
  return sources;
 }

 private static JSONArray cloudSnapshots(SharedPreferences prefs){
  try{return new JSONArray(prefs.getString("snapshots","[]"));}
  catch(Exception ignored){return new JSONArray();}
 }

 private static boolean validAge(long age){return age>=-300000&&age<SNAPSHOT_MAX_AGE;}

 static void renderAll(Context c){
  AppWidgetManager manager=AppWidgetManager.getInstance(c);
  int[] dual=manager.getAppWidgetIds(new ComponentName(c,QuotaWidget.class));
  int[] single=manager.getAppWidgetIds(new ComponentName(c,SingleQuotaWidget.class));
  renderWidgets(c,manager,dual,BOTH_CARDS);
  if(single.length>0)renderWidgets(c,manager,single,displayMode(c).singleCardIndex(availableSources(c)));
 }

 private static void renderWidgets(Context c,AppWidgetManager manager,int[] ids,int card){
  for(int id:ids){
   Bundle options=manager.getAppWidgetOptions(id);
   int height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,0);
   int width=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,0);
   manager.updateAppWidget(id,views(c,height,width,card));
  }
 }
 static RemoteViews views(Context c,int height,int width,int card){
  boolean single=card!=BOTH_CARDS;
  RemoteViews v=new RemoteViews(c.getPackageName(),single?R.layout.quota_widget_single:R.layout.quota_widget);
  String[] providers={"chatgpt","claude"},names={"ChatGPT","Claude"};int[] values={R.id.gpt_value,R.id.claude_value},times={R.id.gpt_time,R.id.claude_time},titles={R.id.gpt_name,R.id.claude_name},bars={R.id.gpt_bar,R.id.claude_bar},cards={R.id.gpt_card,R.id.claude_card};
  int[] accents={Color.rgb(117,226,190),Color.rgb(241,182,156)};long now=System.currentTimeMillis();
  SharedPreferences prefs=QuotaStore.prefs(c);
  String syncStatus=prefs.getString("status","尚未連接");
  JSONArray snapshots=cloudSnapshots(prefs);
  boolean useCodex=CodexAccount.connected(c),useClaude=ClaudeAccount.connected(c);
  float fontScale=Math.max(1f,c.getResources().getConfiguration().fontScale);
  float effectiveWidth=(width>0?width:single?58:130)/fontScale;
  boolean narrow=single||effectiveWidth<165;
  boolean compact=(height==0&&single)||(height>0&&height/fontScale<59);
  boolean roomy=height/fontScale>=70;
  for(int p=0;p<providers.length;p++){
   if(single&&p!=card)continue;
   int slot=single?0:p;
   boolean codex=p==0&&useCodex,claude=p==1&&useClaude;
   boolean direct=codex||claude;
   String name=names[p],status=codex?CodexAccount.status(c):claude?ClaudeAccount.status(c):syncStatus;
   String value="—",label=narrow?compactStatus(status):status,description=name+"，"+status;double remaining=0;int accent=accents[p];
   JSONArray source=codex?new JSONArray().put(CodexAccount.snapshot(c)):claude?new JSONArray().put(ClaudeAccount.snapshot(c)):snapshots;
   String provider=codex?"codex":providers[p];
   for(int i=0;i<source.length();i++){JSONObject s=source.optJSONObject(i);if(s==null||!provider.equals(s.optString("provider")))continue;
    long observed=s.optLong("observedAt",0),age=now-observed;double pct=Double.POSITIVE_INFINITY;JSONObject selected=null;
    JSONArray windows=s.optJSONArray("windows");if(windows!=null)for(int n=0;n<windows.length();n++){JSONObject w=windows.optJSONObject(n);if(w!=null){double x=w.optDouble("remainingPct",Double.NaN);if(!Double.isNaN(x)&&x>=0&&x<=100&&x<pct){pct=x;selected=w;}}}
    if(validAge(age)&&Double.isFinite(pct)&&selected!=null){
     boolean weekly="weekly".equals(selected.optString("id"));
     String number=String.format(Locale.US,pct==Math.floor(pct)?"%.0f":"%.1f",pct);
     value=(narrow?"":weekly?"週 ":"期 ")+number+"%";remaining=pct;
     accent=pct<=10?Color.rgb(255,112,145):pct<=25?Color.rgb(250,199,106):accents[p];
     label=ResetLabel.format(selected.optString("resetText",""),selected.optLong("resetAt",0),now);
     boolean stale=age>(claude?50:codex?30:15)*60000||(!"已更新".equals(status)&&!"已連接".equals(status)&&!"更新中".equals(status));
     if(direct&&"請重新登入".equals(status))label=status;
     if(stale)accent=Color.rgb(174,185,200);
     description=name+"，"+(weekly?"本週":"當期")+"剩餘 "+pct+"%，"+label+"。讀取於 "+new SimpleDateFormat("MM/dd HH:mm",Locale.getDefault()).format(new Date(observed))+(stale?"，資料較舊":"");
     if(narrow)label=label.replace("後重置","後").replace("重置時間未提供","重置未提供");
    }
   }
   SpannableString metric=new SpannableString(value);if(value.length()>2&&value.charAt(1)==' ')metric.setSpan(new RelativeSizeSpan(0.5f),0,2,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
   v.setTextViewText(values[slot],metric);v.setTextColor(values[slot],accent);v.setTextViewText(times[slot],label);v.setTextViewText(titles[slot],name);v.setImageViewBitmap(bars[slot],bar(remaining,accent));v.setContentDescription(cards[slot],description);
   float metricSize=compact?22:roomy?28:25;
   if(single&&height>0&&height/fontScale<54)metricSize=19;
   if(narrow) {
    float textWidth=single?effectiveWidth-10:(effectiveWidth-6)/2-6;
    float metricWidth=value.length()>=5?2.86f:value.length()==4?2.53f:1.95f;
    metricSize=Math.min(metricSize,Math.max(14f,textWidth/metricWidth));
   }
   int nameSize=single?(effectiveWidth<56?10:12):narrow?(effectiveWidth<126?11:12):compact?12:roomy?14:13;
   int timeSize=single&&effectiveWidth<56?8:narrow?9:compact?10:roomy?12:11;
   v.setTextViewTextSize(titles[slot],TypedValue.COMPLEX_UNIT_SP,nameSize);
   v.setTextViewTextSize(values[slot],TypedValue.COMPLEX_UNIT_SP,metricSize);
   v.setTextViewTextSize(times[slot],TypedValue.COMPLEX_UNIT_SP,timeSize);
   v.setViewVisibility(bars[slot],compact?View.GONE:View.VISIBLE);
  }
  PendingIntent tap=PendingIntent.getBroadcast(c,0,new Intent(c,QuotaWidget.class).setAction(REFRESH),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  v.setOnClickPendingIntent(R.id.widget_root,tap);
  return v;
 }
 static String compactStatus(String status) {
  if(status.contains("CL-USAGE-TIME"))return "查詢逾時";
  if(status.contains("CL-USAGE-NODATA"))return "查無額度";
  if(status.contains("CL-USAGE-"))return "查詢失敗";
  if("等待首次更新".equals(status))return "等待更新";
  return status;
 }
 static Bitmap bar(double pct,int color){Bitmap b=Bitmap.createBitmap(240,8,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.rgb(53,65,79));canvas.drawRoundRect(new RectF(0,0,240,8),4,4,paint);if(pct>0){paint.setColor(color);canvas.drawRoundRect(new RectF(0,0,(float)(240*pct/100),8),4,4,paint);}return b;}
}
