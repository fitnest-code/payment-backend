package az.fitnest.payment.event;

import az.fitnest.payment.dto.coin.WelcomeBonusRequest;
import az.fitnest.payment.exception.ConflictException;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import az.fitnest.payment.service.CoinWalletService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventListener {

    private static final String EVENT_USER_HARD_DELETED = "USER_HARD_DELETED";
    private static final String EVENT_REGISTRATION_COMPLETED = "REGISTRATION_COMPLETED";
    private static final String EVENT_WELCOME_BONUS_ELIGIBLE = "WELCOME_BONUS_ELIGIBLE";

    private final UserCardRepository userCardRepository;
    private final PaymentRepository paymentRepository;
    private final CoinWalletService coinWalletService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @KafkaListener(topics = "user-events", groupId = "payment-user-events-group")
    public void consumeUserEvent(String message) {
        final Map<String, Object> event;
        try {
            event = objectMapper.readValue(message, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize user-events payload", e);
            return;
        }

        String eventType = asString(event.get("eventType"));
        Long userId = parseUserId(event.get("userId"));
        if (userId == null || eventType == null) {
            return;
        }

        switch (eventType) {
            case EVENT_USER_HARD_DELETED -> handleUserHardDeleted(userId);
            case EVENT_REGISTRATION_COMPLETED, EVENT_WELCOME_BONUS_ELIGIBLE -> handleWelcomeBonusEvent(userId, event);
            default -> log.debug("Unhandled user event type: {}", eventType);
        }
    }

    private void handleUserHardDeleted(Long userId) {
        log.warn("Received USER_HARD_DELETED for userId={}", userId);
        transactionTemplate.executeWithoutResult(status -> {
            userCardRepository.deleteByUserId(userId);
            paymentRepository.deleteByUserId(userId);
        });
    }

    private void handleWelcomeBonusEvent(Long userId, Map<String, Object> event) {
        try {
            WelcomeBonusRequest bonusRequest = WelcomeBonusRequest.builder()
                    .phone(asString(event.get("phone")))
                    .email(asString(event.get("email")))
                    .build();
            coinWalletService.awardWelcomeBonus(userId, bonusRequest);
            log.info("Welcome bonus awarded for userId={}", userId);
        } catch (ConflictException e) {
            log.info("Welcome bonus skipped for userId={}: {}", userId, e.getMessage());
        } catch (Exception e) {
            log.warn("Welcome bonus could not be awarded for userId={}: {}", userId, e.getMessage());
        }
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static Long parseUserId(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                log.warn("Invalid userId in user-events payload: {}", text);
            }
        }
        return null;
    }
}
