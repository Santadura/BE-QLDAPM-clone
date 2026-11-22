package com.example.demo.storefront;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class StoreDtos {
 public record Course(String id,String title,String category,String level,String duration,int lessons,
  String target,BigDecimal price,String image,String entry,String description,List<String> outcomes,List<String> syllabus,boolean featured,boolean published) {}
 public record Opening(String id,String code,String courseId,String date,String time,String location,String mode,int seats,int capacity,boolean open) {}
 public record Catalog(List<Course> courses,List<Opening> classes) {}
 public record CartRow(@NotBlank @Size(max=50) String courseId,@NotBlank @Size(max=50) String classId) {}
 public record CartMerge(@NotNull @Size(max=30) List<@Valid CartRow> items) {}
 public record Profile(@NotBlank @Size(max=100) String fullName,
  @NotBlank @Email @Size(max=100) String email,
  @Pattern(regexp="^$|^\\+?[0-9]{9,15}$") @Size(max=15) String phone) {}
 public record Checkout(@NotBlank @Size(max=80) String requestKey,
  @NotEmpty @Size(max=20) List<@Valid CartRow> items,
  @NotNull @Valid Profile profile) {}
 public record Enrollment(String id,String courseId,String classId,String title,String classCode,String classStatus,String status,Instant joinedAt) {}
 public record OrderItem(String id,String orderId,String courseId,String classId,String title,String classCode,
  BigDecimal amount,String status,Instant purchasedAt) {}
 public record Order(String id,String username,String fullName,String email,String phone,String status,BigDecimal amount,Instant createdAt,List<OrderItem> items,String paymentStatus,Instant paidAt,String fulfillmentError) {}
 public record CourseEdit(@NotBlank @Size(max=150) String title,@NotBlank @Size(max=40) String category,
  @Size(max=30) String level,@Min(0) @Max(260) Integer weeks,@Min(0) @Max(2000) int lessons,
  @Size(max=150) String target,@DecimalMin("0.0") @Digits(integer=12,fraction=2) BigDecimal price,
  @NotBlank @Pattern(regexp="/images/[a-zA-Z0-9._/-]+") @Size(max=500) String image,
  @Size(max=255) String entry,@Size(max=10000) String description,
  @NotNull @Size(max=100) List<@NotBlank @Size(max=1000) String> outcomes,
  @NotNull @Size(max=100) List<@NotBlank @Size(max=1000) String> syllabus,boolean published,boolean featured) {}
 public record OpeningEdit(boolean open,@Min(1) @Max(1000) int capacity,
  @NotBlank @Pattern(regexp="Tại trung tâm|Trực tuyến") String mode,
  @Size(max=255) String location,@Size(max=255) String time) {}
 public record Consultation(@Size(max=100) String fullName,@Size(max=15) @Pattern(regexp="^$|^\\+?[0-9]{9,15}$") String phone,
  @Email @Size(max=100) String email,@Size(max=50) String category,@Size(max=2000) String message,@AssertTrue boolean consent) {}
}
