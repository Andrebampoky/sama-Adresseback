package sn.samaadresse.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import sn.samaadresse.address.domain.Address;
import sn.samaadresse.address.domain.AddressStatus;
import sn.samaadresse.address.domain.PlaceType;
import sn.samaadresse.address.repository.AddressRepository;
import sn.samaadresse.address.service.AddressService;
import sn.samaadresse.admin.AdminController;
import sn.samaadresse.auth.service.AuthService;
import sn.samaadresse.auth.service.LoginAttemptLimiter;
import sn.samaadresse.common.FieldValidationException;
import sn.samaadresse.report.AddressReportRepository;
import sn.samaadresse.route.RouteController;
import sn.samaadresse.security.TokenService;
import sn.samaadresse.user.domain.UserAccount;
import sn.samaadresse.user.repository.UserAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Authentification, indicateurs et itinéraire testés sans contexte Spring. */
class AuthAndControllersUnitTest {
    private static int status(ResponseStatusException e) {
        return e.getStatusCode().value();
    }

    private static TokenService tokens() {
        byte[] secret = "cle-de-test-cle-de-test-cle-de-test-32".getBytes(StandardCharsets.UTF_8);
        return new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(secret, "HmacSHA256"))),
                Duration.ofHours(1));
    }

    @Test
    void registerAndLoginRules() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(2, Duration.ofMinutes(15));
        AuthService auth = new AuthService(users, encoder, tokens(), limiter);
        when(users.saveAndFlush(any(UserAccount.class))).thenAnswer(i -> {
            UserAccount u = i.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });

        assertThat(AuthService.normalizeEmail("  Awa@Exemple.TEST ")).isEqualTo("awa@exemple.test");
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> auth.register("a@exemple.test", "sanschiffre", "Awa")))).isEqualTo(400);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> auth.register("a@exemple.test", "é".repeat(40) + "1", "Awa")))).isEqualTo(400);
        AuthService.AuthResult registered = auth.register("A@Exemple.test", "motdepasse-42", " Awa ");
        assertThat(registered.user().getEmail()).isEqualTo("a@exemple.test");
        assertThat(registered.user().getPasswordHash()).startsWith("{bcrypt}");
        assertThat(registered.token().value()).isNotBlank();
        when(users.existsByEmail("a@exemple.test")).thenReturn(true);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> auth.register("a@exemple.test", "motdepasse-42", "Awa")))).isEqualTo(409);

        when(users.findByEmail("a@exemple.test")).thenReturn(Optional.of(registered.user()));
        assertThat(auth.login("a@exemple.test", "motdepasse-42").user()).isSameAs(registered.user());
        // Même message pour un mauvais mot de passe ou un e-mail inconnu ; blocage après 2 échecs.
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> auth.login("a@exemple.test", "faux-42")))).isEqualTo(401);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> auth.login("a@exemple.test", "faux-42")))).isEqualTo(401);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> auth.login("a@exemple.test", "motdepasse-42")))).isEqualTo(429);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> auth.login("inconnu@exemple.test", "x")))).isEqualTo(401);
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> auth.currentUser(UUID.randomUUID())))).isEqualTo(401);
    }

    @Test
    void statsAreAggregatedByMonthAndType() {
        AddressRepository addresses = mock(AddressRepository.class);
        AddressReportRepository reports = mock(AddressReportRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(addresses.count()).thenReturn(3L);
        when(addresses.countByStatus(AddressStatus.VALIDATED)).thenReturn(2L);
        Instant now = Instant.now();
        when(addresses.creationsSince(any())).thenReturn(List.of(
                new Object[] {now, PlaceType.HOUSE}, new Object[] {now, PlaceType.HOUSE},
                new Object[] {now, PlaceType.BUSINESS}));
        AdminController.Stats stats = new AdminController(addresses, reports, users).stats();
        assertThat(stats.totalAddresses()).isEqualTo(3);
        assertThat(stats.byStatus()).containsEntry(AddressStatus.VALIDATED, 2L);
        assertThat(stats.monthly()).hasSize(12);
        assertThat(stats.monthly().get(11).total()).isEqualTo(3);
        assertThat(stats.monthly().get(11).byType()).containsEntry(PlaceType.HOUSE, 2L).containsEntry(PlaceType.INSTITUTION, 0L);
    }

    @Test
    void routeControllerHandlesConfigurationAndProviderErrors() throws Exception {
        AddressService addresses = mock(AddressService.class);
        Address target = Address.createdByOwner(UUID.randomUUID(), "ZIG-KAN-6826", "Maison", PlaceType.HOUSE, "Kandé",
                "Ziguinchor", null, new BigDecimal("12.5681"), new BigDecimal("-16.2733"), true, false);
        when(addresses.findValidatedPublicAddress("ZIG-KAN-6826")).thenReturn(target);
        MockHttpServletRequest request = new MockHttpServletRequest();
        ObjectMapper json = new ObjectMapper();

        RouteController withoutKey = new RouteController(addresses, json, "http://127.0.0.1:1", "");
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> withoutKey.route(request, 12.5, -16.2, "ZIG-KAN-6826", "foot-walking")))).isEqualTo(503);
        assertThrows(FieldValidationException.class,
                () -> withoutKey.route(request, 91, -16.2, "ZIG-KAN-6826", "foot-walking"));
        assertThrows(FieldValidationException.class,
                () -> withoutKey.route(request, 12.5, -16.2, "ZIG-KAN-6826", "avion"));

        AtomicInteger code = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = (code.get() == 200 ? "{\"features\":[{\"geometry\":{\"coordinates\":[[-16.2,12.5]]},"
                    + "\"properties\":{\"summary\":{\"distance\":10,\"duration\":5}}}]}" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RouteController controller = new RouteController(addresses, json,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "cle");
            RouteController.RouteResponse route = controller.route(request, 12.5, -16.2, "ZIG-KAN-6826", "driving-car");
            assertThat(route.coordinates().get(0)).containsExactly(12.5, -16.2);
            assertThat(route.durationSeconds()).isEqualTo(5);
            for (Map.Entry<Integer, Integer> c : Map.of(401, 503, 403, 503, 400, 422, 429, 429, 502, 502).entrySet()) {
                code.set(c.getKey());
                assertThat(status(assertThrows(ResponseStatusException.class,
                        () -> controller.route(request, 12.5, -16.2, "ZIG-KAN-6826", "foot-walking"))))
                        .as("ORS %s", c.getKey()).isEqualTo(c.getValue());
            }
            code.set(200);
        } finally {
            server.stop(0);
        }
        RouteController unreachable = new RouteController(addresses, json, "http://127.0.0.1:1", "cle");
        assertThat(status(assertThrows(ResponseStatusException.class,
                () -> unreachable.route(request, 12.5, -16.2, "ZIG-KAN-6826", "foot-walking")))).isEqualTo(502);
    }

    @Test
    void routeFallsBackToKeylessOsrmWhenNoOrsKey() throws Exception {
        AddressService addresses = mock(AddressService.class);
        Address target = Address.createdByOwner(UUID.randomUUID(), "ZIG-KAN-6826", "Maison", PlaceType.HOUSE, "Kandé",
                "Ziguinchor", null, new BigDecimal("12.5681"), new BigDecimal("-16.2733"), true, false);
        when(addresses.findValidatedPublicAddress("ZIG-KAN-6826")).thenReturn(target);
        java.util.concurrent.atomic.AtomicReference<String> path = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger code = new AtomicInteger(200);
        AtomicInteger bodyKind = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().toString());
            String json = bodyKind.get() == 0
                    ? "{\"code\":\"Ok\",\"routes\":[{\"distance\":979.6,\"duration\":700,\"geometry\":{\"coordinates\":[[-16.27,12.56],[-16.2733,12.5681]]}}]}"
                    : "{\"code\":\"NoRoute\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        MockHttpServletRequest request = new MockHttpServletRequest();
        try {
            RouteController controller = new RouteController(addresses, new ObjectMapper(), "http://127.0.0.1:1", "",
                    true, "http://127.0.0.1:" + server.getAddress().getPort());
            RouteController.RouteResponse route = controller.route(request, 12.56, -16.27, "ZIG-KAN-6826", "foot-walking");
            assertThat(route.provider()).isEqualTo("osrm");
            assertThat(route.distanceMeters()).isEqualTo(979.6);
            assertThat(route.coordinates().get(1)).containsExactly(12.5681, -16.2733);
            assertThat(path.get()).startsWith("/routed-foot/route/v1/foot/-16.270000,12.560000;-16.273300,12.568100");
            controller.route(request, 12.56, -16.27, "ZIG-KAN-6826", "driving-car");
            assertThat(path.get()).startsWith("/routed-car/route/v1/car/");
            bodyKind.set(1);
            assertThat(status(assertThrows(ResponseStatusException.class,
                    () -> controller.route(request, 12.56, -16.27, "ZIG-KAN-6826", "foot-walking")))).isEqualTo(422);
            bodyKind.set(0);
            code.set(429);
            assertThat(status(assertThrows(ResponseStatusException.class,
                    () -> controller.route(request, 12.56, -16.27, "ZIG-KAN-6826", "foot-walking")))).isEqualTo(429);
            code.set(500);
            assertThat(status(assertThrows(ResponseStatusException.class,
                    () -> controller.route(request, 12.56, -16.27, "ZIG-KAN-6826", "foot-walking")))).isEqualTo(502);
        } finally {
            server.stop(0);
        }
    }
}
