package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.CoinTerms;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CoinTermsRepository extends JpaRepository<CoinTerms, Long> {
    Optional<CoinTerms> findFirstByOrderByIdAsc();
}
