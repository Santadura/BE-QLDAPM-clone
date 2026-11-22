package com.example.demo.payment;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
public class PaymentDtos {
 public record Start(@NotBlank @Pattern(regexp="VNPAY|MOMO") String provider) {}
 public record Provider(boolean enabled,boolean sandbox,String reason) {}
 public record Attempt(String id,String orderId,String provider,BigDecimal amount,String status,String payUrl,String qrData,String transactionId,Instant createdAt,Instant expiresAt) {}
 public record Status(String orderId,String orderStatus,String paymentStatus,BigDecimal amount,Instant paidAt,String fulfillmentError,Attempt attempt,boolean sandbox) {}
 public record Receipt(String orderId,boolean duplicate) {}
}
