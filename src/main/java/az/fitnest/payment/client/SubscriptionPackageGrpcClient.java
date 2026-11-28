package az.fitnest.payment.client;

import az.fitnest.order.grpc.CheckOptionInPackageExistsRequest;
import az.fitnest.order.grpc.CheckOptionInPackageExistsResponse;
import az.fitnest.order.grpc.SubscriptionPackageServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SubscriptionPackageGrpcClient {
    @GrpcClient("order-backend")
    private SubscriptionPackageServiceGrpc.SubscriptionPackageServiceBlockingStub stub;

    private final Map<String, CacheEntry<OptionPriceCurrency>> optionCache = new ConcurrentHashMap<>();
    /** Catalog duration only (not campaign/amount) — used by light chip APIs. */
    private final Map<String, CacheEntry<Integer>> durationCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<Boolean>> existsCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    private static class CacheEntry<T> {
        final T value;
        final long expiry;

        CacheEntry(T value, long expiry) {
            this.value = value;
            this.expiry = expiry;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiry;
        }
    }

    public boolean checkOptionInPackageExists(Long packageId, Long optionId) {
        if (packageId == null || optionId == null) return false;
        String key = packageId + ":" + optionId;
        CacheEntry<Boolean> entry = existsCache.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry.value;
        }
        try {
            CheckOptionInPackageExistsRequest request = CheckOptionInPackageExistsRequest.newBuilder()
                    .setPackageId(packageId)
                    .setOptionId(optionId)
                    .build();
            CheckOptionInPackageExistsResponse response = stub.checkOptionInPackageExists(request);
            boolean exists = response.getExists();
            existsCache.put(key, new CacheEntry<>(exists, System.currentTimeMillis() + CACHE_TTL_MS));
            return exists;
        } catch (Exception e) {
            if (entry != null) return entry.value;
            throw e;
        }
    }

    /**
     * Catalog option duration only (one gRPC hop, 5m cache). Prefer this over
     * {@link #getOptionPriceCurrency} when amount/currency are unused.
     */
    public int getOptionDurationMonths(Long packageId, Long optionId) {
        if (packageId == null || optionId == null) {
            throw new IllegalArgumentException("PackageId and OptionId must be provided");
        }
        String key = packageId + ":" + optionId;
        CacheEntry<Integer> entry = durationCache.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry.value;
        }
        try {
            az.fitnest.order.grpc.GetOptionDetailsResponse response = stub.getOptionDetails(
                    az.fitnest.order.grpc.GetOptionDetailsRequest.newBuilder()
                            .setPackageId(packageId)
                            .setOptionId(optionId)
                            .build());
            int duration = response.getDurationMonths();
            durationCache.put(key, new CacheEntry<>(duration, System.currentTimeMillis() + CACHE_TTL_MS));
            return duration;
        } catch (Exception e) {
            if (entry != null) return entry.value;
            throw e;
        }
    }

    /** Duration / existence lookups that do not need per-user campaign eligibility. */
    public OptionPriceCurrency getOptionPriceCurrency(Long packageId, Long optionId) {
        return getOptionPriceCurrency(packageId, optionId, null);
    }

    /**
     * Resolves charge amount for a package option. When {@code userId} is set, order-backend applies
     * Black Friday campaign eligibility the same way as v3 checkout (gym legacy init path).
     */
    public OptionPriceCurrency getOptionPriceCurrency(Long packageId, Long optionId, Long userId) {
        if (packageId == null || optionId == null) {
            throw new IllegalArgumentException("PackageId and OptionId must be provided");
        }
        // Cache key includes userId: campaign eligibility (redeemed / Bakcell) differs per user.
        String key = packageId + ":" + optionId + ":" + (userId == null ? "anon" : userId);
        CacheEntry<OptionPriceCurrency> entry = optionCache.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry.value;
        }
        try {
            az.fitnest.order.grpc.GetOptionDetailsRequest.Builder request =
                    az.fitnest.order.grpc.GetOptionDetailsRequest.newBuilder()
                            .setPackageId(packageId)
                            .setOptionId(optionId);
            if (userId != null && userId > 0) {
                request.setUserId(userId);
            }
            az.fitnest.order.grpc.GetOptionDetailsResponse response = stub.getOptionDetails(request.build());
            OptionPriceCurrency result = new OptionPriceCurrency(response.getAmount(), response.getCurrency(), response.getDurationMonths());
            optionCache.put(key, new CacheEntry<>(result, System.currentTimeMillis() + CACHE_TTL_MS));
            return result;
        } catch (Exception e) {
            if (entry != null) return entry.value;
            throw e;
        }
    }

    public java.util.List<az.fitnest.order.grpc.PackageNameInfo> getPackageNamesByIds(java.util.List<Long> packageIds) {
        az.fitnest.order.grpc.GetPackageNamesByIdsRequest request = az.fitnest.order.grpc.GetPackageNamesByIdsRequest.newBuilder()
                .addAllPackageIds(packageIds)
                .build();
        az.fitnest.order.grpc.GetPackageNamesByIdsResponse response = stub.getPackageNamesByIds(request);
        return response.getPackagesList();
    }

    public static class OptionPriceCurrency {
        public final double amount;
        public final String currency;
        public final int durationMonths;
        public OptionPriceCurrency(double amount, String currency, int durationMonths) {
            this.amount = amount;
            this.currency = currency;
            this.durationMonths = durationMonths;
        }
    }
}
