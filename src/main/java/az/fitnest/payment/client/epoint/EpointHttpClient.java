package az.fitnest.payment.client.epoint;

import az.fitnest.payment.dto.epoint.EpointResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.decorators.Decorators;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class EpointHttpClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EpointHttpClient.class);

    private final RestTemplate restTemplate;
    private final EpointSigner signer;
    private final EpointProperties properties;

    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;

    public EpointHttpClient(RestTemplate restTemplate, EpointSigner signer, EpointProperties properties) {
        this.restTemplate = restTemplate;
        this.signer = signer;
        this.properties = properties;
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(2))
                .retryExceptions(Exception.class)
                .build();
        retry = RetryRegistry.of(retryConfig).retry("epoint-http");
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .slidingWindowSize(10)
                .build();
        circuitBreaker = CircuitBreakerRegistry.of(cbConfig).circuitBreaker("epoint-http");
        timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5))
                .build());
    }

    public EpointResponse postSigned(String endpoint, Object payload) {
        String data = signer.encodeData(payload);
        String signature = signer.sign(data, properties.getPrivateKey());

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("data", data);
        body.add("signature", signature);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        String url = properties.getBaseUrl() + endpoint;

        Supplier<CompletableFuture<EpointResponse>> futureSupplier = () -> CompletableFuture.supplyAsync(() -> restTemplate.postForObject(url, request, EpointResponse.class));
        java.util.concurrent.Callable<EpointResponse> timeLimitedCallable = io.github.resilience4j.timelimiter.TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);
        java.util.concurrent.Callable<EpointResponse> decoratedCallable = io.github.resilience4j.decorators.Decorators.ofCallable(timeLimitedCallable)
                .withCircuitBreaker(circuitBreaker)
                .withRetry(retry)
                .decorate();
        try {
            return decoratedCallable.call();
        } catch (Exception e) {
            log.error("Error in postSigned", e);
            throw new RuntimeException(e);
        }
    }

    public EpointResponse postDirect(String endpoint, Object payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Object> request = new HttpEntity<>(payload, headers);

        String url = properties.getBaseUrl() + endpoint;

        Supplier<CompletableFuture<EpointResponse>> futureSupplier = () -> CompletableFuture.supplyAsync(() -> restTemplate.postForObject(url, request, EpointResponse.class));
        java.util.concurrent.Callable<EpointResponse> timeLimitedCallable = io.github.resilience4j.timelimiter.TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);
        java.util.concurrent.Callable<EpointResponse> decoratedCallable = io.github.resilience4j.decorators.Decorators.ofCallable(timeLimitedCallable)
                .withCircuitBreaker(circuitBreaker)
                .withRetry(retry)
                .decorate();
        try {
            return decoratedCallable.call();
        } catch (Exception e) {
            log.error("Error in postDirect", e);
            throw new RuntimeException(e);
        }
    }

    public EpointResponse get(String endpoint) {
        String url = properties.getBaseUrl() + endpoint;
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<Void> request = new HttpEntity<>(headers);

        Supplier<CompletableFuture<EpointResponse>> futureSupplier = () -> CompletableFuture.supplyAsync(() -> restTemplate.getForObject(url, EpointResponse.class));
        java.util.concurrent.Callable<EpointResponse> timeLimitedCallable = io.github.resilience4j.timelimiter.TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);
        java.util.concurrent.Callable<EpointResponse> decoratedCallable = io.github.resilience4j.decorators.Decorators.ofCallable(timeLimitedCallable)
                .withCircuitBreaker(circuitBreaker)
                .withRetry(retry)
                .decorate();
        try {
            return decoratedCallable.call();
        } catch (Exception e) {
            log.error("Error in get", e);
            throw new RuntimeException(e);
        }
    }
}
