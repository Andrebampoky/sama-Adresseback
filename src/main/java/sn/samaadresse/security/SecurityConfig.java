package sn.samaadresse.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccountJwtConverter converter) throws Exception {
        http
                // API sans état : le jeton est transmis dans l'en-tête Authorization, jamais par cookie.
                // Un navigateur ne l'ajoute donc pas automatiquement : la protection CSRF n'est pas nécessaire.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/addresses", "/api/v1/addresses/*",
                                "/api/v1/addresses/*/photo", "/api/v1/routes").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Contrôle d'accès réel des opérations d'administration, indépendant de l'interface React.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, ex) ->
                                ProblemResponses.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "Authentification requise ou jeton invalide."))
                        .accessDeniedHandler((request, response, ex) ->
                                ProblemResponses.write(response, HttpServletResponse.SC_FORBIDDEN,
                                        "Droits insuffisants pour cette opération.")))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                ProblemResponses.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "Authentification requise."))
                        .accessDeniedHandler((request, response, e) ->
                                ProblemResponses.write(response, HttpServletResponse.SC_FORBIDDEN,
                                        "Droits insuffisants pour cette opération.")));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // BCrypt par défaut, avec préfixe {bcrypt} pour permettre une migration d'algorithme ultérieure.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeyProvider keys) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(keys.key()));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeyProvider keys) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(keys.key())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Vérifie expiration/date d'émission (validateurs par défaut) et l'émetteur attendu.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(TokenService.ISSUER)));
        return decoder;
    }
}
