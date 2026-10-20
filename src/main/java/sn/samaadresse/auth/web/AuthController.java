package sn.samaadresse.auth.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.samaadresse.auth.service.AuthService;
import sn.samaadresse.auth.web.AuthDtos.AuthResponse;
import sn.samaadresse.auth.web.AuthDtos.LoginRequest;
import sn.samaadresse.auth.web.AuthDtos.RegisterRequest;
import sn.samaadresse.auth.web.AuthDtos.UserResponse;
import sn.samaadresse.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentification")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un compte utilisateur (rôle USER)")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return AuthResponse.from(authService.register(request.email(), request.password(), request.displayName()));
    }

    @PostMapping("/login")
    @Operation(summary = "Se connecter et obtenir un jeton d'accès")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return AuthResponse.from(authService.login(request.email(), request.password()));
    }

    @GetMapping("/me")
    @Operation(summary = "Compte actuellement connecté", security = @SecurityRequirement(name = "bearer"))
    public UserResponse me(Authentication authentication) {
        return UserResponse.from(authService.currentUser(CurrentUser.id(authentication)));
    }
}
