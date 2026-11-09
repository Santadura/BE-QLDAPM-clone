package vn.iig.hr;

import java.math.BigDecimal;
import java.time.Instant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class SalaryRankModels {
    private SalaryRankModels() {}

    public record RankState(String id,String roleId,String rank,BigDecimal amount,
                            String status,Instant createdAt,long version) {
        public RankView publicView() {
            return new RankView(id,roleId,rank,amount,status,createdAt);
        }
    }
    public record RankView(String id,String roleId,String rank,BigDecimal amount,
                           String status,Instant createdAt) {}

    public record RankRequest(@NotBlank String roleId,
                              @NotBlank @Size(max=50) String rank,
                              @NotNull @PositiveOrZero BigDecimal amount,
                              String status) {}
    public record RankUpdate(@NotBlank @Size(max=50) String rank,
                             @NotNull @PositiveOrZero BigDecimal amount,
                             String status) {}
}
