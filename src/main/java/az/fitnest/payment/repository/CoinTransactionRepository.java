package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.CoinTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CoinTransactionRepository extends JpaRepository<CoinTransaction, Long> {

    Page<CoinTransaction> findByUserIdOrderByCreatedDateDesc(Long userId, Pageable pageable);

    Page<CoinTransaction> findAllByOrderByCreatedDateDesc(Pageable pageable);

    @Query("SELECT t FROM CoinTransaction t WHERE t.userId = :userId AND t.remainingAmount > 0 AND (t.expiryDate IS NULL OR t.expiryDate > :now) ORDER BY t.expiryDate ASC, t.createdDate ASC")
    List<CoinTransaction> findActiveEarnBatchesForSpending(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Query("SELECT t FROM CoinTransaction t WHERE t.remainingAmount > 0 AND t.expiryDate <= :now")
    List<CoinTransaction> findExpiredBatches(@Param("now") LocalDateTime now);

    @Query("SELECT COALESCE(SUM(t.remainingAmount), 0) FROM CoinTransaction t WHERE t.userId = :userId AND t.remainingAmount > 0 AND t.expiryDate IS NOT NULL AND t.expiryDate > :now AND t.expiryDate <= :threshold")
    BigDecimal findExpiringSoonAmount(@Param("userId") Long userId, @Param("now") LocalDateTime now, @Param("threshold") LocalDateTime threshold);

    @Query("SELECT MIN(t.expiryDate) FROM CoinTransaction t WHERE t.userId = :userId AND t.remainingAmount > 0 AND t.expiryDate IS NOT NULL AND t.expiryDate > :now")
    Optional<LocalDateTime> findNextExpiryDate(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
