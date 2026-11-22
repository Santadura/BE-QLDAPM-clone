package com.example.demo.payment;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
public final class PaymentSignatures {
 private PaymentSignatures(){}
 public static String hmac(String algorithm,String secret,String input){try{Mac mac=Mac.getInstance(algorithm);mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),algorithm));return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException("Cannot sign payment request",ex);}}
 public static boolean equal(String expected,String actual){return actual!=null&&actual.matches("[a-fA-F0-9]+")&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));}
 public static String encode(String text){return URLEncoder.encode(text,StandardCharsets.UTF_8);}
 public static String vnpayData(Map<String,String> params){return new TreeMap<>(params).entrySet().stream().filter(e->e.getKey().startsWith("vnp_")&&!Set.of("vnp_SecureHash","vnp_SecureHashType").contains(e.getKey())&&e.getValue()!=null&&!e.getValue().isEmpty()).map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(Collectors.joining("&"));}
 public static String momoData(Map<String,String> params,String...keys){return Arrays.stream(keys).map(k->k+"="+params.getOrDefault(k,"")).collect(Collectors.joining("&"));}
}
