package az.fitnest.payment.service.bob;

import az.fitnest.payment.client.bob.BobRestClient;
import az.fitnest.payment.dto.bob.BobOrderStatusResponse;
import az.fitnest.payment.exception.BobPaymentException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.repository.UserCardRepository;
import az.fitnest.payment.util.CardBrandDetector;
import az.fitnest.payment.util.CardMaskUtil;
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
            log.warn("[BOB][Card] skip save: payment or userId null");
            return;
        }

        boolean saveRequested = Boolean.TRUE.equals(payment.getAutoPaymentEnabled());
        if (!saveRequested) {
            log.warn("[BOB][Card] skip save: saveCard not requested orderId={}", payment.getOrderId());
            return;
        }

        if (statusResponse != null) {
            statusResponse.flattenBankPayload();
        }

        String bindingId = statusResponse != null ? statusResponse.getResolvedBindingId() : null;
        if (bindingId == null || bindingId.isBlank()) {
            log.warn("[BOB][Card] saveCard requested but bank returned no bindingId for orderId={} orderStatus={} bindingInfo={} attributesPresent={}",
                    payment.getOrderId(),
                    statusResponse != null ? statusResponse.getOrderStatus() : null,
                    statusResponse != null && statusResponse.getBindingInfo() != null,
                    statusResponse != null && statusResponse.getAttributes() != null);
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
        String brand = resolveBrand(statusResponse, cardMask);
        String displayMask = CardMaskUtil.toLast4(cardMask);

        log.warn("[BOB][Card] saving binding userId={} orderId={} bindingId={} mask={} brand={}",
                payment.getUserId(), payment.getOrderId(), bindingId, displayMask, brand);
        saveUserCard(payment.getUserId(), bindingId, displayMask, cardName, brand);
    }

    private static String resolveBrand(BobOrderStatusResponse statusResponse, String cardMask) {
        if (statusResponse != null) {
            String paymentSystem = statusResponse.getResolvedPaymentSystem();
            if (paymentSystem != null && !paymentSystem.isBlank()) {
                String upper = paymentSystem.trim().toUpperCase();
                if (upper.contains("VISA")) {
                    return "VISA";
                }
                if (upper.contains("MASTER")) {
                    return "MASTERCARD";
                }
                return upper;
            }
        }
        String detected = CardBrandDetector.detectBrand(cardMask);
        return detected != null && !"UNKNOWN".equalsIgnoreCase(detected) ? detected : "UNKNOWN";
    }

    private void saveUserCard(Long userId, String bindingId, String cardMask, String cardName, String brand) {
        try {
            Optional<UserCard> existing = userCardRepository.findByUserIdAndCardId(userId, bindingId);
            if (existing.isEmpty()) {
                UserCard userCard = UserCard.builder()
                        .userId(userId)
                        .cardId(bindingId)
                        .cardMask(cardMask != null ? CardMaskUtil.toLast4(cardMask) : CardMaskUtil.EMPTY_DISPLAY)
                        .cardName(cardName != null ? cardName : "Bank Card")
                        .brand(brand != null ? brand : "UNKNOWN")
                        .reccPmntId(bindingId)
                        .build();
                userCardRepository.save(userCard);
                log.warn("[BOB][Card] Saved binding userId={}, bindingId={}, brand={}", userId, bindingId, brand);
            }
        } catch (Exception e) {
            log.error("[BOB][Card] Failed to save binding userId={}, bindingId={}", userId, bindingId, e);
        }
    }
}
