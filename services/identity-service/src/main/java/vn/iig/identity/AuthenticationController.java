package vn.iig.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    private final AuthenticationService auth;
    public AuthenticationController(AuthenticationService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public AuthenticationService.LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.username(), request.password());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value="Authorization", required=false) String authorization) {
        auth.logout(authorization);
    }
}
