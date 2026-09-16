package az.fitnest.payment.service.coin;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoinNotificationPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void sendPush(Long userId, String title, String body) {
        if (userId == null) {
            return;
        }
        String resolvedTitle = StringUtils.hasText(title) ? title.trim() : "FitNest";
        String resolvedBody = StringUtils.hasText(body) ? body.trim() : "";

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("timestamp", System.currentTimeMillis());
        event.put("type", "PUSH");
        event.put("recipient", String.valueOf(userId));
        event.put("subject", resolvedTitle);
        event.put("body", resolvedBody);
        event.put("variables", Map.of("userId", String.valueOf(userId)));

        try {
            kafkaTemplate.send("notification-events", String.valueOf(userId), objectMapper.writeValueAsString(event));
            log.info("Published push notification for userId={} title={}", userId, resolvedTitle);
        } catch (Exception e) {
            log.error("Failed to publish push notification for userId={}: {}", userId, e.getMessage());
            throw new IllegalStateException("Push notification publish failed", e);
        }
    }
}
