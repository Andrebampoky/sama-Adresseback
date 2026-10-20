package sn.samaadresse.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sn.samaadresse.user.domain.Role;
import sn.samaadresse.user.repository.UserAccountRepository;

/**
 * Promotion explicite d'un compte existant au rôle ADMIN au démarrage.
 *
 * Aucun compte ni mot de passe administrateur n'est créé par le code : la personne s'inscrit
 * normalement, puis l'exploitant définit APP_BOOTSTRAP_ADMIN_EMAIL et redémarre l'API.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserAccountRepository users;
    private final String adminEmail;

    public AdminBootstrap(UserAccountRepository users, @Value("${app.security.bootstrap-admin-email:}") String adminEmail) {
        this.users = users;
        this.adminEmail = AuthService.normalizeEmail(adminEmail);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail.isEmpty()) {
            return;
        }
        users.findByEmail(adminEmail).ifPresentOrElse(user -> {
            if (user.getRole() != Role.ADMIN) {
                user.promoteToAdmin();
                log.info("Compte promu administrateur via APP_BOOTSTRAP_ADMIN_EMAIL.");
            }
        }, () -> log.warn("APP_BOOTSTRAP_ADMIN_EMAIL ne correspond à aucun compte inscrit ; aucune promotion."));
    }
}
