package sn.samaadresse.auth.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import sn.samaadresse.auth.service.AuthService.AuthResult;
import sn.samaadresse.user.domain.Role;
import sn.samaadresse.user.domain.UserAccount;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "L'e-mail est obligatoire.")
            @Email(message = "L'e-mail n'est pas valide.")
            @Size(max = 254, message = "L'e-mail est trop long.")
            String email,
            @NotBlank(message = "Le mot de passe est obligatoire.")
            @Size(min = 10, max = 72, message = "Le mot de passe doit contenir entre 10 et 72 caractères.")
            String password,
            @NotBlank(message = "Le nom affiché est obligatoire.")
            @Size(min = 2, max = 80, message = "Le nom affiché doit contenir entre 2 et 80 caractères.")
            String displayName
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "L'e-mail est obligatoire.") @Size(max = 254) String email,
            @NotBlank(message = "Le mot de passe est obligatoire.") @Size(max = 200) String password
    ) {
    }

    @Schema(description = "Compte authentifié. Le hachage du mot de passe n'est jamais renvoyé.")
    public record UserResponse(UUID id, String email, String displayName, Role role, Instant createdAt) {
        public static UserResponse from(UserAccount user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                    user.getCreatedAt());
        }
    }

    public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, UserResponse user) {
        public static AuthResponse from(AuthResult result) {
            return new AuthResponse(result.token().value(), "Bearer", result.token().expiresAt(),
                    UserResponse.from(result.user()));
        }
    }
}
