package com.example.demo.controller;

import com.example.demo.dto.AccountDtos.AccountResponse;
import com.example.demo.dto.ProfileDtos.*;
import com.example.demo.service.AccountManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {
    private final AccountManagementService accounts;

    @GetMapping
    public AccountResponse mine(Authentication actor) {
        return accounts.profile(actor);
    }

    @PatchMapping
    public AccountResponse update(@Valid @RequestBody UpdateProfileRequest request, Authentication actor) {
        return accounts.updateProfile(request, actor);
    }

    @GetMapping("/salary")
    public CurrentSalaryResponse salary(Authentication actor) {
        return accounts.profileSalary(actor);
    }

    @GetMapping("/certificates")
    public List<Map<String, Object>> certificates(Authentication actor) {
        return accounts.profileCertificates(actor);
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication actor) {
        accounts.changePassword(request, actor);
        return ResponseEntity.noContent().build();
    }
}
