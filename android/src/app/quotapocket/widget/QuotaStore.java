package app.quotapocket.widget;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.security.KeyStore;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

public final class QuotaStore {
 public static final String ORIGIN="https://quota-pocket-1119.kmsming0619.chatgpt.site";
 private static final String AES="quota_storage_aes_v1";
 static SharedPreferences prefs(Context c){return c.getSharedPreferences("quota",Context.MODE_PRIVATE);}
 static String b64(byte[] b){return Base64.encodeToString(b,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
 static byte[] unb64(String s){return Base64.decode(s,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
 static KeyStore keys()throws Exception{KeyStore k=KeyStore.getInstance("AndroidKeyStore");k.load(null);return k;}
 public static String startPair(Context c)throws Exception{
  KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);java.security.KeyPair pair=generator.generateKeyPair();
  byte[] random=new byte[32];new SecureRandom().nextBytes(random);String state=b64(random);
  prefs(c).edit().putString("pair_state",state).putString("pair_private",seal(pair.getPrivate().getEncoded())).putLong("pair_started",System.currentTimeMillis()).apply();
  return ORIGIN+"/widget-pair?key="+b64(pair.getPublic().getEncoded())+"&state="+state;
 }
 private static synchronized javax.crypto.SecretKey storageKey()throws Exception{
  KeyStore k=keys();if(!k.containsAlias(AES)){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(AES,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}return (javax.crypto.SecretKey)keys().getKey(AES,null);
 }
 public static void finishPair(Context c,String encoded)throws Exception{
  if(encoded==null||encoded.length()>12000||System.currentTimeMillis()-prefs(c).getLong("pair_started",0)>600000)throw new Exception("配對已逾時，請重新連接");
  JSONObject envelope=new JSONObject(new String(unb64(encoded),java.nio.charset.StandardCharsets.UTF_8));
  java.security.PrivateKey privateKey=java.security.KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(unseal(prefs(c).getString("pair_private",""))));
  Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");rsa.init(Cipher.DECRYPT_MODE,privateKey,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
  byte[] aesKey=rsa.doFinal(unb64(envelope.getString("key")));
  Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(aesKey,"AES"),new GCMParameterSpec(128,unb64(envelope.getString("iv"))));
  byte[] clear=aes.doFinal(unb64(envelope.getString("data")));JSONObject config=new JSONObject(new String(clear,java.nio.charset.StandardCharsets.UTF_8));
  String expected=prefs(c).getString("pair_state","");
  if(expected.isEmpty()||!java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),config.getString("state").getBytes(java.nio.charset.StandardCharsets.UTF_8))||!ORIGIN.equals(config.getString("origin")))throw new Exception("配對資料不符，請重新連接");
  prefs(c).edit().putString("connection",seal(clear)).remove("pair_state").remove("pair_private").remove("pair_started").putString("status","已連接").apply();
 }
 public static JSONObject connection(Context c)throws Exception{
  String saved=prefs(c).getString("connection","");if(saved.isEmpty())return null;
  return new JSONObject(new String(unseal(saved),java.nio.charset.StandardCharsets.UTF_8));
 }
 static String seal(byte[] data)throws Exception{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,storageKey());return b64(cipher.getIV())+"."+b64(cipher.doFinal(data));}
 static byte[] unseal(String saved)throws Exception{String[] parts=saved.split("\\.");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,storageKey(),new GCMParameterSpec(128,unb64(parts[0])));return cipher.doFinal(unb64(parts[1]));}
 public static void disconnect(Context c){prefs(c).edit().remove("connection").remove("snapshots").putString("status","尚未連接").apply();QuotaWidget.renderAll(c);}
}
