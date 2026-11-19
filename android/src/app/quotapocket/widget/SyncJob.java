package app.quotapocket.widget;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

public final class SyncJob extends JobService {
 private final ConcurrentHashMap<JobParameters,Thread> running = new ConcurrentHashMap<>();
 static void schedule(Context c){JobScheduler scheduler=c.getSystemService(JobScheduler.class);if(scheduler.getPendingJob(101)==null)scheduler.schedule(new JobInfo.Builder(101,new ComponentName(c,SyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).build());}
 static void refresh(Context c){JobScheduler scheduler=c.getSystemService(JobScheduler.class);if(scheduler.getPendingJob(102)==null)scheduler.schedule(new JobInfo.Builder(102,new ComponentName(c,SyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());}
 static void cancel(Context c){c.getSystemService(JobScheduler.class).cancel(101);}
 @Override public boolean onStartJob(JobParameters params){Thread worker = new Thread(()->{
  try {
   CodexAccount.sync(this);
   if(Thread.currentThread().isInterrupted())return;
   if(!CodexAccount.connected(this)||!ClaudeAccount.connected(this))sync(this);
   QuotaWidget.renderAll(this);
   ClaudeAccount.sync(this,params.getJobId()==102);
   QuotaWidget.renderAll(this);
  }
  finally { if(running.remove(params)!=null)jobFinished(params,false); }
 },"quota-sync");running.put(params,worker);worker.start();return true;}
 @Override public boolean onStopJob(JobParameters params){Thread worker=running.remove(params);if(worker!=null)worker.interrupt();return true;}
 static void sync(Context c){HttpsURLConnection connection=null;
  try{
   JSONObject settings=QuotaStore.connection(c);if(settings==null){QuotaStore.prefs(c).edit().putString("status","等待電腦同步").apply();return;}
   connection=(HttpsURLConnection)new URL(QuotaStore.ORIGIN+"/api/widget").openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(12000);connection.setReadTimeout(12000);
   connection.setRequestProperty("OAI-Sites-Authorization","Bearer "+settings.getString("gateToken"));connection.setRequestProperty("Authorization","Bearer "+settings.getString("widgetToken"));connection.setRequestProperty("Accept","application/json");
   int code=connection.getResponseCode();if(code!=200){QuotaStore.prefs(c).edit().putString("status",code==401||code==403||code==302?"請重新連接":"連線失敗").apply();return;}
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream input=connection.getInputStream()){byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1){bytes.write(b,0,n);if(bytes.size()>64000)throw new Exception("資料過大");}}
   JSONObject payload=new JSONObject(bytes.toString("UTF-8"));QuotaStore.prefs(c).edit().putString("snapshots",payload.getJSONArray("snapshots").toString()).putString("status","已更新").putLong("fetched_at",System.currentTimeMillis()).apply();
  }catch(Exception ignored){QuotaStore.prefs(c).edit().putString("status","離線").apply();}finally{if(connection!=null)connection.disconnect();}
 }
}
