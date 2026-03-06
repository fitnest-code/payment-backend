package az.fitnest.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration properties for idempotency key management.
 * Configurable via application.yml or environment variables.
 *
 * Example application.yml:
 * payment:
 *   idempotency:
 *     ttl-hours: 24
 *     cleanup-cron: "0 0 * * * *"
 *     max-entries: 100000
 *     redis-enabled: true
 */
@Configuration
@ConfigurationProperties(prefix = "payment.idempotency")
@Getter
@Setter
public class IdempotencyConfig {

    /**
     * Time To Live for idempotency keys in hours.
     * Default: 24 hours
     *
     * Recommendation:
     * - 24 hours: Standard for most payment systems (Stripe, Square use this)
     * - 48 hours: For higher-risk transactions
     * - 1 hour: For high-frequency payment scenarios
     */
    private long ttlHours = 24;

    /**
     * Cron expression for cleanup scheduled task.
     * Default: Every hour at minute 0 (hourly cleanup)
     * Format: second minute hour day month weekday
     * Examples:
     * - Hourly (0 0 ... ... ... ...)
     * - Every 30 minutes (0 [slash]30 ... ... ... ...)
     * - Daily at 2am (0 0 2 ... ... ...)
     * - Daily at midnight (0 0 0 ... ... ...)
     */
    private String cleanupCron = "0 0 * * * *";

    /**
     * Maximum number of idempotency keys to keep in database.
     * When exceeded, cleanup happens more aggressively.
     * Default: 100,000 records
     *
     * Recommendations:
     * - 100,000 (Small/medium systems: 100-1000 transactions/day)
     * - 500,000 (Medium/large systems: 1000-10,000 transactions/day)
     * - 1,000,000 (Large systems: 10,000+ transactions/day)
     */
    private long maxEntries = 100_000;

    /**
     * Enable Redis caching for idempotency keys.
     * If false, only database is used.
     * Default: true
     *
     * Rationale:
     * - true: Better performance, reduces database load
     * - false: Simpler setup, no Redis dependency
     */
    private boolean redisEnabled = true;

    /**
     * Redis TTL in seconds (independent of database TTL).
     * Should be equal to or less than ttlHours.
     * Default: 86400 seconds (24 hours)
     */
    private long redisTtlSeconds = 86400;

    /**
     * Enable cleanup logging for monitoring.
     * Default: true
     */
    private boolean cleanupLoggingEnabled = true;

    /**
     * Log detailed information about cache hits/misses.
     * Default: false (set to true for debugging)
     */
    private boolean detailedLoggingEnabled = false;

    /**
     * Get TTL in seconds (for convenience)
     */
    public long getTtlSeconds() {
        return ttlHours * 3600;
    }

    /**
     * Get max age for cleanup (in seconds)
     */
    public long getMaxAgeSeconds() {
        return getTtlSeconds();
    }
}



