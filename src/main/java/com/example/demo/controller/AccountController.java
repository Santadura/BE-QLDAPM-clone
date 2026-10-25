package com.example.demo.controller;

import com.example.demo.dto.AccountDtos.*;
import com.example.demo.service.AccountManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {
    private final AccountManagementService accounts;

    @GetMapping
    public List<AccountResponse> list(Authentication actor) {
        return accounts.list(actor);
    }

    @GetMapping("/{username}")
    public AccountResponse detail(@PathVariable String username, Authentication actor) {
        return accounts.detail(username, actor);
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request, Authentication actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accounts.create(request, actor));
    }

    @PatchMapping("/{username}/status")
    public AccountResponse changeStatus(@PathVariable String username,
                                       @Valid @RequestBody ChangeStatusRequest request, Authentication actor) {
        return accounts.changeStatus(username, request.status(), actor);
    }
}
