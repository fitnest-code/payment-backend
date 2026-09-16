package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.CoinTransaction;
import az.fitnest.payment.model.enums.CoinTransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CoinTransactionRepository extends JpaRepository<CoinTransaction, Long> {

    // ALL category — bütün tranzaksiyalar
    Page<CoinTransaction> findByUserIdOrderByCreatedDateDesc(Long userId, Pageable pageable);

    // Type siyahısına görə filter — EXPIRED və s. üçün
    @Query("SELECT t FROM CoinTransaction t WHERE t.userId = :userId AND t.type IN :types ORDER BY t.createdDate DESC")
    Page<CoinTransaction> findByUserIdAndTypeInOrderByCreatedDateDesc(
            @Param("userId") Long userId,
            @Param("types") Collection<CoinTransactionType> types,
            Pageable pageable);

    // EARNED: BONUS, EARN, CAMPAIGN_BONUS, müsbət ADJUSTMENT və xərclənmiş Coin-lərin bərpası (REFUND + RESTORE_SPENT_COINS)
    @Query("SELECT t FROM CoinTransaction t WHERE t.userId = :userId AND (" +
           "t.type IN :typesWithoutAdjustment OR " +
           "(t.type = 'ADJUSTMENT' AND t.amount > 0) OR " +
           "(t.type = 'REFUND' AND t.refundAction = 'RESTORE_SPENT_COINS')" +
           ") ORDER BY t.createdDate DESC")
    Page<CoinTransaction> findEarnedTransactionsByUserId(
            @Param("userId") Long userId,
            @Param("typesWithoutAdjustment") Collection<CoinTransactionType> typesWithoutAdjustment,
            Pageable pageable);

    // SPENT: SPEND və qazanılmış Coin-lərin geri alınması (REFUND + REVERSE_EARNED_COINS)
    @Query("SELECT t FROM CoinTransaction t WHERE t.userId = :userId AND (" +
           "t.type = 'SPEND' OR " +
           "(t.type = 'REFUND' AND t.refundAction = 'REVERSE_EARNED_COINS')" +
           ") ORDER BY t.createdDate DESC")
    Page<CoinTransaction> findSpentTransactionsByUserId(
            @Param("userId") Long userId,
            Pageable pageable);

    Page<CoinTransaction> findAllByOrderByCreatedDateDesc(Pageable pageable);

    List<CoinTransaction> findByUserIdAndOrderIdAndTypeIn(Long userId, String orderId, Collection<CoinTransactionType> types);
}
