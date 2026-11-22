package com.example.demo.payment;
import com.example.demo.exception.ApiException;
import com.example.demo.service.ClassManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.example.demo.payment.PaymentDtos.*;

@Service @RequiredArgsConstructor
public class PaymentService {
 private final PaymentConfig.Settings config;
 private final PaymentLedger ledger;
 private final PaymentGateways gateways;
 private final ClassManagementService classes;
 private final Set<String> creating=java.util.concurrent.ConcurrentHashMap.newKeySet();
 public Map<String,Provider> providers(){Map<String,Provider> result=new LinkedHashMap<>();for(String p:List.of("VNPAY","MOMO")){boolean enabled=config.configured(p);result.put(p,new Provider(enabled,config.isSandbox(),enabled?"":"Chưa cấu hình tài khoản thanh toán và địa chỉ nhận kết quả."));}return result;}
 public Status start(String username,String orderId,String provider,String ip){
  if(!config.configured(provider))throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"Phương thức thanh toán chưa được cấu hình. Vui lòng chọn phương thức đang hoạt động hoặc liên hệ GK.");
  Attempt a=ledger.prepare(username,orderId,provider);
  if("CREATING".equals(a.status())){
   if(!creating.add(a.id()))throw new ApiException(HttpStatus.CONFLICT,"Đang tạo mã thanh toán. Vui lòng chờ trong giây lát.");
   try{var link=gateways.create(a,ip);ledger.created(a.id(),link.url(),link.qr());}
   catch(PaymentGateways.GatewayDeclined ex){ledger.failedCreation(a.id());throw ex;}
   finally{creating.remove(a.id());}
  }
  return ledger.status(username,orderId,config.isSandbox());
 }
 public Status status(String username,String orderId){return ledger.status(username,orderId,config.isSandbox());}
 public Receipt notify(PaymentGateways.Verified result){
  Attempt a=ledger.find(result.attemptId());
  if(!a.provider().equals(result.provider())||a.amount().compareTo(result.amount())!=0)throw new ApiException(HttpStatus.BAD_REQUEST,"Số tiền hoặc nhà cung cấp không khớp.");
  if(result.pending())return new Receipt(a.orderId(),true);
  Receipt receipt=ledger.receive(result.attemptId(),result.provider(),result.amount(),result.success(),result.transactionId(),result.code());
  if(result.success()){try{classes.enrollPaidOrder(receipt.orderId());}catch(Exception ex){ledger.fulfillmentIssue(receipt.orderId());}}
  return receipt;
 }
 public String returned(PaymentGateways.Verified result){Attempt a=ledger.find(result.attemptId());if(!a.provider().equals(result.provider())||a.amount().compareTo(result.amount())!=0)throw new ApiException(HttpStatus.BAD_REQUEST,"Thông tin trả về không khớp.");return config.frontend()+"/payment/"+a.orderId();}
}
