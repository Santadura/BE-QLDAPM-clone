package com.example.demo.social;

import com.example.demo.dto.AuthDtos.AuthResponse;
import com.example.demo.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/social")
@RequiredArgsConstructor
public class SocialAuthController {
    private final SocialClients clients;
    private final SocialAccountService accounts;
    public record StartRequest(@NotBlank @Pattern(regexp="google|facebook") String provider,
                               @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String challenge, boolean link) {}
    public record ExchangeRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String code,
                                  @NotBlank @Pattern(regexp="[A-Za-z0-9._~-]{43,128}") String verifier) {}

    @GetMapping("/providers")
    public ResponseEntity<Map<String,Boolean>> providers() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("google",clients.enabled("google"),"facebook",clients.enabled("facebook")));
    }
    @PostMapping("/start")
    public ResponseEntity<Map<String,String>> start(@Valid @RequestBody StartRequest body,Authentication actor,
            @RequestHeader(value="Authorization",required=false) String authorization,HttpServletRequest request) {
        if (!clients.enabled(body.provider())) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"Đăng nhập bằng nhà cung cấp này chưa được mở. Vui lòng dùng tài khoản GK.");
        var flow=accounts.newFlow(body.provider(),body.challenge(),body.link(),actor,authorization);
        var session=request.getSession(true);
        session.setMaxInactiveInterval(600);
        session.setAttribute(SocialFlow.SESSION,flow);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("authorizationUrl","/api/oauth2/authorization/"+body.provider()+"?flow="+flow.key()));
    }
    @PostMapping("/exchange")
    public ResponseEntity<AuthResponse> exchange(@Valid @RequestBody ExchangeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.exchange(request.code(),request.verifier()));
    }
    @GetMapping("/connections")
    public List<SocialAccountService.Connection> connections(Authentication actor) { return accounts.connections(actor); }

    @ExceptionHandler(SocialException.class)
    public ResponseEntity<Map<String,String>> error(SocialException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).body(Map.of("code",ex.code(),"message","Không thể hoàn tất đăng nhập mạng xã hội. Vui lòng bắt đầu lại."));
    }
}
