package az.fitnest.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Configuration
public class ApplicationWarmupConfig {

    private final DataSource dataSource;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.warmup.enabled:true}")
    private boolean warmupEnabled;

    @Value("${app.warmup.db:true}")
    private boolean warmupDb;

    public ApplicationWarmupConfig(DataSource dataSource, RedisTemplate<String, Object> redisTemplate) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmupApplication() {
        if (!warmupEnabled) {
            return;
        }

        if (warmupDb) {
            warmupDatabase();
        }

        warmupRedis();
        warmupJit();
    }

    private void warmupDatabase() {
        try {
            long start = System.currentTimeMillis();
            for (int i = 0; i < 3; i++) {
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("SELECT 1");
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        rs.getInt(1);
                    }
                }
            }
        } catch (Exception e) {
        }
    }

    private void warmupRedis() {
        try {
            long start = System.currentTimeMillis();
            redisTemplate.hasKey("__warmup__");
        } catch (Exception e) {
        }
    }

    private void warmupJit() {
        try {
            long start = System.currentTimeMillis();

            String test = "warmup-test-string";
            test.toLowerCase();
            test.toUpperCase();
            test.split("-");

            java.util.List<String> list = new java.util.ArrayList<>();
            list.add("test");
            list.stream().filter(s -> s != null).count();

            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("key", "value");
            map.get("key");

        } catch (Exception e) {
        }
    }
}
