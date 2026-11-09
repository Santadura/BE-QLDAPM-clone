package vn.iig.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record Course(
    @NotBlank @Size(max=50) String courseId,
    @NotBlank @Size(max=150) String name,
    @NotBlank @Pattern(regexp="ACTIVE|INACTIVE") String status
) {}
