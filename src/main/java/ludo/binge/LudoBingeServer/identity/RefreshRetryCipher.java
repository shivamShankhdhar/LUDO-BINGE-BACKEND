package ludo.binge.LudoBingeServer.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Retry response can be decrypted only by the holder of the original refresh credential. */
public final class RefreshRetryCipher {
 private RefreshRetryCipher(){}
 public static String encrypt(String token,String value){try{
  byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher cipher=cipher(Cipher.ENCRYPT_MODE,token,iv);
  return Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
 }catch(Exception e){throw new IllegalStateException(e);}}
 public static String decrypt(String token,String value){try{
  String[] parts=value.split("\\.",2);return new String(cipher(Cipher.DECRYPT_MODE,token,Base64.getDecoder().decode(parts[0])).doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);
 }catch(Exception e){throw new IllegalStateException(e);}}
 private static Cipher cipher(int mode,String token,byte[] iv)throws Exception{
  byte[] key=MessageDigest.getInstance("SHA-256").digest(("refresh-retry:"+token).getBytes(StandardCharsets.UTF_8));
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));return cipher;
 }
}
