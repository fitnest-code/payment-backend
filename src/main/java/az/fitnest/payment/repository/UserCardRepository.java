package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.UserCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserCardRepository extends JpaRepository<UserCard, Long> {
    List<UserCard> findAllByUserId(Long userId);

    /**
     * Find card by userId and cardId (user-scoped lookup).
     * This ensures card_id uniqueness is scoped to the user, not global.
     * SECURITY: Always use this instead of findByCardId() to prevent cross-user card access.
     */
    Optional<UserCard> findByUserIdAndCardId(Long userId, String cardId);

    Optional<UserCard> findByUserIdAndIsDefaultTrue(Long userId);
}
