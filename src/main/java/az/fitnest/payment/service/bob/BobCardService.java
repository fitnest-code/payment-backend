package az.fitnest.payment.service.bob;

import az.fitnest.payment.client.bob.BobRestClient;
import az.fitnest.payment.dto.bob.BobOrderStatusResponse;
import az.fitnest.payment.exception.BobPaymentException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.repository.UserCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * BOB saved-card (binding) persistence and unbind — separate from payment orchestration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BobCardService {

    private final UserCardRepository userCardRepository;
    private final BobRestClient bobRestClient;

    @Transactional(readOnly = true)
    public List<UserCard> getUserSavedCards(Long userId) {
        return userCardRepository.findAllByUserId(userId);
    }

    @Transactional(readOnly = true)
    public UserCard requireSavedCard(Long userId, String cardId) {
        return userCardRepository.findByUserIdAndCardId(userId, cardId)
                .orElseThrow(() -> new BobPaymentException("CARD_NOT_FOUND", "Saxlanılmış kart tapılmadı"));
    }

    @Transactional
    public void deleteSavedCard(Long userId, String cardId) {
        UserCard userCard = userCardRepository.findByUserIdAndCardId(userId, cardId)
                .orElseThrow(() -> new BobPaymentException("CARD_NOT_FOUND", "Kart tapılmadı"));

        try {
            bobRestClient.unbindCard(cardId);
        } catch (Exception e) {
            log.warn("[BOB][Card] Bank unbindCard failed for cardId={}, deleting locally: {}",
                    cardId, e.getMessage());
        }

        userCardRepository.delete(userCard);
        log.info("[BOB][Card] Deleted saved card userId={}, cardId={}", userId, cardId);
    }

    public void checkAndSaveUserCard(Payment payment, BobOrderStatusResponse statusResponse) {
        if (payment == null || payment.getUserId() == null) {
            return;
        }

        String bindingId = statusResponse != null ? statusResponse.getResolvedBindingId() : null;
        boolean saveRequested = Boolean.TRUE.equals(payment.getAutoPaymentEnabled());

        if ((bindingId == null || bindingId.isBlank()) && saveRequested) {
            bindingId = "BOB_BIND_" + payment.getOrderId();
        }

        if (bindingId == null || bindingId.isBlank()) {
            return;
        }

        String cardMask = statusResponse != null && statusResponse.getPan() != null && !statusResponse.getPan().isBlank()
                ? statusResponse.getPan()
                : payment.getCardMask();
        String cardName = statusResponse != null
                && statusResponse.getCardholderName() != null
                && !statusResponse.getCardholderName().isBlank()
                ? statusResponse.getCardholderName()
                : "Bank of Baku Card";

        saveUserCard(payment.getUserId(), bindingId, cardMask, cardName);
    }

    private void saveUserCard(Long userId, String bindingId, String cardMask, String cardName) {
        try {
            Optional<UserCard> existing = userCardRepository.findByUserIdAndCardId(userId, bindingId);
            if (existing.isEmpty()) {
                UserCard userCard = UserCard.builder()
                        .userId(userId)
                        .cardId(bindingId)
                        .cardMask(cardMask != null ? cardMask : "**** **** **** ****")
                        .cardName(cardName != null ? cardName : "Bank Card")
                        .brand("Bank of Baku")
                        .reccPmntId(bindingId)
                        .build();
                userCardRepository.save(userCard);
                log.info("[BOB][Card] Saved binding userId={}, bindingId={}", userId, bindingId);
            }
        } catch (Exception e) {
            log.error("[BOB][Card] Failed to save binding userId={}, bindingId={}", userId, bindingId, e);
        }
    }
}
