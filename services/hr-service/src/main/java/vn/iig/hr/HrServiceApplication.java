package vn.iig.hr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HrServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(HrServiceApplication.class, args);
    }
}
