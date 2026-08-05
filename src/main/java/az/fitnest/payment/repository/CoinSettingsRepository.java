package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.CoinSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CoinSettingsRepository extends JpaRepository<CoinSettings, Long> {

    Optional<CoinSettings> findFirstByActiveTrueOrderByIdDesc();
}
