package az.fitnest.payment.service;

import az.fitnest.payment.dto.epoint.EpointResponse;
import az.fitnest.payment.model.entity.IdempotencyKey;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.IdempotencyKeyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    /**
     * Check if an idempotency key has been used before.
     * If yes, return the cached response.
     * If no, return empty.
     */
    @Transactional(readOnly = true)
    public Optional<EpointResponse> getCachedResponse(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        return idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey)
                .filter(key -> key.getExpiresAt().isAfter(Instant.now()))
                .map(key -> {
                    try {
                        return objectMapper.readValue(key.getResponseBody(), EpointResponse.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize cached response for key: {}", idempotencyKey, e);
                        return null;
                    }
                });
    }

    /**
     * Store the response for the idempotency key.
     */
    @Transactional
    public void storeResponse(String idempotencyKey, EpointResponse response, Payment payment) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        try {
            String responseBody = objectMapper.writeValueAsString(response);

            IdempotencyKey key = IdempotencyKey.builder()
                    .idempotencyKey(idempotencyKey)
                    .paymentId(payment != null ? payment.getPaymentId() : null)
                    .responseStatus(response.status())
                    .responseTransactionId(response.transaction())
                    .responseOrderId(response.orderId())
                    .responseBody(responseBody)
                    .build();

            idempotencyKeyRepository.save(key);
            log.debug("Stored idempotency key: {}", idempotencyKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response for key: {}", idempotencyKey, e);
        }
    }

    /**
     * Clean up expired idempotency keys periodically.
     * Runs every hour.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanupExpiredKeys() {
        int deleted = idempotencyKeyRepository.deleteExpiredKeys(Instant.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired idempotency keys", deleted);
        }
    }
}

