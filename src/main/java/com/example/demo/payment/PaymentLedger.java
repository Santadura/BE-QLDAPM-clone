package com.example.demo.payment;
import com.example.demo.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.example.demo.payment.PaymentDtos.*;

@Service @RequiredArgsConstructor
public class PaymentLedger {
 private final JdbcTemplate db;
 private static ApiException conflict(String text){return new ApiException(HttpStatus.CONFLICT,text);}
 private Attempt attempt(ResultSet r,int n)throws SQLException{return new Attempt(r.getString("attempt_id"),r.getString("order_id"),r.getString("provider"),r.getBigDecimal("amount"),r.getString("status"),r.getString("pay_url"),r.getString("qr_data"),r.getString("transaction_id"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant());}
 public Attempt find(String id){return db.query("SELECT * FROM payment_attempts WHERE attempt_id=?",this::attempt,id).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Không tìm thấy giao dịch."));}
 private void lock(String orderId){String username=db.queryForList("SELECT username FROM storefront_orders WHERE order_id=?",String.class,orderId).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Không tìm thấy đơn."));db.queryForObject("SELECT username FROM users WHERE username=? FOR UPDATE",String.class,username);db.queryForObject("SELECT order_id FROM storefront_orders WHERE order_id=? FOR UPDATE",String.class,orderId);}
 private void owner(String username,String orderId){if(username==null||db.queryForObject("SELECT count(*) FROM storefront_orders o JOIN users u ON u.username=o.username WHERE o.order_id=? AND o.username=? AND u.role_id='USER' AND u.status='ACTIVE'",Integer.class,orderId,username)!=1)throw new ApiException(HttpStatus.NOT_FOUND,"Không tìm thấy đơn đăng ký của bạn.");}
 @Transactional
 public Attempt prepare(String username,String orderId,String provider){
  owner(username,orderId);lock(orderId);
  Map<String,Object> order=db.queryForMap("SELECT status,payment_status,amount FROM storefront_orders WHERE order_id=?",orderId);
  if("PAID".equals(order.get("payment_status")))throw conflict("Đơn đã thanh toán. Vui lòng xem kết quả.");
  if("CANCELLED".equals(order.get("status")))throw conflict("Đơn đã hủy, không thể thanh toán.");
  BigDecimal amount=(BigDecimal)order.get("amount");
  long vnd;try{vnd=amount.longValueExact();}catch(ArithmeticException ex){throw conflict("Học phí phải là số VND nguyên.");}
  if(vnd<1000||vnd>9999999999L)throw conflict("Số tiền nằm ngoài phạm vi thanh toán trực tuyến hỗ trợ.");
  db.update("UPDATE payment_attempts SET status='EXPIRED' WHERE order_id=? AND status IN ('CREATING','PENDING') AND expires_at<=?",orderId,Timestamp.from(Instant.now()));
  var active=db.query("SELECT * FROM payment_attempts WHERE order_id=? AND status IN ('CREATING','PENDING')",this::attempt,orderId);
  if(!active.isEmpty()){if(!active.get(0).provider().equals(provider))throw conflict("Đang có mã thanh toán còn hiệu lực. Hoàn tất hoặc chờ mã hết hạn trước khi đổi phương thức.");return active.get(0);}
  String id="pay"+UUID.randomUUID().toString().replace("-","");
  db.update("INSERT INTO payment_attempts(attempt_id,order_id,provider,amount,status,expires_at) VALUES (?,?,?,?,'CREATING',?)",id,orderId,provider,amount,Timestamp.from(Instant.now().plusSeconds(900)));
  db.update("UPDATE storefront_orders SET payment_status='PENDING' WHERE order_id=?",orderId);return find(id);
 }
 @Transactional public Attempt created(String id,String payUrl,String qr){Attempt a=find(id);lock(a.orderId());db.update("UPDATE payment_attempts SET status='PENDING',pay_url=?,qr_data=? WHERE attempt_id=? AND status='CREATING'",payUrl,qr,id);return find(id);}
 @Transactional public void failedCreation(String id){Attempt a=find(id);lock(a.orderId());db.update("UPDATE payment_attempts SET status='FAILED',result_code='CREATE_FAILED' WHERE attempt_id=? AND status='CREATING'",id);}
 @Transactional
 public Receipt receive(String id,String provider,BigDecimal amount,boolean success,String tx,String code){
  Attempt before=find(id);lock(before.orderId());Attempt a=find(id);
  if(!a.provider().equals(provider))throw conflict("Sai nhà cung cấp.");
  if(a.amount().compareTo(amount)!=0)throw new ApiException(HttpStatus.BAD_REQUEST,"Số tiền không khớp.");
  if("PAID".equals(a.status())){if(success&&!Objects.equals(a.transactionId(),tx))throw conflict("Mã giao dịch không khớp.");return new Receipt(a.orderId(),true);}
  if(!success){db.update("UPDATE payment_attempts SET status='FAILED',result_code=? WHERE attempt_id=? AND status<>'PAID'",code,id);return new Receipt(a.orderId(),false);}
  if(tx==null||tx.isBlank()||"0".equals(tx)||tx.length()>100)throw conflict("Thiếu mã giao dịch thành công.");
  db.update("UPDATE payment_attempts SET status='PAID',transaction_id=?,result_code=?,paid_at=CURRENT_TIMESTAMP WHERE attempt_id=?",tx,code,id);
  db.update("UPDATE payment_attempts SET status='EXPIRED' WHERE order_id=? AND attempt_id<>? AND status IN ('CREATING','PENDING')",a.orderId(),id);
  int changed=db.update("UPDATE storefront_orders SET payment_status='PAID',paid_at=CURRENT_TIMESTAMP WHERE order_id=? AND payment_status<>'PAID'",a.orderId());
  if(changed==0)db.update("UPDATE storefront_orders SET fulfillment_error='Phát sinh thêm giao dịch thành công. Vui lòng liên hệ trung tâm để kiểm tra hoàn tiền.' WHERE order_id=?",a.orderId());
  return new Receipt(a.orderId(),false);
 }
 @Transactional(readOnly=true)
 public Status status(String username,String orderId,boolean sandbox){owner(username,orderId);return db.queryForObject("SELECT * FROM storefront_orders WHERE order_id=?",(r,n)->{var attempts=db.query("SELECT * FROM payment_attempts WHERE order_id=? ORDER BY (status='PAID') DESC,(status IN ('CREATING','PENDING')) DESC,created_at DESC,attempt_id DESC LIMIT 1",this::attempt,orderId);Attempt a=attempts.isEmpty()?null:attempts.get(0);if(a!=null&&Set.of("CREATING","PENDING").contains(a.status())&&!a.expiresAt().isAfter(Instant.now()))a=new Attempt(a.id(),a.orderId(),a.provider(),a.amount(),"EXPIRED",null,null,a.transactionId(),a.createdAt(),a.expiresAt());Timestamp paid=r.getTimestamp("paid_at");return new Status(orderId,r.getString("status"),r.getString("payment_status"),r.getBigDecimal("amount"),paid==null?null:paid.toInstant(),r.getString("fulfillment_error"),a,sandbox);},orderId);}
 @Transactional public void fulfillmentIssue(String id){db.update("UPDATE storefront_orders SET fulfillment_error=COALESCE(fulfillment_error,'Đã thanh toán. Trung tâm cần kiểm tra điều kiện ghi danh và sẽ liên hệ bạn.') WHERE order_id=?",id);}
}
