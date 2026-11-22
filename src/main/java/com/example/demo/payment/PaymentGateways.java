package com.example.demo.payment;
import com.example.demo.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.*;
import static com.example.demo.payment.PaymentDtos.*;
import static com.example.demo.payment.PaymentSignatures.*;

@Service @RequiredArgsConstructor
public class PaymentGateways {
 private final PaymentConfig.Settings config;
 private final ObjectMapper json;
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
 public record Link(String url,String qr) {}
 public record Verified(String attemptId,String provider,BigDecimal amount,boolean success,boolean pending,String transactionId,String code) {}
 public Link create(Attempt attempt,String ip){return "VNPAY".equals(attempt.provider())?vnpay(attempt,ip):momo(attempt);}
 private Link vnpay(Attempt a,String ip){
  DateTimeFormatter time=DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
  Map<String,String> p=new TreeMap<>();p.put("vnp_Version","2.1.0");p.put("vnp_Command","pay");p.put("vnp_TmnCode",config.getVnpayTmnCode());
  p.put("vnp_Amount",a.amount().multiply(BigDecimal.valueOf(100)).toBigIntegerExact().toString());p.put("vnp_CurrCode","VND");p.put("vnp_TxnRef",a.id());
  p.put("vnp_OrderInfo","Thanh toan hoc phi GK "+a.orderId());p.put("vnp_OrderType","other");p.put("vnp_Locale","vn");
  p.put("vnp_IpAddr",ip);p.put("vnp_CreateDate",time.format(a.createdAt()));p.put("vnp_ExpireDate",time.format(a.expiresAt()));p.put("vnp_ReturnUrl",config.frontend()+"/api/payments/vnpay/return");
  String data=vnpayData(p),url=config.vnpayUrl()+"?"+data+"&vnp_SecureHash="+hmac("HmacSHA512",config.getVnpayHashSecret(),data);
  return new Link(url,url);
 }
 private Link momo(Attempt a){
  Map<String,Object> body=new LinkedHashMap<>();body.put("partnerCode",config.getMomoPartnerCode());body.put("requestId",a.id());body.put("orderId",a.id());body.put("amount",a.amount().longValueExact());
  body.put("orderInfo","Thanh toan hoc phi GK "+a.orderId());body.put("redirectUrl",config.momoRedirect());body.put("ipnUrl",config.momoIpn());body.put("requestType","captureWallet");body.put("extraData","");body.put("lang","vi");body.put("autoCapture",true);
  Map<String,String> signing=strings(body);signing.put("accessKey",config.getMomoAccessKey());
  body.put("signature",hmac("HmacSHA256",config.getMomoSecretKey(),momoData(signing,"accessKey","amount","extraData","ipnUrl","orderId","orderInfo","partnerCode","redirectUrl","requestId","requestType")));
  try{
   var request=HttpRequest.newBuilder(URI.create(config.momoUrl())).timeout(Duration.ofSeconds(35)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
   var response=client.send(request,HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()!=200||response.body().length()>65536)throw new IllegalStateException();
   var value=json.readTree(response.body());
   if(value.path("resultCode").asInt(-1)!=0)throw new GatewayDeclined();
   if(!a.id().equals(value.path("orderId").asText())||!a.id().equals(value.path("requestId").asText())||value.path("amount").asLong(-1)!=a.amount().longValueExact()||!config.getMomoPartnerCode().equals(value.path("partnerCode").asText()))throw new IllegalStateException();
   String url=value.path("payUrl").asText();var uri=URI.create(url);
   if(!"https".equals(uri.getScheme())||!(config.isSandbox()?"test-payment.momo.vn":"payment.momo.vn").equals(uri.getHost())||uri.getUserInfo()!=null)throw new IllegalStateException();
   String qr=value.path("qrCodeUrl").asText("");return new Link(url,qr.isBlank()?url:qr);
  }catch(GatewayDeclined ex){throw ex;}
  catch(InterruptedException ex){Thread.currentThread().interrupt();throw unavailable();}
  catch(Exception ex){throw unavailable();}
 }
 public static class GatewayDeclined extends ApiException {public GatewayDeclined(){super(HttpStatus.BAD_GATEWAY,"MoMo chưa tạo được mã thanh toán. Vui lòng kiểm tra cấu hình hoặc thử lại.");}}
 private static ApiException unavailable(){return new ApiException(HttpStatus.BAD_GATEWAY,"Chưa nhận được phản hồi tạo mã từ MoMo. Vui lòng kiểm tra lại trạng thái, không gửi thanh toán nhiều lần.");}
 public Verified verifyVnpay(Map<String,String> p){
  if(!config.configured("VNPAY")||!config.getVnpayTmnCode().equals(p.get("vnp_TmnCode"))||!equal(hmac("HmacSHA512",config.getVnpayHashSecret(),vnpayData(p)),p.get("vnp_SecureHash")))throw new ApiException(HttpStatus.BAD_REQUEST,"Chữ ký thanh toán không hợp lệ.");
  String code=p.getOrDefault("vnp_ResponseCode",""),status=p.getOrDefault("vnp_TransactionStatus","");
  if(!code.matches("[0-9]{2}")||!status.matches("[0-9]{2}"))throw new ApiException(HttpStatus.BAD_REQUEST,"Thiếu kết quả thanh toán.");
  return new Verified(required(p,"vnp_TxnRef"),"VNPAY",amount(p,"vnp_Amount").movePointLeft(2),"00".equals(code)&&"00".equals(status),"01".equals(status),p.get("vnp_TransactionNo"),code);
 }
 public Verified verifyMomo(Map<String,String> p){
  Map<String,String> fields=new HashMap<>(p);fields.put("accessKey",config.getMomoAccessKey());
  String data=momoData(fields,"accessKey","amount","extraData","message","orderId","orderInfo","orderType","partnerCode","payType","requestId","responseTime","resultCode","transId");
  if(!config.configured("MOMO")||!config.getMomoPartnerCode().equals(p.get("partnerCode"))||!equal(hmac("HmacSHA256",config.getMomoSecretKey(),data),p.get("signature")))throw new ApiException(HttpStatus.BAD_REQUEST,"Chữ ký thanh toán không hợp lệ.");
  String id=required(p,"orderId"),code=required(p,"resultCode");if(!id.equals(p.get("requestId"))||!code.matches("[0-9]{1,8}"))throw new ApiException(HttpStatus.BAD_REQUEST,"Sai mã yêu cầu thanh toán.");
  return new Verified(id,"MOMO",amount(p,"amount"),"0".equals(code),Set.of("1000","7000","7002","9000").contains(code),p.get("transId"),code);
 }
 private static String required(Map<String,String> p,String key){String value=p.get(key);if(value==null||value.isBlank()||value.length()>100)throw new ApiException(HttpStatus.BAD_REQUEST,"Thiếu thông tin giao dịch.");return value;}
 private static BigDecimal amount(Map<String,String> p,String key){String value=required(p,key);if(!value.matches("[0-9]{1,14}"))throw new ApiException(HttpStatus.BAD_REQUEST,"Số tiền không hợp lệ.");return new BigDecimal(value);}
 public static Map<String,String> strings(Map<String,?> input){Map<String,String> result=new HashMap<>();input.forEach((k,v)->{if(v!=null&&(v instanceof String||v instanceof Number||v instanceof Boolean))result.put(k,v.toString());});return result;}
}
