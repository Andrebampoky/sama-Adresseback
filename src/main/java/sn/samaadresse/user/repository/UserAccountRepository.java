package sn.samaadresse.user.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.samaadresse.user.domain.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    Page<UserAccount> findAllBy(Pageable pageable);

    long countByEnabledTrue();
}
