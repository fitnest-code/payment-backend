package az.fitnest.payment.event;

import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
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
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "user-events", groupId = "payment-user-events-group")
    @Transactional
    public void consumeUserEvent(String message) {
        try {
            Map<String, Object> event = objectMapper.readValue(message, Map.class);
            String eventType = (String) event.get("eventType");
            Object userIdObj = event.get("userId");
            if ("USER_HARD_DELETED".equals(eventType) && userIdObj != null) {
                Long userId = parseUserId(userIdObj);
                if (userId != null) {
                    log.warn("Received USER_HARD_DELETED event for userId: {}. Deleting user cards and payments.", userId);
                    userCardRepository.deleteByUserId(userId);
                    paymentRepository.deleteByUserId(userId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process user event: {}", message, e);
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
