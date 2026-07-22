package az.fitnest.payment.config;

import az.fitnest.payment.client.epoint.EpointHttpClient;
import az.fitnest.payment.client.epoint.EpointSigner;
import az.fitnest.payment.client.epoint.EpointProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;

import java.time.Duration;

@Configuration
public class EpointHttpClientConfig {
    @Bean
    public EpointHttpClient epointHttpClient(RestTemplate restTemplate, EpointSigner signer, EpointProperties properties) {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(200))
                .retryExceptions(Exception.class)
                .build();
        var retry = RetryRegistry.of(retryConfig).retry("epoint-http");

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .slidingWindowSize(10)
                .build();
        var circuitBreaker = CircuitBreakerRegistry.of(cbConfig).circuitBreaker("epoint-http");

        var timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(15))
                .build());

        return new EpointHttpClient(restTemplate, signer, properties, retry, circuitBreaker, timeLimiter);
    }
}
