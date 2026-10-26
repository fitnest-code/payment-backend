package az.fitnest.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Internal, cluster-only access to the user directory (identity data lives in user-backend).
 * Reached directly (never through the gateway, which blocks /internal/).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserBackendClient {

    private static final ParameterizedTypeReference<List<UserSummary>> USER_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;

    @Value("${user.backend.url:http://user-backend:8080}")
    private String userBackendUrl;

    /**
     * Resolve identities for a page of userIds in a single round trip.
     */
    public Map<Long, UserSummary> getUsers(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            List<UserSummary> users = restTemplate.exchange(
                    userBackendUrl + "/api/v1/internal/users/batch",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("userIds", ids)),
                    USER_LIST_TYPE).getBody();
            if (users == null || users.isEmpty()) {
                return Map.of();
            }
            return users.stream()
                    .filter(user -> user.userId() != null)
                    .collect(Collectors.toMap(UserSummary::userId, Function.identity(), (left, right) -> left));
        } catch (Exception e) {
            log.warn("User directory lookup failed for {} id(s): {}", ids.size(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * Free-text user search (id, name, email, mobile). Returns matching userIds.
     */
    public List<Long> searchUserIds(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            SearchResponse response = restTemplate.getForObject(
                    userBackendUrl + "/api/v1/internal/users/search?q={q}&limit={limit}",
                    SearchResponse.class,
                    query.trim(),
                    Math.max(1, limit));
            return response != null && response.userIds() != null ? response.userIds() : List.of();
        } catch (Exception e) {
            log.warn("User directory search failed for '{}': {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserSummary(
            @JsonProperty("userId") Long userId,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("mobile") String mobile,
            @JsonProperty("email") String email) {

        public String displayName() {
            String name = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
            return name.isEmpty() ? null : name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResponse(@JsonProperty("userIds") List<Long> userIds) {
    }
}
