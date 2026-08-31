package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.CoinWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CoinWalletRepository extends JpaRepository<CoinWallet, Long> {

    Optional<CoinWallet> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM CoinWallet w WHERE w.userId = :userId")
    Optional<CoinWallet> findByUserIdWithLock(@Param("userId") Long userId);

    @Query("SELECT w FROM CoinWallet w WHERE w.balance > 0 AND w.expiryDate IS NOT NULL AND w.expiryDate <= :now")
    List<CoinWallet> findExpiredWallets(@Param("now") LocalDateTime now);
}
