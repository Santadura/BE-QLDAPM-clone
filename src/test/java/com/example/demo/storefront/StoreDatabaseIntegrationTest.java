package com.example.demo.storefront;

import com.example.demo.exception.ApiException;
import com.example.demo.security.JwtUtil;
import org.junit.jupiter.api.*;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.example.demo.storefront.StoreDtos.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="GK_STORE_DB_TEST",matches="true")
class StoreDatabaseIntegrationTest {
 @Autowired com.example.demo.service.ClassManagementService classService;
 @Autowired StoreService store;@Autowired JdbcTemplate db;@Autowired MockMvc http;@Autowired JwtUtil jwt;
 String a,b,admin,course,cl;
 Authentication actor(String name,String role){return new UsernamePasswordAuthenticationToken(name,null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
 Authentication a(){return actor(a,"USER");} Authentication b(){return actor(b,"USER");}
 Profile profile(){return new Profile("Học viên thử nghiệm","contact@example.invalid","0901234567");}
 Checkout checkout(String key){return new Checkout(key,List.of(new CartRow(course,cl)),profile());}
 @BeforeEach void setup(){String suffix=UUID.randomUUID().toString().substring(0,8);a="store_a_"+suffix;b="store_b_"+suffix;admin="store_staff_"+suffix;course="store_course_"+suffix;cl="store_class_"+suffix;
  for(String name:List.of(a,b,admin))db.update("INSERT INTO users(username,email,password_hash,role_id,status) VALUES (?,?,?,?,'ACTIVE')",name,name+"@example.invalid","test-credential-stamp",name.equals(admin)?"ADMIN":"USER");
  db.update("INSERT INTO courses(course_id,name,status,price,category) VALUES (?,'Integration course','ACTIVE',1234567,'TOEIC')",course);
  db.update("INSERT INTO classes(class_id,course_id,class_code,name,start_date,end_date,status,storefront_open,capacity,location,schedule_label) VALUES (?,?,?,'Integration class',?,?,'READY',true,2,'Test campus','Test schedule')",cl,course,cl,LocalDate.now().plusDays(7),LocalDate.now().plusDays(70));
 }
 @Test void catalogReadsCurrentDatabaseAndRespectsPublication(){
  assertTrue(store.catalog(false).courses().stream().anyMatch(c->c.id().equals(course)&&c.price().compareTo(new BigDecimal("1234567"))==0));
  db.update("UPDATE courses SET name='Updated in database',price=777 WHERE course_id=?",course);
  assertTrue(store.catalog(false).courses().stream().anyMatch(c->c.id().equals(course)&&c.title().equals("Updated in database")&&c.price().intValue()==777));
  db.update("UPDATE courses SET published=false WHERE course_id=?",course);
  assertFalse(store.catalog(false).courses().stream().anyMatch(c->c.id().equals(course)));assertFalse(store.catalog(false).classes().stream().anyMatch(c->c.id().equals(cl)));
 }
 @Test void cartPersistsPerAccountAndRejectsMismatchedCourse(){
  store.mergeCart(a(),List.of(new CartRow(course,cl)));assertEquals(1,store.cart(a()).size());assertTrue(store.cart(b()).isEmpty());
  assertThrows(ApiException.class,()->store.mergeCart(a(),List.of(new CartRow("other",cl))));assertEquals(1,store.cart(a()).size());
 }
 @Test void checkoutUsesServerPriceIsIdempotentAndCopiesLinkedStudent(){
  store.mergeCart(a(),List.of(new CartRow(course,cl)));var order=store.checkout(a(),checkout("same-key"));
  assertEquals("PENDING",order.status());assertEquals(1234567,order.amount().intValue());assertEquals(order.id(),store.checkout(a(),checkout("same-key")).id());
  assertEquals(1,store.orders(a()).size());assertTrue(store.cart(a()).isEmpty());assertTrue(store.orders(b()).isEmpty());
  assertEquals(1,db.queryForObject("SELECT count(*) FROM students WHERE username=?",Integer.class,a));
  assertEquals(0,db.queryForObject("SELECT count(*) FROM class_students WHERE class_id=?",Integer.class,cl));
  var updated=store.profile(a(),new Profile("Tên mới","updated@example.invalid","0909999999"));assertEquals(a+"@example.invalid",updated.email());assertEquals("updated@example.invalid",updated.contactEmail());
  assertEquals("Tên mới",db.queryForObject("SELECT full_name FROM students WHERE username=?",String.class,a));
 }
 @Test void fullClassAndCancellationRespectReservationsAndOwnership(){
  db.update("UPDATE classes SET capacity=1 WHERE class_id=?",cl);var first=store.checkout(a(),checkout("first"));
  assertThrows(ApiException.class,()->store.checkout(b(),checkout("second")));assertTrue(store.orders(b()).isEmpty());
  assertThrows(ApiException.class,()->store.cancel(b(),first.id()));store.cancel(a(),first.id());
  assertEquals("PENDING",store.checkout(b(),checkout("second")).status());
 }
 @Test void confirmAddsRosterExactlyOnceAndConvertsPendingReservation(){
  var order=store.checkout(a(),checkout("confirm"));store.confirm(actor(admin,"ADMIN"),order.id());store.confirm(actor(admin,"ADMIN"),order.id());
  assertEquals("CONFIRMED",store.orders(a()).get(0).status());
  assertEquals(1,store.enrollments(a()).size());assertTrue(store.enrollments(b()).isEmpty());
  assertEquals(1,db.queryForObject("SELECT count(*) FROM class_students WHERE class_id=? AND status='ACTIVE'",Integer.class,cl));
  assertEquals(1,store.catalog(false).classes().stream().filter(c->c.id().equals(cl)).findFirst().orElseThrow().seats());
 }
 @Test void apiProtectsRolesValidatesConsentAndDoesNotAcceptBrowserPrice()throws Exception{
  String token=jwt.generateToken(a,"USER","test-credential-stamp");
  http.perform(get("/api/store/catalog")).andExpect(status().isOk());
  String adminToken=jwt.generateToken(admin,"ADMIN","test-credential-stamp");
  http.perform(get("/api/store/admin/catalog").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk());
  http.perform(get("/api/store/me/orders").header("Authorization","Bearer "+adminToken)).andExpect(status().isForbidden());
  http.perform(get("/api/store/me/orders")).andExpect(status().isUnauthorized());
  http.perform(get("/api/store/admin/catalog").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  http.perform(post("/api/store/consultations").contentType("application/json").content("{\"email\":\"test@example.invalid\",\"consent\":false}")).andExpect(status().isBadRequest());
  http.perform(post("/api/store/me/orders").header("Authorization","Bearer "+token).contentType("application/json").content("{\"requestKey\":\"api-key\",\"amount\":1,\"items\":[{\"courseId\":\""+course+"\",\"classId\":\""+cl+"\"}],\"profile\":{\"fullName\":\"Test Student\",\"email\":\"test@example.invalid\",\"phone\":\"0901234567\"}}")).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(1234567));
 }
 @Test void courseAndOpeningEditsImmediatelyChangePublicData(){
  var edit=new CourseEdit("Khóa tạo từ quản lý","MOS","Cơ bản",8,16,"MOS",new BigDecimal("500000"),"/images/mos.jpg","Không yêu cầu", "Mô tả mới",List.of("Thực hành"),List.of("Nội dung"),true,true);
  String created=store.createCourse(edit);assertTrue(store.catalog(false).courses().stream().anyMatch(c->c.id().equals(created)&&c.price().intValue()==500000));
  store.editOpening(cl,new OpeningEdit(false,2,"Trực tuyến","Zoom","Thứ 2, 4"));
  assertFalse(store.catalog(false).classes().stream().anyMatch(c->c.id().equals(cl)));
  assertThrows(ApiException.class,()->store.editOpening(cl,new OpeningEdit(true,2,"Trực tuyến","", "")));
 }
 @Test void pendingWebsiteReservationPreventsManualRosterOverbooking(){
  db.update("UPDATE classes SET capacity=1 WHERE class_id=?",cl);store.checkout(a(),checkout("reserved"));
  String student="manual-"+UUID.randomUUID();db.update("INSERT INTO students(student_id,student_code,full_name,status,username) VALUES (?,?,?,'Active',?)",student,student.substring(0,26),"Another student",b);
  assertThrows(ApiException.class,()->classService.addStudents(cl,new com.example.demo.dto.ClassDtos.AddStudentsRequest(List.of(student)),actor(admin,"ADMIN")));
 }
 @Test void staffCanRejectPendingRegistrationAndReleaseSeat(){
  db.update("UPDATE classes SET capacity=1 WHERE class_id=?",cl);var first=store.checkout(a(),checkout("reject"));
  store.reject(first.id());assertEquals("CANCELLED",store.orders(a()).get(0).status());assertEquals("PENDING",store.checkout(b(),checkout("after-reject")).status());
 }
 @Test void duplicateAndUnavailableClassesCannotCreateExtraOrders(){
  var first=store.checkout(a(),checkout("first"));assertThrows(ApiException.class,()->store.checkout(a(),checkout("different-key")));assertEquals(1,store.orders(a()).size());
  db.update("UPDATE classes SET storefront_open=false WHERE class_id=?",cl);assertThrows(ApiException.class,()->store.checkout(b(),checkout("closed")));assertTrue(store.orders(b()).isEmpty());
 }
}
