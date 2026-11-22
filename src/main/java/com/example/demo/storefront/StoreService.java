package com.example.demo.storefront;

import com.example.demo.dto.AuthDtos.CurrentAccountResponse;
import com.example.demo.exception.ApiException;
import com.example.demo.service.AuthService;
import com.example.demo.service.ClassManagementService;
import com.example.demo.dto.ClassDtos.AddStudentsRequest;
import com.example.demo.social.SocialCrypto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.example.demo.storefront.StoreDtos.*;

@Service
@RequiredArgsConstructor
public class StoreService {
 private final JdbcTemplate db;
 private final ObjectMapper json;
 private final AuthService auth;
 private final ClassManagementService classes;
 private final jakarta.persistence.EntityManager entities;
 private static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
 private static ApiException bad(String message){return new ApiException(HttpStatus.CONFLICT,message);}
 private static String id(String prefix){return prefix+UUID.randomUUID();}
 private List<String> strings(String value){try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(Exception ex){throw new IllegalStateException("Invalid course content",ex);}}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalArgumentException(ex);}}
 private Course course(ResultSet r,int n)throws SQLException {
  Integer weeks=(Integer)r.getObject("duration");
  return new Course(r.getString("course_id"),r.getString("name"),r.getString("category"),Objects.toString(r.getString("level"),"Đang cập nhật"),weeks==null?"Liên hệ trung tâm":weeks+" tuần",r.getInt("lesson_count"),Objects.toString(r.getString("target"),""),r.getBigDecimal("price"),r.getString("image_url"),Objects.toString(r.getString("entry_description"),"Liên hệ để được tư vấn đầu vào"),Objects.toString(r.getString("description"),"Nội dung khóa học đang được cập nhật."),strings(r.getString("outcomes")),strings(r.getString("syllabus")),r.getBoolean("featured"),r.getBoolean("published"));
 }
 private static final String OCCUPIED="(SELECT count(*) FROM class_students cs WHERE cs.class_id=c.class_id AND cs.status='ACTIVE') + (SELECT count(*) FROM storefront_order_items oi JOIN storefront_orders o ON o.order_id=oi.order_id WHERE oi.class_id=c.class_id AND o.status='PENDING')";
 private List<Opening> openings(boolean management) {
  return db.query("SELECT c.*, GREATEST(0,c.capacity-("+OCCUPIED+")) AS seats FROM classes c JOIN courses co ON co.course_id=c.course_id WHERE "+(management?"true":"co.status='ACTIVE' AND co.published AND c.storefront_open AND c.status='READY' AND c.start_date>=?")+" ORDER BY c.start_date,c.class_id",
   (r,n)->new Opening(r.getString("class_id"),r.getString("class_code"),r.getString("course_id"),r.getDate("start_date").toLocalDate().toString(),Objects.toString(r.getString("schedule_label"),"Lịch chi tiết đang cập nhật"),Objects.toString(r.getString("location"),"Liên hệ trung tâm"),r.getString("delivery_mode"),r.getInt("seats"),r.getInt("capacity"),r.getBoolean("storefront_open")),management?new Object[]{}:new Object[]{LocalDate.now(ZONE)});
 }
 @Transactional(readOnly=true)
 public Catalog catalog(boolean management){return new Catalog(db.query("SELECT * FROM courses WHERE "+(management?"true":"status='ACTIVE' AND published")+" ORDER BY featured DESC,created_at,course_id",this::course),openings(management));}
 private String customer(Authentication actor){
  if(actor==null)throw new ApiException(HttpStatus.UNAUTHORIZED,"Vui lòng đăng nhập.");
  if(db.queryForObject("SELECT count(*) FROM users WHERE username=? AND role_id='USER' AND status='ACTIVE'",Integer.class,actor.getName())!=1)throw new ApiException(HttpStatus.FORBIDDEN,"Chỉ dành cho học viên đang hoạt động.");
  return actor.getName();
 }
 private void userLock(String username){db.queryForObject("SELECT username FROM users WHERE username=? FOR UPDATE",String.class,username);}
 @Transactional
 public CurrentAccountResponse profile(Authentication actor,Profile body){String username=customer(actor);userLock(username);saveProfile(username,body);entities.clear();return auth.currentAccount(actor);}
 private void saveProfile(String username,Profile body){
  db.update("UPDATE users SET display_name=?,phone=?,contact_email=? WHERE username=?",body.fullName().trim(),body.phone(),body.email().trim(),username);
  db.update("UPDATE students SET full_name=?,phone=?,email=? WHERE username=?",body.fullName().trim(),body.phone(),body.email().trim(),username);
 }
 @Transactional(readOnly=true)
 public List<CartRow> cart(Authentication actor){return readCart(customer(actor));}
 private List<CartRow> readCart(String username){return db.query("SELECT course_id,class_id FROM storefront_cart_items WHERE username=? ORDER BY course_id",(r,n)->new CartRow(r.getString(1),r.getString(2)),username);}
 private void validateSelection(CartRow row){
  if(db.queryForObject("SELECT count(*) FROM classes c JOIN courses co ON co.course_id=c.course_id WHERE c.class_id=? AND c.course_id=? AND c.storefront_open AND c.status='READY' AND c.start_date>=? AND co.status='ACTIVE' AND co.published AND co.price IS NOT NULL AND NULLIF(c.schedule_label,'') IS NOT NULL AND NULLIF(c.location,'') IS NOT NULL",Integer.class,row.classId(),row.courseId(),LocalDate.now(ZONE))!=1)throw bad("Lớp không còn mở đăng ký hoặc chưa đủ thông tin. Vui lòng chọn lại lịch học.");
 }
 @Transactional
 public List<CartRow> mergeCart(Authentication actor,List<CartRow> rows){String username=customer(actor);userLock(username);for(var row:rows){validateSelection(row);db.update("INSERT INTO storefront_cart_items(username,course_id,class_id) VALUES (?,?,?) ON CONFLICT(username,course_id) DO UPDATE SET class_id=excluded.class_id",username,row.courseId(),row.classId());}return readCart(username);}
 @Transactional
 public List<CartRow> removeCart(Authentication actor,String courseId){String username=customer(actor);userLock(username);db.update("DELETE FROM storefront_cart_items WHERE username=? AND course_id=?",username,courseId);return readCart(username);}
 @Transactional
 public Order checkout(Authentication actor,Checkout body){
  String username=customer(actor);userLock(username);
  List<CartRow> rows=body.items().stream().sorted(Comparator.comparing(CartRow::classId)).toList();
  if(rows.stream().map(CartRow::classId).distinct().count()!=rows.size() || rows.stream().map(CartRow::courseId).distinct().count()!=rows.size())throw bad("Mỗi khóa học chỉ chọn một lớp trong một đơn đăng ký.");
  String hash=SocialCrypto.hash(encode(rows));
  var prior=db.queryForList("SELECT order_id,request_hash FROM storefront_orders WHERE username=? AND request_key=?",username,body.requestKey());
  if(!prior.isEmpty()) {if(!hash.equals(prior.get(0).get("request_hash")))throw bad("Mã yêu cầu đã được dùng cho nội dung khác.");return order((String)prior.get(0).get("order_id"));}
  for(String courseId:rows.stream().map(CartRow::courseId).sorted().toList())
   if(db.queryForList("SELECT course_id FROM courses WHERE course_id=? FOR SHARE",courseId).isEmpty())throw bad("Khóa học không còn tồn tại.");
  for(var row:rows) {
   if(db.queryForList("SELECT class_id FROM classes WHERE class_id=? FOR UPDATE",row.classId()).isEmpty())throw bad("Không tìm thấy lớp học.");
   validateSelection(row);
   int available=db.queryForObject("SELECT c.capacity-("+OCCUPIED+") FROM classes c WHERE c.class_id=?",Integer.class,row.classId());
   if(available<=0)throw bad("Lớp đã hết chỗ. Vui lòng chọn lớp khác.");
   if(db.queryForObject("SELECT count(*) FROM storefront_order_items i JOIN storefront_orders o ON o.order_id=i.order_id WHERE o.username=? AND i.class_id=? AND o.status<>'CANCELLED'",Integer.class,username,row.classId())>0)throw bad("Bạn đã có đơn đăng ký cho lớp này. Hãy kiểm tra trong hồ sơ.");
   if(db.queryForObject("SELECT count(*) FROM class_students cs JOIN students s ON s.student_id=cs.student_id WHERE s.username=? AND cs.class_id=? AND cs.status='ACTIVE'",Integer.class,username,row.classId())>0)throw bad("Bạn đã ghi danh vào lớp này.");
  }
  saveProfile(username,body.profile());
  // A verified account creates its own student record. Never merge students by email.
  if(db.queryForObject("SELECT count(*) FROM students WHERE username=?",Integer.class,username)==0){String studentId=id("web-");db.update("INSERT INTO students(student_id,student_code,full_name,phone,email,status,username) VALUES (?,?,?,?,?,'Active',?)",studentId,"GK-"+UUID.randomUUID().toString().substring(0,20),body.profile().fullName().trim(),body.profile().phone(),body.profile().email().trim(),username);}
  BigDecimal amount=rows.stream().map(row->db.queryForObject("SELECT price FROM courses WHERE course_id=?",BigDecimal.class,row.courseId())).reduce(BigDecimal.ZERO,BigDecimal::add);
  String orderId=id("gk-");db.update("INSERT INTO storefront_orders(order_id,username,request_key,request_hash,amount) VALUES (?,?,?,?,?)",orderId,username,body.requestKey(),hash,amount);
  for(var row:rows){db.update("INSERT INTO storefront_order_items(order_id,class_id,course_id,course_title,class_code,amount) SELECT ?,c.class_id,co.course_id,co.name,c.class_code,co.price FROM classes c JOIN courses co ON co.course_id=c.course_id WHERE c.class_id=?",orderId,row.classId());db.update("DELETE FROM storefront_cart_items WHERE username=? AND course_id=? AND class_id=?",username,row.courseId(),row.classId());}
  return order(orderId);
 }
 private Order order(String orderId){return db.queryForObject("SELECT o.*,COALESCE(u.display_name,u.username) AS full_name,COALESCE(u.contact_email,u.email) AS email,u.phone FROM storefront_orders o JOIN users u ON u.username=o.username WHERE o.order_id=?",(r,n)->new Order(r.getString("order_id"),r.getString("username"),r.getString("full_name"),r.getString("email"),r.getString("phone"),r.getString("status"),r.getBigDecimal("amount"),r.getTimestamp("created_at").toInstant(),db.query("SELECT i.*,o.status,o.created_at FROM storefront_order_items i JOIN storefront_orders o ON o.order_id=i.order_id WHERE i.order_id=? ORDER BY i.class_id",(s,k)->new OrderItem(s.getString("order_id")+":"+s.getString("class_id"),s.getString("order_id"),s.getString("course_id"),s.getString("class_id"),s.getString("course_title"),s.getString("class_code"),s.getBigDecimal("amount"),s.getString("status"),s.getTimestamp("created_at").toInstant()),orderId),r.getString("payment_status"),r.getTimestamp("paid_at")==null?null:r.getTimestamp("paid_at").toInstant(),r.getString("fulfillment_error")),orderId);}
 @Transactional(readOnly=true)
 public List<Enrollment> enrollments(Authentication actor){String username=customer(actor);return db.query("SELECT cs.class_student_id,cs.status,cs.joined_at,c.class_id,c.course_id,c.class_code,c.status AS class_status,co.name FROM class_students cs JOIN students s ON s.student_id=cs.student_id JOIN classes c ON c.class_id=cs.class_id JOIN courses co ON co.course_id=c.course_id WHERE s.username=? ORDER BY cs.joined_at DESC",(r,n)->new Enrollment(r.getString("class_student_id"),r.getString("course_id"),r.getString("class_id"),r.getString("name"),r.getString("class_code"),r.getString("class_status"),r.getString("status"),r.getTimestamp("joined_at").toInstant()),username);}
 @Transactional(readOnly=true)
 public List<Order> orders(Authentication actor){String username=customer(actor);return db.queryForList("SELECT order_id FROM storefront_orders WHERE username=? ORDER BY created_at DESC",String.class,username).stream().map(this::order).toList();}
 @Transactional(readOnly=true)
 public List<Order> adminOrders(){return db.queryForList("SELECT order_id FROM storefront_orders ORDER BY created_at DESC LIMIT 200",String.class).stream().map(this::order).toList();}
 private void ensureCancelable(String orderId){
  if(db.queryForObject("SELECT count(*) FROM storefront_orders WHERE order_id=? AND payment_status='PAID'",Integer.class,orderId)>0||db.queryForObject("SELECT count(*) FROM payment_attempts WHERE order_id=? AND status IN ('CREATING','PENDING') AND expires_at>CURRENT_TIMESTAMP",Integer.class,orderId)>0)throw bad("Đơn đã thanh toán hoặc đang có mã thanh toán hiệu lực. Vui lòng kiểm tra thanh toán trước khi hủy.");
 }
 @Transactional
 public Order cancel(Authentication actor,String orderId){String username=customer(actor);userLock(username);if(db.queryForList("SELECT order_id FROM storefront_orders WHERE order_id=? AND username=? FOR UPDATE",orderId,username).isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"Không tìm thấy đơn đăng ký.");ensureCancelable(orderId);if(db.update("UPDATE storefront_orders SET status='CANCELLED' WHERE order_id=? AND status='PENDING'",orderId)!=1)throw bad("Chỉ có thể hủy đơn đang chờ xác nhận.");return order(orderId);}
 @Transactional
 public Order reject(String orderId){Order existing=order(orderId);userLock(existing.username());db.queryForObject("SELECT order_id FROM storefront_orders WHERE order_id=? FOR UPDATE",String.class,orderId);ensureCancelable(orderId);if(db.update("UPDATE storefront_orders SET status='CANCELLED' WHERE order_id=? AND status='PENDING'",orderId)!=1)throw bad("Chỉ có thể từ chối đơn đang chờ xác nhận.");return order(orderId);}
 @Transactional
 public Order confirm(Authentication actor,String orderId){
  Order order=order(orderId);userLock(order.username());
  db.queryForObject("SELECT order_id FROM storefront_orders WHERE order_id=? FOR UPDATE",String.class,orderId);
  order=order(orderId);if("CONFIRMED".equals(order.status()))return order;if(!"PENDING".equals(order.status()))throw bad("Đơn đã hủy.");
  String student=db.queryForObject("SELECT student_id FROM students WHERE username=?",String.class,order.username());
  for(var item:order.items().stream().sorted(Comparator.comparing(OrderItem::classId)).toList()){
   db.queryForObject("SELECT class_id FROM classes WHERE class_id=? FOR UPDATE",String.class,item.classId());
   if(db.queryForObject("SELECT count(*) FROM class_students WHERE class_id=? AND student_id=? AND status='ACTIVE'",Integer.class,item.classId(),student)==0)classes.addStudents(item.classId(),new AddStudentsRequest(List.of(student)),actor);
  }
  db.update("UPDATE storefront_orders SET status='CONFIRMED' WHERE order_id=?",orderId);return order(orderId);
 }
 @Transactional
 public String createCourse(CourseEdit body){String courseId=id("course-");db.update("INSERT INTO courses(course_id,name,status) VALUES (?,?,'ACTIVE')",courseId,body.title().trim());editCourse(courseId,body);return courseId;}
 @Transactional
 public void editCourse(String courseId,CourseEdit body){
  if(db.update("UPDATE courses SET name=?,category=?,level=?,duration=?,lesson_count=?,target=?,price=?,image_url=?,entry_description=?,description=?,outcomes=?::jsonb,syllabus=?::jsonb,published=?,featured=? WHERE course_id=?",body.title().trim(),body.category().trim(),body.level(),body.weeks(),body.lessons(),body.target(),body.price(),body.image(),body.entry(),body.description(),encode(body.outcomes()),encode(body.syllabus()),body.published(),body.featured(),courseId)!=1)throw new ApiException(HttpStatus.NOT_FOUND,"Không tìm thấy khóa học.");
 }
 @Transactional
 public void editOpening(String classId,OpeningEdit body){
  if(body.open()&&(body.location()==null||body.location().isBlank()||body.time()==null||body.time().isBlank()))throw bad("Cần địa điểm và lịch học trước khi mở đăng ký.");
  if(db.queryForList("SELECT class_id FROM classes WHERE class_id=? FOR UPDATE",classId).isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"Không tìm thấy lớp.");
  int occupied=db.queryForObject("SELECT "+OCCUPIED+" FROM classes c WHERE c.class_id=?",Integer.class,classId);
  if(body.capacity()<occupied)throw bad("Sĩ số không được nhỏ hơn số học viên và đăng ký đang chờ.");
  db.update("UPDATE classes SET storefront_open=?,capacity=?,delivery_mode=?,location=?,schedule_label=? WHERE class_id=?",body.open(),body.capacity(),body.mode(),body.location(),body.time(),classId);
 }
 @Transactional
 public String consultation(Consultation body){if((body.email()==null||body.email().isBlank())&&(body.phone()==null||body.phone().isBlank()))throw bad("Vui lòng cung cấp email hoặc số điện thoại.");String id=id("ask-");db.update("INSERT INTO storefront_consultations(consultation_id,full_name,phone,email,category,message) VALUES (?,?,?,?,?,?)",id,body.fullName(),body.phone(),body.email(),body.category(),body.message());return id;}
 @Transactional(readOnly=true)
 public List<Map<String,Object>> consultations(){return db.queryForList("SELECT consultation_id AS id,full_name AS \"fullName\",phone,email,category,message,status,created_at AS \"createdAt\" FROM storefront_consultations ORDER BY created_at DESC LIMIT 200");}
}
