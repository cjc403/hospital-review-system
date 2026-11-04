package cn.demo.review.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.util.*;

public final class PasswordHash {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final int ITERATIONS=210000;
    public static String hash(String password){byte[] salt=new byte[16];RANDOM.nextBytes(salt);return "pbkdf2-sha256$"+ITERATIONS+"$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(derive(password,salt,ITERATIONS));}
    public static boolean matches(String password,String encoded){try{String[] parts=encoded.split("\\$");if(parts.length!=4||!parts[0].equals("pbkdf2-sha256"))return false;int n=Integer.parseInt(parts[1]);if(n<100000||n>1000000)return false;return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),derive(password,Base64.getDecoder().decode(parts[2]),n));}catch(RuntimeException e){return false;}}
    private static byte[] derive(String password,byte[] salt,int n){PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,n,256);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(GeneralSecurityException e){throw new IllegalStateException("密码算法不可用",e);}finally{spec.clearPassword();}}
    public static String token(){byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public static String digest(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
    private PasswordHash(){}
}
