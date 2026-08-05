package az.fitnest.payment.service;

import az.fitnest.payment.client.UserGrpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves display names from user-backend via gRPC (identity/user data stays there).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDisplayNameResolver {

    private final UserGrpcClient userGrpcClient;

    public String resolveFullName(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            var userResp = userGrpcClient.getUser(userId);
            if (userResp != null) {
                String first = userResp.getFirstName();
                String last = userResp.getLastName();
                String full = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
                return full.isEmpty() ? null : full;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve user name for userId={}: {}", userId, e.getMessage());
        }
        return null;
    }

    public Map<Long, String> buildNameCache(List<Long> userIds) {
        Map<Long, String> cache = new HashMap<>();
        Set<Long> unique = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        for (Long uid : unique) {
            String name = resolveFullName(uid);
            if (name != null) {
                cache.put(uid, name);
            }
        }
        return cache;
    }
}
