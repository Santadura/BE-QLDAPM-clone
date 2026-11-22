package com.example.demo.payment;
import com.example.demo.exception.ApiException;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.example.demo.payment.PaymentDtos.*;

@RestController @RequestMapping("/api/payments") @RequiredArgsConstructor
public class PaymentController {
 private final PaymentService payments;
 private final PaymentGateways gateways;
 private Map<String,String> unique(MultiValueMap<String,String> params){Map<String,String> values=new HashMap<>();params.forEach((k,v)->{if(v.size()!=1)throw new ApiException(HttpStatus.BAD_REQUEST,"Tham số thanh toán bị lặp.");values.put(k,v.get(0));});return values;}
 @GetMapping("/providers") public ResponseEntity<?> providers(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payments.providers());}
 @PostMapping("/orders/{orderId}") public ResponseEntity<Status> start(Authentication actor,@PathVariable String orderId,@Valid @RequestBody Start body,HttpServletRequest request){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payments.start(actor.getName(),orderId,body.provider(),request.getRemoteAddr()));}
 @GetMapping("/orders/{orderId}") public ResponseEntity<Status> status(Authentication actor,@PathVariable String orderId){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payments.status(actor.getName(),orderId));}
 @GetMapping("/vnpay/ipn") public Map<String,String> vnpayIpn(@RequestParam MultiValueMap<String,String> params){
  try{var result=gateways.verifyVnpay(unique(params));if(result.pending())return Map.of("RspCode","99","Message","Transaction processing");var receipt=payments.notify(result);return Map.of("RspCode",receipt.duplicate()?"02":"00","Message","Confirm Success");}
  catch(ApiException ex){String code=ex.getStatus()==HttpStatus.NOT_FOUND?"01":ex.getMessage().contains("Chữ ký")?"97":ex.getStatus()==HttpStatus.BAD_REQUEST?"04":"99";return Map.of("RspCode",code,"Message","Cannot confirm transaction");}
  catch(Exception ex){return Map.of("RspCode","99","Message","Cannot confirm transaction");}
 }
 @PostMapping("/momo/ipn") public ResponseEntity<Void> momoIpn(@RequestBody Map<String,Object> body){payments.notify(gateways.verifyMomo(PaymentGateways.strings(body)));return ResponseEntity.noContent().build();}
 @GetMapping("/vnpay/return") public void vnpayReturn(@RequestParam MultiValueMap<String,String> params,HttpServletResponse response)throws java.io.IOException{redirect(response,payments.returned(gateways.verifyVnpay(unique(params))));}
 @GetMapping("/momo/return") public void momoReturn(@RequestParam MultiValueMap<String,String> params,HttpServletResponse response)throws java.io.IOException{redirect(response,payments.returned(gateways.verifyMomo(unique(params))));}
 private void redirect(HttpServletResponse response,String url)throws java.io.IOException{response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");response.sendRedirect(url);}
 @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> error(Exception ex){HttpStatus status=ex instanceof ApiException api?api.getStatus():ex instanceof org.springframework.web.bind.MethodArgumentNotValidException?HttpStatus.BAD_REQUEST:HttpStatus.BAD_GATEWAY;String message=ex instanceof ApiException api?api.getMessage():"Không thể xử lý thanh toán. Vui lòng kiểm tra trạng thái hoặc liên hệ GK.";return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message",message));}
}
