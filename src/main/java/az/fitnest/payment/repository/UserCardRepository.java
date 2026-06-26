package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.UserCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserCardRepository extends JpaRepository<UserCard, Long> {
    List<UserCard> findAllByUserId(Long userId);

    Optional<UserCard> findByUserIdAndCardId(Long userId, String cardId);

    Optional<UserCard> findByCardNumberAndReccPmntId(String cardNumber, String reccPmntId);

    void deleteByUserId(Long userId);
}
