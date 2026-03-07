package az.fitnest.payment.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Configuration
@ConfigurationProperties(prefix = "payment.idempotency")
@Getter
@Setter
@Slf4j
public class IdempotencyConfig {

    private long ttlHours = 24;

    private String cleanupCron = "0 0 * * * *";

    private long maxEntries = 100_000;

    private boolean redisEnabled = true;

    private long redisTtlSeconds = 86400;

    private boolean cleanupLoggingEnabled = true;

    private boolean detailedLoggingEnabled = false;

    public long getTtlSeconds() {
        return ttlHours * 3600;
    }

    public long getMaxAgeSeconds() {
        return getTtlSeconds();
    }

    @PostConstruct
    void validateConfig() {
        if (cleanupCron != null && ttlHours <= 4) {
            String[] parts = cleanupCron.trim().split("\\s+");
            if (parts.length >= 4) {
                String hourField = parts[2];
                if (!hourField.contains("*") && !hourField.contains("/")) {
                    log.warn("[Config] Idempotency cleanup cron '{}' appears to run daily or less frequently, " +
                            "but TTL is only {} hours. Expired rows may accumulate. " +
                            "Consider a more frequent cron (e.g., '0 */15 * * * *' for every 15 minutes).",
                            cleanupCron, ttlHours);
                }
            }
        }

        if (redisEnabled && redisTtlSeconds > getTtlSeconds()) {
            log.warn("[Config] Redis TTL ({}s) exceeds DB TTL ({}s / {} hours). " +
                    "Redis may serve stale entries after DB considers them expired. " +
                    "Set redis-ttl-seconds <= ttl-hours * 3600.",
                    redisTtlSeconds, getTtlSeconds(), ttlHours);
        }

        if (maxEntries < 100 && ttlHours >= 24) {
            log.warn("[Config] max-entries ({}) is very low for a {} hour TTL. " +
                    "This may cause frequent eviction under normal traffic.",
                    maxEntries, ttlHours);
        }

        log.info("[Config] Idempotency: TTL={}h, maxEntries={}, redisEnabled={}, redisTTL={}s, cron='{}'",
                ttlHours, maxEntries, redisEnabled, redisTtlSeconds, cleanupCron);
    }
}
