package app.quotapocket.widget;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ResetLabel {
 private static int hour(int h,String period){if(period==null||period.isEmpty())return h;if(h<1||h>12)throw new IllegalArgumentException();return h%12+(period.matches("下午|晚上|中午|PM|pm")?12:0);}
 public static long exact(String text){
  if(text==null)return 0;
  try{Matcher iso=Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,3})?)?(?:Z|[+-]\\d{2}:\\d{2})").matcher(text);if(iso.find())return OffsetDateTime.parse(iso.group()).toInstant().toEpochMilli();
   Matcher m=Pattern.compile("(\\d{4})年\\s*(\\d{1,2})月\\s*(\\d{1,2})日.*?(上午|下午|晚上|中午|凌晨|早上)?\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?.*?\\[GMT([+-]\\d{1,2})(?::(\\d{2}))?\\]").matcher(text);
   if(m.find()){int sign=Integer.parseInt(m.group(8));int offset=sign*3600+Integer.signum(sign)*Integer.parseInt(m.group(9)==null?"0":m.group(9))*60;return LocalDateTime.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),hour(Integer.parseInt(m.group(5)),m.group(4)),Integer.parseInt(m.group(6)),Integer.parseInt(m.group(7)==null?"0":m.group(7))).toInstant(ZoneOffset.ofTotalSeconds(offset)).toEpochMilli();}
  }catch(Exception ignored){}return 0;
 }
 public static String format(String text,long resetAt,long now){try{return compute(text,resetAt,now);}catch(Exception ignored){return "重置時間待確認";}}
 private static String compute(String text,long resetAt,long now){
  long target=resetAt>0?resetAt:exact(text);
  if(target>0){if(target<=now)return "重置待確認";long minutes=(target-now+59999)/60000,days=minutes/1440,hours=minutes%1440/60;
   if(days>0)return days+"天"+hours+"時後重置";if(hours>0)return hours+"時"+(minutes%60)+"分後重置";return minutes+"分後重置";}
  if(text==null||text.trim().isEmpty())return "重置時間未提供";
  if(text.toLowerCase(Locale.US).contains("starts with your first message"))return "對話後開始計時";
  String[] days={"Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"};
  for(int i=0;i<days.length;i++){Matcher day=Pattern.compile(days[i]+"\\s+(\\d{1,2}):(\\d{2})\\s*(AM|PM)",Pattern.CASE_INSENSITIVE).matcher(text);if(day.find())return "週"+"一二三四五六日".charAt(i)+" "+String.format(Locale.US,"%02d:%s",hour(Integer.parseInt(day.group(1)),day.group(3).toUpperCase(Locale.US)),day.group(2))+"重置";}
  Matcher clock=Pattern.compile("(?:(\\d{1,2})月(\\d{1,2})日)?\\s*(上午|下午|晚上|中午|凌晨|早上)?\\s*(\\d{1,2}):(\\d{2})").matcher(text);
  if(clock.find()){String date=clock.group(1)!=null?clock.group(1)+"/"+clock.group(2)+" ":"";return date+String.format(Locale.US,"%02d:%s",hour(Integer.parseInt(clock.group(4)),clock.group(3)),clock.group(5))+"重置";}
  return text.replaceAll("^(重設時間|重置時間|重设时间)[:：]\\s*","").replaceAll("(?i)^Resets?\\s*","").trim();
 }
}
