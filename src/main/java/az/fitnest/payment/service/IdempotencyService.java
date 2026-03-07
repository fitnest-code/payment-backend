package az.fitnest.payment.service;

import az.fitnest.payment.config.IdempotencyConfig;
import az.fitnest.payment.dto.epoint.EpointResponse;
import az.fitnest.payment.model.entity.IdempotencyKey;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.IdempotencyKeyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class IdempotencyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final IdempotencyConfig idempotencyConfig;
    private final StringRedisTemplate redisTemplate;

    private static final long MIN_TTL_FOR_REDIS_CACHE = 5;
    private static final String REDIS_KEY_PREFIX = "idempotency:";
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "idx_idempotency_key";

    private final Counter redisHitCounter;
    private final Counter redisMissCounter;
    private final Counter dbHitCounter;
    private final Counter dbMissCounter;
    private final Counter redisDeserializeFailCounter;
    private final Counter dbDeserializeFailCounter;
    private final Counter duplicateInsertRaceCounter;
    private final Timer lookupTimer;
    private final Timer storeTimer;

    private final AtomicReference<Instant> lastCleanupRun = new AtomicReference<>();
    private final AtomicLong lastCleanupDeletedCount = new AtomicLong(0);

    public IdempotencyService(
            IdempotencyKeyRepository idempotencyKeyRepository,
            ObjectMapper objectMapper,
            IdempotencyConfig idempotencyConfig,
            @Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
        this.idempotencyConfig = idempotencyConfig;
        this.redisTemplate = redisTemplate;

        this.redisHitCounter = Counter.builder("idempotency.cache.redis.hit").register(meterRegistry);
        this.redisMissCounter = Counter.builder("idempotency.cache.redis.miss").register(meterRegistry);
        this.dbHitCounter = Counter.builder("idempotency.cache.db.hit").register(meterRegistry);
        this.dbMissCounter = Counter.builder("idempotency.cache.db.miss").register(meterRegistry);
        this.redisDeserializeFailCounter = Counter.builder("idempotency.cache.redis.deserialize_fail").register(meterRegistry);
        this.dbDeserializeFailCounter = Counter.builder("idempotency.cache.db.deserialize_fail").register(meterRegistry);
        this.duplicateInsertRaceCounter = Counter.builder("idempotency.store.duplicate_race").register(meterRegistry);
        this.lookupTimer = Timer.builder("idempotency.lookup.duration").register(meterRegistry);
        this.storeTimer = Timer.builder("idempotency.store.duration").register(meterRegistry);
    }

    public Optional<EpointResponse> getCachedResponse(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        Instant now = Instant.now();

        return lookupTimer.record(() -> {
            if (idempotencyConfig.isRedisEnabled()) {
                try {
                    Optional<EpointResponse> redisResult = getFromRedis(idempotencyKey);
                    if (redisResult.isPresent()) {
                        return redisResult;
                    }
                } catch (RedisConnectionFailureException e) {
                    log.warn("Redis unavailable for idempotency lookup (key: {}), falling back to DB",
                            maskKey(idempotencyKey), e);
                } catch (org.springframework.data.redis.RedisSystemException e) {
                    log.warn("Redis system error for idempotency key: {}, falling back to DB",
                            maskKey(idempotencyKey), e);
                }
            }

            if (idempotencyConfig.isDetailedLoggingEnabled()) {
                log.debug("Checking database for idempotency key: {}", maskKey(idempotencyKey));
            }
            return getCachedResponseFromDatabase(idempotencyKey, now);
        });
    }

    private Optional<EpointResponse> getFromRedis(String idempotencyKey) {
        String redisKey = REDIS_KEY_PREFIX + idempotencyKey;
        String cachedResponse = redisTemplate.opsForValue().get(redisKey);

        if (cachedResponse == null) {
            redisMissCounter.increment();
            return Optional.empty();
        }

        if (idempotencyConfig.isDetailedLoggingEnabled()) {
            log.debug("Found idempotency key in Redis cache: {}", maskKey(idempotencyKey));
        }

        try {
            EpointResponse result = objectMapper.readValue(cachedResponse, EpointResponse.class);
            redisHitCounter.increment();
            return Optional.of(result);
        } catch (JsonProcessingException e) {
            redisDeserializeFailCounter.increment();
            log.error("Corrupt JSON in Redis for key: {}. Deleting and falling back to DB.", maskKey(idempotencyKey), e);
            try {
                redisTemplate.delete(redisKey);
            } catch (Exception deleteEx) {
                log.warn("Failed to delete corrupt Redis key: {}", maskKey(redisKey), deleteEx);
            }
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    private Optional<EpointResponse> getCachedResponseFromDatabase(String idempotencyKey, Instant now) {
        Optional<IdempotencyKey> optKey = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);

        if (optKey.isEmpty()) {
            dbMissCounter.increment();
            return Optional.empty();
        }

        IdempotencyKey key = optKey.get();

        if (!key.getExpiresAt().isAfter(now)) {
            dbMissCounter.increment();
            return Optional.empty();
        }

        if (key.getResponseBody() == null || key.getResponseBody().isBlank()) {
            dbDeserializeFailCounter.increment();
            log.error("Corrupt idempotency record (null/blank responseBody) for key: {}, id: {}. Deleting row.",
                    maskKey(idempotencyKey), key.getId());
            idempotencyKeyRepository.deleteById(key.getId());
            return Optional.empty();
        }

        try {
            EpointResponse response = objectMapper.readValue(key.getResponseBody(), EpointResponse.class);
            dbHitCounter.increment();

            if (idempotencyConfig.isRedisEnabled()) {
                long remainingSeconds = key.getExpiresAt().getEpochSecond() - now.getEpochSecond();
                if (remainingSeconds >= MIN_TTL_FOR_REDIS_CACHE) {
                    cacheInRedis(idempotencyKey, key.getResponseBody(), key.getExpiresAt(), now);
                }
            }

            return Optional.of(response);
        } catch (JsonProcessingException e) {
            dbDeserializeFailCounter.increment();
            log.error("Corrupt JSON in DB for key: {}, id: {}. Deleting row.", maskKey(idempotencyKey), key.getId(), e);
            idempotencyKeyRepository.deleteById(key.getId());
            return Optional.empty();
        }
    }

    @Transactional
    public EpointResponse persistIdempotentResponse(String idempotencyKey, EpointResponse response, Payment payment) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return response;
        }
        if (response == null) {
            log.warn("Attempted to store null response for key: {}. Skipping.", maskKey(idempotencyKey));
            return null;
        }

        return storeTimer.record(() -> {
            Instant now = Instant.now();

            try {
                String responseBody = objectMapper.writeValueAsString(response);
                Instant expiresAt = now.plusSeconds(idempotencyConfig.getTtlSeconds());

                IdempotencyKey entity = buildKeyEntity(idempotencyKey, response, payment, responseBody, now, expiresAt);
                idempotencyKeyRepository.save(entity);
                log.debug("Stored idempotency key with TTL {} hours: {}",
                        idempotencyConfig.getTtlHours(), maskKey(idempotencyKey));

                cachePersistedBody(idempotencyKey, responseBody, expiresAt, now);
                enforceMaxEntries(now);

                return response;

            } catch (JsonProcessingException e) {
                log.error("Failed to serialize response for key: {}", maskKey(idempotencyKey), e);
                return response;
            } catch (DataIntegrityViolationException e) {
                return handleDuplicateKeyRace(idempotencyKey, response, now, e);
            }
        });
    }

    @Deprecated(forRemoval = true)
    @Transactional
    public EpointResponse storeResponse(String idempotencyKey, EpointResponse response, Payment payment) {
        return persistIdempotentResponse(idempotencyKey, response, payment);
    }

    private void enforceMaxEntries(Instant now) {
        try {
            long activeKeys = idempotencyKeyRepository.countActiveKeys(now);
            if (activeKeys > idempotencyConfig.getMaxEntries()) {
                int evictCount = (int) Math.max(1, idempotencyConfig.getMaxEntries() / 10);
                int evicted = idempotencyKeyRepository.evictOldestKeys(evictCount);
                log.warn("Max entries exceeded ({}/{}). Evicted {} nearest-expiry rows.",
                        activeKeys, idempotencyConfig.getMaxEntries(), evicted);
            }
        } catch (Exception e) {
            log.error("Error enforcing max-entries limit", e);
        }
    }

    private IdempotencyKey buildKeyEntity(String idempotencyKey, EpointResponse response,
                                          Payment payment, String responseBody,
                                          Instant now, Instant expiresAt) {
        return IdempotencyKey.builder()
                .idempotencyKey(idempotencyKey)
                .paymentId(payment != null ? payment.getId() : null)
                .responseStatus(response.status())
                .responseTransactionId(response.transaction())
                .responseOrderId(response.orderId())
                .responseBody(responseBody)
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();
    }

    private EpointResponse handleDuplicateKeyRace(String idempotencyKey, EpointResponse response,
                                                   Instant now, DataIntegrityViolationException e) {
        if (isIdempotencyKeyConstraintViolation(e)) {
            duplicateInsertRaceCounter.increment();
            log.warn("Duplicate idempotency key (concurrent race): {}. Returning winning record.", maskKey(idempotencyKey));
            return loadWinningResponse(idempotencyKey, response, now);
        }
        log.error("DataIntegrityViolationException for key {} is NOT idempotency-key constraint. Rethrowing.",
                maskKey(idempotencyKey), e);
        throw e;
    }

    private void cachePersistedBody(String idempotencyKey, String responseBody, Instant expiresAt, Instant now) {
        if (idempotencyConfig.isRedisEnabled()) {
            cacheInRedis(idempotencyKey, responseBody, expiresAt, now);
        }
    }

    private EpointResponse loadWinningResponse(String idempotencyKey, EpointResponse fallback, Instant now) {
        return idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    if (idempotencyConfig.isRedisEnabled()) {
                        long remainingSeconds = existing.getExpiresAt().getEpochSecond() - now.getEpochSecond();
                        if (remainingSeconds >= MIN_TTL_FOR_REDIS_CACHE) {
                            cacheInRedis(idempotencyKey, existing.getResponseBody(), existing.getExpiresAt(), now);
                        }
                    }
                    try {
                        return objectMapper.readValue(existing.getResponseBody(), EpointResponse.class);
                    } catch (JsonProcessingException ex) {
                        dbDeserializeFailCounter.increment();
                        log.error("Failed to deserialize winning record for key: {}", maskKey(idempotencyKey), ex);
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private boolean isIdempotencyKeyConstraintViolation(DataIntegrityViolationException e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null) {
            return true;
        }
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains(IDEMPOTENCY_KEY_CONSTRAINT)
                || lowerMessage.contains("idempotency_key")
                || lowerMessage.contains("unique");
    }

    private void cacheInRedis(String idempotencyKey, String responseBody, Instant expiresAt, Instant now) {
        try {
            long ttlSeconds = Math.max(1, expiresAt.getEpochSecond() - now.getEpochSecond());
            long redisTtl = Math.min(ttlSeconds, idempotencyConfig.getRedisTtlSeconds());

            String redisKey = REDIS_KEY_PREFIX + idempotencyKey;
            redisTemplate.opsForValue().set(redisKey, responseBody, redisTtl, TimeUnit.SECONDS);

            if (idempotencyConfig.isDetailedLoggingEnabled()) {
                log.debug("Cached in Redis with TTL {}s: {}", redisTtl, maskKey(idempotencyKey));
            }
        } catch (Exception e) {
            log.warn("Failed to cache in Redis: {}, continuing with DB only", maskKey(idempotencyKey), e);
        }
    }

    private static String maskKey(String key) {
        if (key == null) return "null";
        if (key.length() <= 16) return key;
        return key.substring(0, 12) + "***" + key.substring(key.length() - 4);
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyKey> getIdempotencyKeyRecord(String idempotencyKey) {
        return idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Scheduled(cron = "${payment.idempotency.cleanup-cron:0 0 * * * *}")
    @Transactional
    public void cleanupExpiredKeys() {
        try {
            Instant now = Instant.now();
            int deleted = deleteExpiredRows(now);
            long activeKeys = logCleanupStats(now, deleted);
            evictExcessRows(activeKeys, now);
        } catch (Exception e) {
            log.error("Error during idempotency key cleanup", e);
        }
    }

    private int deleteExpiredRows(Instant now) {
        int deleted = idempotencyKeyRepository.deleteExpiredKeys(now);
        lastCleanupRun.set(now);
        lastCleanupDeletedCount.set(deleted);
        if (deleted > 0 && idempotencyConfig.isCleanupLoggingEnabled()) {
            log.info("[Cleanup] Removed {} expired idempotency keys", deleted);
        }
        return deleted;
    }

    private long logCleanupStats(Instant now, int deleted) {
        long activeKeys = idempotencyKeyRepository.countActiveKeys(now);
        if (idempotencyConfig.isCleanupLoggingEnabled()) {
            log.info("[Idempotency] {} active keys, {} hours TTL",
                    activeKeys, idempotencyConfig.getTtlHours());
        }
        return activeKeys;
    }

    private void evictExcessRows(long activeKeys, Instant now) {
        if (activeKeys > idempotencyConfig.getMaxEntries()) {
            int evictCount = (int) (activeKeys - idempotencyConfig.getMaxEntries());
            int evicted = idempotencyKeyRepository.evictOldestKeys(evictCount);
            log.warn("[Cleanup] Evicted {} excess rows (was {}/{})",
                    evicted, activeKeys, idempotencyConfig.getMaxEntries());
        }
    }

    public void clearRedisCache(String idempotencyKey) {
        if (!idempotencyConfig.isRedisEnabled()) {
            return;
        }
        try {
            String redisKey = REDIS_KEY_PREFIX + idempotencyKey;
            Boolean deleted = redisTemplate.delete(redisKey);
            if (deleted != null && deleted) {
                log.debug("Cleared Redis cache for key: {}", maskKey(idempotencyKey));
            }
        } catch (Exception e) {
            log.warn("Failed to clear Redis cache for key: {}", maskKey(idempotencyKey), e);
        }
    }

    public void clearAllRedisCache() {
        if (!idempotencyConfig.isRedisEnabled()) {
            return;
        }
        try {
            var scanOptions = org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(REDIS_KEY_PREFIX + "*")
                    .count(100)
                    .build();

            List<String> batch = new ArrayList<>(100);
            long deletedCount = 0;

            try (var cursor = redisTemplate.scan(scanOptions)) {
                while (cursor.hasNext()) {
                    batch.add(cursor.next());
                    if (batch.size() >= 100) {
                        Long count = redisTemplate.delete(batch);
                        deletedCount += (count != null ? count : 0);
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                Long count = redisTemplate.delete(batch);
                deletedCount += (count != null ? count : 0);
            }

            if (deletedCount > 0) {
                log.info("Cleared {} Redis idempotency cache entries", deletedCount);
            }
        } catch (Exception e) {
            log.warn("Failed to clear all Redis cache", e);
        }
    }

    @Transactional(readOnly = true)
    public IdempotencyStats getStats() {
        Instant now = Instant.now();
        long activeKeys = idempotencyKeyRepository.countActiveKeys(now);
        long expiredKeys = idempotencyKeyRepository.countExpiredKeys(now);

        Duration oldestActiveAge = idempotencyKeyRepository.findOldestActiveCreatedAt(now)
                .map(oldest -> Duration.between(oldest, now))
                .orElse(Duration.ZERO);

        return IdempotencyStats.builder()
                .activeKeys(activeKeys)
                .expiredKeys(expiredKeys)
                .ttlHours(idempotencyConfig.getTtlHours())
                .maxEntries(idempotencyConfig.getMaxEntries())
                .redisEnabled(idempotencyConfig.isRedisEnabled())
                .utilizationPercent((activeKeys * 100) / Math.max(1, idempotencyConfig.getMaxEntries()))
                .oldestActiveKeyAgeSeconds(oldestActiveAge.getSeconds())
                .redisHitCount((long) redisHitCounter.count())
                .redisMissCount((long) redisMissCounter.count())
                .dbHitCount((long) dbHitCounter.count())
                .dbMissCount((long) dbMissCounter.count())
                .redisDeserializeFailCount((long) redisDeserializeFailCounter.count())
                .dbDeserializeFailCount((long) dbDeserializeFailCounter.count())
                .duplicateInsertRaceCount((long) duplicateInsertRaceCounter.count())
                .lastCleanupRun(lastCleanupRun.get())
                .lastCleanupDeletedCount(lastCleanupDeletedCount.get())
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class IdempotencyStats {
        private long activeKeys;
        private long expiredKeys;
        private long ttlHours;
        private long maxEntries;
        private boolean redisEnabled;
        private long utilizationPercent;
        private long oldestActiveKeyAgeSeconds;
        private long redisHitCount;
        private long redisMissCount;
        private long dbHitCount;
        private long dbMissCount;
        private long redisDeserializeFailCount;
        private long dbDeserializeFailCount;
        private long duplicateInsertRaceCount;
        private Instant lastCleanupRun;
        private long lastCleanupDeletedCount;
    }
}
