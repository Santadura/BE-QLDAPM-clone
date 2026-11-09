package vn.iig.scheduling;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class SchedulingModels {
    private SchedulingModels() {}
    public record Actor(String employeeId,String username,String role,String fullName) {}
    public record Slot(String id,String employeeId,String fullName,String role,
                       LocalDate availableDate,String dayOfWeek,String shift,
                       LocalTime startTime,LocalTime endTime,String availabilityType,
                       String campus,String note,String status,Instant createdAt,
                       Instant reviewedAt,String reviewedBy,long version,boolean deleted) {
        // Keep the exact names used by existing FE and Core availability APIs.
        public Map<String,Object> publicData() {
            Map<String,Object> response=new LinkedHashMap<>();
            response.put("id",id);
            response.put("employeeId",employeeId);
            response.put("fullName",fullName);
            response.put("role",role);
            response.put("availableDate",availableDate);
            response.put("day",dayOfWeek);
            response.put("shift",shift);
            response.put("start",startTime);
            response.put("end",endTime);
            response.put("type",availabilityType);
            response.put("campus",campus);
            response.put("note",note);
            response.put("status",status);
            response.put("submittedAt",createdAt);
            response.put("reviewedAt",reviewedAt);
            return response;
        }
    }
    public record AvailabilityRequest(LocalDate availableDate,String dayOfWeek,String shift,
                                      @NotNull LocalTime startTime,@NotNull LocalTime endTime,
                                      String availabilityType,String campus,String note) {}
    public record ReviewRequest(@NotBlank String status) {}
    public record ReservationRequest(String employeeId,LocalDate date,
                                     LocalTime startTime,LocalTime endTime,String campus) {}
    public record Lease(boolean approved,String reservationId) {}
}
