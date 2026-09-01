package az.fitnest.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityBackendClient {

    private final RestTemplate restTemplate;

    @Value("${iam.backend.url:http://identity-backend:8080}")
    private String identityBackendUrl;

    public boolean isWelcomeBonusReceived(Long userId) {
        if (userId == null) {
            return false;
        }
        try {
            WelcomeBonusStatusResponse response = restTemplate.getForObject(
                    identityBackendUrl + "/api/v1/internal/users/welcome-bonus/" + userId + "/status",
                    WelcomeBonusStatusResponse.class);
            return response != null && response.isReceived();
        } catch (Exception e) {
            log.warn("Failed to fetch welcome bonus status for userId={}: {}", userId, e.getMessage());
            return false;
        }
    }

    public void markWelcomeBonusReceived(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            restTemplate.exchange(
                    identityBackendUrl + "/api/v1/internal/users/welcome-bonus/" + userId + "/received",
                    HttpMethod.PUT,
                    HttpEntity.EMPTY,
                    Void.class);
        } catch (Exception e) {
            log.error("Failed to mark welcome bonus received for userId={}: {}", userId, e.getMessage());
            throw e;
        }
    }

    public List<Long> findPendingWelcomeBonusUserIds() {
        try {
            Map<String, List<Long>> response = restTemplate.exchange(
                    identityBackendUrl + "/api/v1/internal/users/welcome-bonus/pending-ids",
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<Map<String, List<Long>>>() {})
                    .getBody();
            if (response == null || response.get("userIds") == null) {
                return Collections.emptyList();
            }
            return response.get("userIds");
        } catch (Exception e) {
            log.error("Failed to fetch pending welcome bonus user ids: {}", e.getMessage());
            throw e;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WelcomeBonusStatusResponse {
        @JsonProperty("userId")
        private Long userId;

        @JsonProperty("received")
        private boolean received;
    }
}
