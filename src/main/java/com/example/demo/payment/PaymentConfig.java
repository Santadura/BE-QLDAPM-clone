package com.example.demo.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.net.URI;

@Configuration
@EnableConfigurationProperties(PaymentConfig.Settings.class)
public class PaymentConfig {
 @Data @ConfigurationProperties(prefix="payment")
 public static class Settings {
  private boolean sandbox=true;
  private String frontendUrl="http://localhost:5173",publicApiUrl="";
  private String vnpayTmnCode="",vnpayHashSecret="",momoPartnerCode="",momoAccessKey="",momoSecretKey="";
  private String momoEndpoint="",momoRedirectUrl="",momoIpnUrl="";
  public String frontend(){return origin(frontendUrl,true);}
  public String api(){return origin(publicApiUrl,false);}
  public boolean configured(String provider){
   try{
    frontend();
    return switch(provider){
     case "VNPAY" -> { api(); yield !vnpayTmnCode.isBlank()&&!vnpayHashSecret.isBlank(); }
     case "MOMO" -> { momoUrl();momoRedirect();momoIpn();yield !momoPartnerCode.isBlank()&&!momoAccessKey.isBlank()&&!momoSecretKey.isBlank(); }
     default -> false;
    };
   }catch(IllegalArgumentException ex){return false;}
  }
  public String vnpayUrl(){return sandbox?"https://sandbox.vnpayment.vn/paymentv2/vpcpay.html":"https://pay.vnpay.vn/vpcpay.html";}
  public String momoUrl(){
   String expected=(sandbox?"https://test-payment.momo.vn":"https://payment.momo.vn")+"/v2/gateway/api/create";
   if(!momoEndpoint.isBlank()&&!expected.equals(momoEndpoint.trim()))throw new IllegalArgumentException("MOMO_ENDPOINT must match the selected payment environment.");
   return expected;
  }
  public String momoRedirect(){
   String expected=frontend()+"/api/payments/momo/return";
   if(!momoRedirectUrl.isBlank()&&!expected.equals(momoRedirectUrl.trim()))throw new IllegalArgumentException("MOMO_REDIRECT_URL must use the storefront origin and /api/payments/momo/return.");
   return expected;
  }
  public String momoIpn(){
   if(momoIpnUrl.isBlank())return api()+"/api/payments/momo/ipn";
   URI uri=URI.create(momoIpnUrl.trim());
   if(!"/api/payments/momo/ipn".equals(uri.getPath())||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Invalid MoMo IPN path.");
   return origin(uri.resolve("/").toString(),false)+"/api/payments/momo/ipn";
  }
  private static String origin(String value,boolean allowLocal){
   try{var uri=URI.create(value);String host=uri.getHost();boolean local=host!=null&&java.util.Set.of("localhost","127.0.0.1","[::1]").contains(host);
    if(host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||"/".equals(uri.getPath()))||!("https".equals(uri.getScheme())||allowLocal&&local&&"http".equals(uri.getScheme()))||!allowLocal&&local)throw new IllegalArgumentException();
    return value.replaceAll("/+$","");
   }catch(Exception ex){throw new IllegalArgumentException("Payment origin is not configured correctly.");}
  }
 }
}
