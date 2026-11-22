package com.example.demo.payment;
import com.example.demo.dto.AuthDtos;
import com.example.demo.exception.ApiException;
import com.example.demo.security.JwtUtil;
import com.example.demo.storefront.StoreDtos.*;
import com.example.demo.storefront.StoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.LinkedMultiValueMap;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"payment.public-api-url=https://callbacks.example.test","payment.vnpay-tmn-code=TESTCODE","payment.vnpay-hash-secret=test-vnpay-secret","payment.momo-partner-code=TESTMOMO","payment.momo-access-key=test-access","payment.momo-secret-key=test-momo-secret"})
@AutoConfigureMockMvc @Transactional
@EnabledIfEnvironmentVariable(named="GK_PAYMENT_DB_TEST",matches="true")
class PaymentDatabaseIntegrationTest {
 @Autowired PaymentService payments;@Autowired PaymentGateways gateways;@Autowired PaymentLedger ledger;@Autowired StoreService store;@Autowired JdbcTemplate db;@Autowired MockMvc http;@Autowired JwtUtil jwt;
 String user,other,course,cl;
 Authentication actor(){return new UsernamePasswordAuthenticationToken(user,null,List.of(new SimpleGrantedAuthority("ROLE_USER")));}
 @BeforeEach void setup(){String suffix=UUID.randomUUID().toString().substring(0,8);user="payment_"+suffix;other="other_"+suffix;course="pay-course-"+suffix;cl="pay-class-"+suffix;for(String name:List.of(user,other))db.update("INSERT INTO users(username,email,password_hash,role_id,status) VALUES (?,?,?,'USER','ACTIVE')",name,name+"@example.invalid","test-credential");db.update("INSERT INTO courses(course_id,name,status,price) VALUES (?,'Payment test course','ACTIVE',2490000)",course);db.update("INSERT INTO classes(class_id,course_id,class_code,name,start_date,end_date,status,capacity,storefront_open,location,schedule_label) VALUES (?,?,?,'Payment test class',?,?,'READY',2,true,'Test','Test')",cl,course,cl,LocalDate.now().plusDays(10),LocalDate.now().plusDays(60));}
 Order order(){return store.checkout(actor(),new Checkout(UUID.randomUUID().toString(),List.of(new CartRow(course,cl)),new Profile("Payment Test","contact@example.invalid","0901234567")));}
 PaymentDtos.Status start(String orderId){return payments.start(user,orderId,"VNPAY","127.0.0.1");}
 @Test void amountComesFromOrderAndActiveRequestIsReused(){var o=order();var first=start(o.id());assertEquals(2490000,first.attempt().amount().intValue());assertEquals(first.attempt().id(),start(o.id()).attempt().id());assertThrows(ApiException.class,()->payments.start(user,o.id(),"MOMO","127.0.0.1"));assertThrows(ApiException.class,()->store.cancel(actor(),o.id()));assertThrows(ApiException.class,()->payments.status(other,o.id()));}
 @Test void validIpnPaysAndEnrollsExactlyOnceAndDoesNotDowngrade(){var o=order();var a=start(o.id()).attempt();var result=gateways.verifyVnpay(PaymentGatewaysTest.vnpay(a.id(),"249000000"));payments.notify(result);payments.notify(result);assertEquals("PAID",payments.status(user,o.id()).paymentStatus());assertEquals("CONFIRMED",store.orders(actor()).get(0).status());assertEquals(1,store.enrollments(actor()).size());ledger.receive(a.id(),"VNPAY",a.amount(),false,null,"24");assertEquals("PAID",payments.status(user,o.id()).paymentStatus());assertThrows(ApiException.class,()->store.cancel(actor(),o.id()));}
 @Test void wrongAmountInvalidSignatureAndBrowserReturnCannotMarkPaid()throws Exception{var o=order();var a=start(o.id()).attempt();var wrong=gateways.verifyVnpay(PaymentGatewaysTest.vnpay(a.id(),"100"));assertThrows(ApiException.class,()->payments.notify(wrong));assertEquals("PENDING",payments.status(user,o.id()).paymentStatus());var tampered=PaymentGatewaysTest.vnpay(a.id(),"249000000");tampered.put("vnp_ResponseCode","24");assertThrows(ApiException.class,()->gateways.verifyVnpay(tampered));var params=new LinkedMultiValueMap<String,String>();PaymentGatewaysTest.vnpay(a.id(),"249000000").forEach(params::add);http.perform(get("/api/payments/vnpay/return").params(params)).andExpect(status().isFound());assertEquals("PENDING",payments.status(user,o.id()).paymentStatus());http.perform(get("/api/payments/vnpay/ipn").params(params)).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("00"));http.perform(get("/api/payments/vnpay/ipn").params(params)).andExpect(jsonPath("$.RspCode").value("02"));}
 @Test void momoIpnRequiresSignatureAndReturns204OnlyForVerifiedNotification()throws Exception{var o=order();var a=ledger.prepare(user,o.id(),"MOMO");var p=PaymentGatewaysTest.momo(a.id(),"2490000","0");payments.notify(gateways.verifyMomo(p));assertEquals("PAID",payments.status(user,o.id()).paymentStatus());var body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(p);http.perform(post("/api/payments/momo/ipn").contentType("application/json").content(body)).andExpect(status().isNoContent());p.put("signature","invalid");http.perform(post("/api/payments/momo/ipn").contentType("application/json").content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(p))).andExpect(status().isBadRequest());}
 @Test void expiredAttemptCanBeRetriedAndSignedLateSuccessIsStillRecorded(){var o=order();var first=start(o.id()).attempt();db.update("UPDATE payment_attempts SET expires_at=CURRENT_TIMESTAMP-interval '1 minute' WHERE attempt_id=?",first.id());assertEquals("EXPIRED",payments.status(user,o.id()).attempt().status());var next=start(o.id()).attempt();assertNotEquals(first.id(),next.id());payments.notify(gateways.verifyVnpay(PaymentGatewaysTest.vnpay(first.id(),"249000000")));assertEquals("PAID",payments.status(user,o.id()).paymentStatus());assertEquals(first.id(),payments.status(user,o.id()).attempt().id());}
 @Test void paymentEndpointsRequireOwnerAndDoNotExposeKeys()throws Exception{var o=order();http.perform(get("/api/payments/orders/"+o.id())).andExpect(status().isUnauthorized());String token=jwt.generateToken(other,"USER","test-credential");http.perform(get("/api/payments/orders/"+o.id()).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());http.perform(get("/api/payments/providers")).andExpect(status().isOk()).andExpect(jsonPath("$.VNPAY.enabled").value(true)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-vnpay-secret"))));}
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
 void paidMoneySurvivesEnrollmentFailureInSeparateTransaction(){String orderId=null;try{var o=order();orderId=o.id();var a=start(o.id()).attempt();db.update("UPDATE classes SET status='CLOSED' WHERE class_id=?",cl);payments.notify(gateways.verifyVnpay(PaymentGatewaysTest.vnpay(a.id(),"249000000")));var state=payments.status(user,o.id());assertEquals("PAID",state.paymentStatus());assertEquals("PENDING",state.orderStatus());assertNotNull(state.fulfillmentError());assertEquals(0,store.enrollments(actor()).size());}finally{if(orderId!=null){db.update("DELETE FROM payment_attempts WHERE order_id=?",orderId);db.update("DELETE FROM storefront_order_items WHERE order_id=?",orderId);db.update("DELETE FROM storefront_orders WHERE order_id=?",orderId);}db.update("DELETE FROM storefront_cart_items WHERE username=?",user);db.update("DELETE FROM students WHERE username=?",user);db.update("DELETE FROM audit_logs WHERE username IN (?,?)",user,other);db.update("DELETE FROM classes WHERE class_id=?",cl);db.update("DELETE FROM courses WHERE course_id=?",course);db.update("DELETE FROM users WHERE username IN (?,?)",user,other);}}
}
