package az.fitnest.payment.service;

import az.fitnest.payment.config.IdempotencyConfig;
import az.fitnest.payment.dto.epoint.EpointResponse;
import az.fitnest.payment.model.entity.IdempotencyKey;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.IdempotencyKeyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final IdempotencyConfig idempotencyConfig;

    @Qualifier("stringRedisTemplate")
    private final RedisTemplate<String, String> redisTemplate;

    private static final String REDIS_KEY_PREFIX = "idempotency:";

    /**
     * Check if an idempotency key has been used before.
     * Uses Redis for fast lookup, falls back to database if Redis is unavailable.
     * If yes, return the cached response.
     * If no, return empty.
     */
    public Optional<EpointResponse> getCachedResponse(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        try {
            // First, try to get from Redis (fast) if enabled
            if (idempotencyConfig.isRedisEnabled()) {
                String redisKey = REDIS_KEY_PREFIX + idempotencyKey;
                String cachedResponse = redisTemplate.opsForValue().get(redisKey);

                if (cachedResponse != null) {
                    if (idempotencyConfig.isDetailedLoggingEnabled()) {
                        log.debug("Found idempotency key in Redis cache: {}", idempotencyKey);
                    }
                    try {
                        return Optional.of(objectMapper.readValue(cachedResponse, EpointResponse.class));
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize cached response from Redis for key: {}", idempotencyKey, e);
                    }
                }
            }

            // If not in Redis, check database (persistent storage)
            if (idempotencyConfig.isDetailedLoggingEnabled()) {
                log.debug("Idempotency key not in Redis/disabled, checking database: {}", idempotencyKey);
            }
            return getCachedResponseFromDatabase(idempotencyKey);

        } catch (Exception e) {
            log.warn("Error checking Redis cache for idempotency key: {}, falling back to database", idempotencyKey, e);
            return getCachedResponseFromDatabase(idempotencyKey);
        }
    }

    /**
     * Retrieve cached response from database
     */
    @Transactional(readOnly = true)
    private Optional<EpointResponse> getCachedResponseFromDatabase(String idempotencyKey) {
        return idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey)
                .filter(key -> key.getExpiresAt().isAfter(Instant.now()))
                .map(key -> {
                    try {
                        EpointResponse response = objectMapper.readValue(key.getResponseBody(), EpointResponse.class);
                        // Cache it in Redis for next time if Redis is enabled
                        if (idempotencyConfig.isRedisEnabled()) {
                            cacheInRedis(idempotencyKey, key.getResponseBody(), key.getExpiresAt());
                        }
                        return response;
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize cached response from database for key: {}", idempotencyKey, e);
                        return null;
                    }
                });
    }

    /**
     * Store the response for the idempotency key in both Redis and database.
     * - Database: Persistent storage, expires after configurable TTL
     * - Redis: Fast retrieval, expires after configurable TTL (if enabled)
     */
    @Transactional
    public void storeResponse(String idempotencyKey, EpointResponse response, Payment payment) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        try {
            String responseBody = objectMapper.writeValueAsString(response);

            // Calculate expiration time
            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(idempotencyConfig.getTtlSeconds());

            // Store in database (persistent)
            IdempotencyKey key = IdempotencyKey.builder()
                    .idempotencyKey(idempotencyKey)
                    .paymentId(payment != null ? payment.getId() : null)
                    .responseStatus(response.status())
                    .responseTransactionId(response.transaction())
                    .responseOrderId(response.orderId())
                    .responseBody(responseBody)
                    .createdAt(now)
                    .expiresAt(expiresAt)
                    .build();

            IdempotencyKey savedKey = idempotencyKeyRepository.save(key);
            log.debug("Stored idempotency key in database with TTL {} hours: {}",
                    idempotencyConfig.getTtlHours(), idempotencyKey);

            // Also cache in Redis for faster retrieval (if enabled)
            if (idempotencyConfig.isRedisEnabled()) {
                cacheInRedis(idempotencyKey, responseBody, expiresAt);
            }

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response for key: {}", idempotencyKey, e);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Duplicate key race: another thread inserted the same idempotency key concurrently.
            // Reconcile by loading the existing record — this ensures deterministic behavior.
            log.warn("Duplicate idempotency key detected (concurrent insert race): {}. Reconciling with existing record.", idempotencyKey);
            idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey).ifPresent(existing -> {
                // Re-cache in Redis from the winning record so subsequent lookups are fast
                if (idempotencyConfig.isRedisEnabled()) {
                    cacheInRedis(idempotencyKey, existing.getResponseBody(), existing.getExpiresAt());
                }
            });
        } catch (Exception e) {
            log.error("Error storing idempotency key: {}", idempotencyKey, e);
        }
    }

    /**
     * Cache response in Redis with TTL
     */
    private void cacheInRedis(String idempotencyKey, String responseBody, Instant expiresAt) {
        try {
            long ttlSeconds = Math.max(1, expiresAt.getEpochSecond() - Instant.now().getEpochSecond());
            long redisTtl = Math.min(ttlSeconds, idempotencyConfig.getRedisTtlSeconds());

            String redisKey = REDIS_KEY_PREFIX + idempotencyKey;
            redisTemplate.opsForValue().set(redisKey, responseBody, redisTtl, TimeUnit.SECONDS);

            if (idempotencyConfig.isDetailedLoggingEnabled()) {
                log.debug("Cached idempotency key in Redis with TTL {} seconds: {}", redisTtl, idempotencyKey);
            }
        } catch (Exception e) {
            log.warn("Failed to cache idempotency key in Redis: {}, continuing with database only", idempotencyKey, e);
        }
    }

    /**
     * Get idempotency key record from database (for admin/debug purposes)
     */
    @Transactional(readOnly = true)
    public Optional<IdempotencyKey> getIdempotencyKeyRecord(String idempotencyKey) {
        return idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
    }

    /**
     * Clean up expired idempotency keys from database periodically.
     * Redis keys will automatically expire due to TTL.
     * Schedule configurable via application.yml: payment.idempotency.cleanup-cron
     */
    @Scheduled(cron = "${payment.idempotency.cleanup-cron:0 0 * * * *}")
    @Transactional
    public void cleanupExpiredKeys() {
        try {
            Instant now = Instant.now();
            int deleted = idempotencyKeyRepository.deleteExpiredKeys(now);

            if (deleted > 0) {
                if (idempotencyConfig.isCleanupLoggingEnabled()) {
                    log.info("🧹 Cleanup: Removed {} expired idempotency keys from database", deleted);
                }
            }

            // Log database stats
            long activeKeys = idempotencyKeyRepository.countActiveKeys(now);
            if (idempotencyConfig.isCleanupLoggingEnabled()) {
                log.info("📊 Idempotency Stats: {} active keys, {} hours TTL",
                        activeKeys, idempotencyConfig.getTtlHours());
            }

            // Warn if approaching max entries limit
            if (activeKeys > idempotencyConfig.getMaxEntries()) {
                log.warn("⚠️  WARNING: Active idempotency keys ({}) exceeds max entries ({}). " +
                         "Consider reducing TTL or increasing max entries.",
                        activeKeys, idempotencyConfig.getMaxEntries());
            }

        } catch (Exception e) {
            log.error("Error during idempotency key cleanup", e);
        }
    }

    /**
     * Clear Redis cache for a specific key (useful for debugging/testing)
     */
    public void clearRedisCache(String idempotencyKey) {
        try {
            String redisKey = REDIS_KEY_PREFIX + idempotencyKey;
            Boolean deleted = redisTemplate.delete(redisKey);
            if (deleted != null && deleted) {
                log.debug("Cleared Redis cache for idempotency key: {}", idempotencyKey);
            }
        } catch (Exception e) {
            log.warn("Failed to clear Redis cache for key: {}", idempotencyKey, e);
        }
    }

    /**
     * Clear all Redis cache keys (useful for maintenance/testing).
     * Uses SCAN instead of KEYS to avoid blocking Redis in production.
     */
    public void clearAllRedisCache() {
        try {
            long deletedCount = 0;
            var scanOptions = org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(REDIS_KEY_PREFIX + "*")
                    .count(100)
                    .build();

            try (var cursor = redisTemplate.scan(scanOptions)) {
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    Boolean deleted = redisTemplate.delete(key);
                    if (deleted != null && deleted) {
                        deletedCount++;
                    }
                }
            }

            if (deletedCount > 0) {
                log.info("Cleared {} Redis idempotency cache entries via SCAN", deletedCount);
            }
        } catch (Exception e) {
            log.warn("Failed to clear all Redis cache", e);
        }
    }

    /**
     * Get statistics about idempotency keys
     */
    @Transactional(readOnly = true)
    public IdempotencyStats getStats() {
        Instant now = Instant.now();
        long activeKeys = idempotencyKeyRepository.countActiveKeys(now);

        return IdempotencyStats.builder()
                .activeKeys(activeKeys)
                .ttlHours(idempotencyConfig.getTtlHours())
                .maxEntries(idempotencyConfig.getMaxEntries())
                .redisEnabled(idempotencyConfig.isRedisEnabled())
                .utilizationPercent((activeKeys * 100) / Math.max(1, idempotencyConfig.getMaxEntries()))
                .build();
    }

    /**
     * Statistics DTO
     */
    @lombok.Data
    @lombok.Builder
    public static class IdempotencyStats {
        private long activeKeys;
        private long ttlHours;
        private long maxEntries;
        private boolean redisEnabled;
        private long utilizationPercent;
    }
}

