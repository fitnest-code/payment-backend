package az.fitnest.payment.event;

import az.fitnest.payment.dto.coin.WelcomeBonusRequest;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import az.fitnest.payment.service.CoinWalletService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventListener {

    private final UserCardRepository userCardRepository;
    private final PaymentRepository paymentRepository;
    private final CoinWalletService coinWalletService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "user-events", groupId = "payment-user-events-group")
    @Transactional
    public void consumeUserEvent(String message) {
        try {
            Map<String, Object> event = objectMapper.readValue(message, new TypeReference<>() {});
            String eventType = (String) event.get("eventType");
            Object userIdObj = event.get("userId");

            if (userIdObj == null) return;
            Long userId = parseUserId(userIdObj);
            if (userId == null) return;

            switch (eventType != null ? eventType : "") {
                case "USER_HARD_DELETED" -> {
                    log.warn("Received USER_HARD_DELETED event for userId: {}. Deleting user cards and payments.", userId);
                    userCardRepository.deleteByUserId(userId);
                    paymentRepository.deleteByUserId(userId);
                }
                case "REGISTRATION_COMPLETED", "WELCOME_BONUS_ELIGIBLE" -> {
                    log.info("Received {} event for userId: {}. Awarding Welcome Bonus.", eventType, userId);
                    handleWelcomeBonusEvent(userId, event);
                }
                default -> log.debug("Unhandled user event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process user event: {}", message, e);
        }
    }

    /**
     * Qeydiyyat tamamlandıqda Welcome Bonus-u avtomatik verir.
     * Client-dən heç bir məlumat (phone/email) gözlənilmir — fraud prevention.
     * phone və email user-backend-dən Kafka event payload-unda gəlir.
     */
    private void handleWelcomeBonusEvent(Long userId, Map<String, Object> event) {
        try {
            String phone = (String) event.getOrDefault("phone", null);
            String email = (String) event.getOrDefault("email", null);
            WelcomeBonusRequest bonusRequest = WelcomeBonusRequest.builder()
                    .phone(phone)
                    .email(email)
                    .build();
            coinWalletService.awardWelcomeBonus(userId, bonusRequest);
            log.info("Welcome Bonus successfully awarded to userId: {}", userId);
        } catch (Exception e) {
            // Idempotent: duplicate bonus xətası baş versə log et, tekrar throw etmə
            log.warn("Welcome Bonus could not be awarded to userId: {} — Reason: {}", userId, e.getMessage());
        }
    }

    private Long parseUserId(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        } else if (obj instanceof String) {
            try {
                return Long.parseLong((String) obj);
            } catch (NumberFormatException e) {
                log.error("Failed to parse userId from string: {}", obj);
            }
        }
        return null;
    }
}
