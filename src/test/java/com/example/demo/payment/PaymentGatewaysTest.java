package com.example.demo.payment;
import com.example.demo.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.demo.payment.PaymentSignatures.*;

class PaymentGatewaysTest {
 static PaymentConfig.Settings config(){var c=new PaymentConfig.Settings();c.setPublicApiUrl("https://callbacks.example.test");c.setVnpayTmnCode("TESTCODE");c.setVnpayHashSecret("test-vnpay-secret");c.setMomoPartnerCode("TESTMOMO");c.setMomoAccessKey("test-access");c.setMomoSecretKey("test-momo-secret");return c;}
 static Map<String,String> vnpay(String id,String amount){Map<String,String> p=new HashMap<>(Map.of("vnp_TxnRef",id,"vnp_Amount",amount,"vnp_TmnCode","TESTCODE","vnp_ResponseCode","00","vnp_TransactionStatus","00","vnp_TransactionNo","12345"));p.put("vnp_SecureHash",hmac("HmacSHA512","test-vnpay-secret",vnpayData(p)));return p;}
 static Map<String,String> momo(String id,String amount,String code){Map<String,String> p=new HashMap<>();p.put("partnerCode","TESTMOMO");p.put("orderId",id);p.put("requestId",id);p.put("amount",amount);p.put("orderType","momo_wallet");p.put("transId","987654321");p.put("responseTime","1795180000000");p.put("resultCode",code);p.put("message","Successful.");p.put("payType","qr");p.put("orderInfo","Thanh toan hoc phi");p.put("extraData","");signMomo(p);return p;}
 static void signMomo(Map<String,String> p){Map<String,String> fields=new HashMap<>(p);fields.put("accessKey","test-access");p.put("signature",hmac("HmacSHA256","test-momo-secret",momoData(fields,"accessKey","amount","extraData","message","orderId","orderInfo","orderType","partnerCode","payType","requestId","responseTime","resultCode","transId")));}
 final PaymentGateways gateway=new PaymentGateways(config(),new ObjectMapper());
 @Test void hmacUsesKnownSha256VectorAndConstantTimeComparison(){assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",hmac("HmacSHA256","key","The quick brown fox jumps over the lazy dog"));assertTrue(equal("abcdef","ABCDEF"));assertFalse(equal("abcdef","abcdeg"));}
 @Test void vnpayValidatesSignatureAmountScaleAndBothSuccessCodes(){var p=vnpay("paytest","249000000");assertEquals(new BigDecimal("2490000.00"),gateway.verifyVnpay(p).amount());assertTrue(gateway.verifyVnpay(p).success());p.put("vnp_Amount","100");var tampered=p;assertThrows(ApiException.class,()->gateway.verifyVnpay(tampered));p=vnpay("paytest","249000000");p.put("vnp_TransactionStatus","02");p.put("vnp_SecureHash",hmac("HmacSHA512","test-vnpay-secret",vnpayData(p)));assertFalse(gateway.verifyVnpay(p).success());}
 @Test void momoRequiresSignedMatchingRequestAndIgnoresAuthorizedAsPaid(){var p=momo("paytest","2490000","0");assertTrue(gateway.verifyMomo(p).success());p.put("resultCode","1006");var tampered=p;assertThrows(ApiException.class,()->gateway.verifyMomo(tampered));var authorized=gateway.verifyMomo(momo("paytest","2490000","9000"));assertFalse(authorized.success());assertTrue(authorized.pending());p=momo("paytest","2490000","0");p.put("requestId","other");signMomo(p);var mismatch=p;assertThrows(ApiException.class,()->gateway.verifyMomo(mismatch));}
 @Test void emptyConfigurationAndLocalCallbackCannotEnablePayments(){var c=new PaymentConfig.Settings();assertFalse(c.configured("VNPAY"));c=config();c.setPublicApiUrl("http://localhost:8080");assertFalse(c.configured("VNPAY"));assertFalse(c.configured("MOMO"));}
 @Test void momoExplicitUrlsSupportPublicIpnWithoutSharedApiOrigin(){
  var c=config();c.setPublicApiUrl("");c.setMomoEndpoint("https://test-payment.momo.vn/v2/gateway/api/create");
  c.setMomoRedirectUrl("http://localhost:5173/api/payments/momo/return");c.setMomoIpnUrl("https://callbacks.example.test/api/payments/momo/ipn");
  assertTrue(c.configured("MOMO"));assertFalse(c.configured("VNPAY"));
  assertEquals("https://callbacks.example.test/api/payments/momo/ipn",c.momoIpn());
  assertEquals("http://localhost:5173/api/payments/momo/return",c.momoRedirect());
  c.setMomoIpnUrl("http://localhost:3000/payment/momo/ipn");assertFalse(c.configured("MOMO"));
 }
 @Test void momoRejectsWrongEnvironmentOrUnsupportedCallback(){
  var c=config();c.setMomoEndpoint("https://untrusted.example.test/create");assertFalse(c.configured("MOMO"));
  c.setMomoEndpoint("");c.setMomoRedirectUrl("http://localhost:3000/payment/momo/callback");assertFalse(c.configured("MOMO"));
 }
 @Test void generatedVnpayLinkUsesSandboxSignedReturnAndExpiry(){var now=Instant.parse("2026-11-20T12:00:00Z");var a=new PaymentDtos.Attempt("paytest","gk-test","VNPAY",new BigDecimal("2490000"),"CREATING",null,null,null,now,now.plusSeconds(900));var link=gateway.create(a,"127.0.0.1");assertTrue(link.url().startsWith("https://sandbox.vnpayment.vn/"));assertTrue(link.url().contains("vnp_Amount=249000000"));assertTrue(link.url().contains("vnp_CreateDate=20261120190000"));assertTrue(link.url().contains("vnp_ExpireDate=20261120191500"));assertEquals(link.url(),link.qr());assertFalse(link.url().contains("test-vnpay-secret"));}
}
