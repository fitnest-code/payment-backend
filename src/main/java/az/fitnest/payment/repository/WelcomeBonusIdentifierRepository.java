package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.WelcomeBonusIdentifier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WelcomeBonusIdentifierRepository extends JpaRepository<WelcomeBonusIdentifier, Long> {

    boolean existsByUserId(Long userId);

    Optional<WelcomeBonusIdentifier> findByUserId(Long userId);

    boolean existsByPhoneHash(String phoneHash);

    boolean existsByEmailHash(String emailHash);
}
