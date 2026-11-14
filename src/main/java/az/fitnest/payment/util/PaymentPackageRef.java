package az.fitnest.payment.util;

/**
 * Encodes/decodes packageId/optionId (and optional pricing campaignId) in payment description
 * and Epoint otherAttr strings. Subscription ownership stays in order-backend; payment only
 * carries these refs.
 */
public final class PaymentPackageRef {

    private PaymentPackageRef() {
    }

    public record Ref(Long packageId, Long optionId, String campaignId) {
        public boolean isComplete() {
            return packageId != null && optionId != null;
        }
    }

    public static String encode(Long packageId, Long optionId) {
        return encode(packageId, optionId, null);
    }

    public static String encode(Long packageId, Long optionId, String campaignId) {
        String base = "packageId:" + packageId + ",optionId:" + optionId;
        if (campaignId != null && !campaignId.isBlank()) {
            return base + ",campaignId:" + campaignId.trim();
        }
        return base;
    }

    /**
     * Same rules historically used by BOB/ABB/Epoint description builders.
     */
    public static String appendToDescription(String requestDescription, Long packageId, Long optionId) {
        return appendToDescription(requestDescription, packageId, optionId, null);
    }

    public static String appendToDescription(String requestDescription, Long packageId, Long optionId,
                                             String campaignId) {
        String packageDesc = encode(packageId, optionId, campaignId);
        if (requestDescription == null || requestDescription.isBlank()) {
            return packageDesc;
        }
        if (!requestDescription.contains("packageId:")) {
            return requestDescription + "," + packageDesc;
        }
        if (campaignId != null && !campaignId.isBlank() && !requestDescription.contains("campaignId:")) {
            return requestDescription + ",campaignId:" + campaignId.trim();
        }
        return requestDescription;
    }

    /**
     * Merges package ref (and campaignId) from description and/or Epoint other_attr onto the stored description.
     */
    public static String ensurePackageRefOnDescription(String description, String fallbackAttr) {
        Ref ref = parseWithFallback(description, fallbackAttr);
        if (description == null || description.isBlank()) {
            return ref.isComplete() ? encode(ref.packageId(), ref.optionId(), ref.campaignId()) : description;
        }
        if (!description.contains("packageId:") && ref.isComplete()) {
            return description + "," + encode(ref.packageId(), ref.optionId(), ref.campaignId());
        }
        if (ref.campaignId() != null && !ref.campaignId().isBlank() && !description.contains("campaignId:")) {
            return description + ",campaignId:" + ref.campaignId();
        }
        return description;
    }

    public static Ref parse(String text) {
        if (text == null || text.isBlank() || !text.contains("packageId:")) {
            return new Ref(null, null, null);
        }
        Long packageId = null;
        Long optionId = null;
        String campaignId = null;
        for (String part : text.split(",")) {
            part = part.trim();
            if (part.startsWith("packageId:")) {
                packageId = parseLongSafe(part.substring("packageId:".length()).trim());
            } else if (part.startsWith("optionId:")) {
                optionId = parseLongSafe(part.substring("optionId:".length()).trim());
            } else if (part.startsWith("campaignId:")) {
                String raw = part.substring("campaignId:".length()).trim();
                if (!raw.isEmpty()) {
                    campaignId = raw;
                }
            }
        }
        return new Ref(packageId, optionId, campaignId);
    }

    public static Ref parseWithFallback(String primary, String fallback) {
        Ref fromPrimary = parse(primary);
        if (fromPrimary.isComplete()) {
            if (fromPrimary.campaignId() == null) {
                Ref fromFallback = parse(fallback);
                if (fromFallback.campaignId() != null) {
                    return new Ref(fromPrimary.packageId(), fromPrimary.optionId(), fromFallback.campaignId());
                }
            }
            return fromPrimary;
        }
        Ref fromFallback = parse(fallback);
        return new Ref(
                fromPrimary.packageId() != null ? fromPrimary.packageId() : fromFallback.packageId(),
                fromPrimary.optionId() != null ? fromPrimary.optionId() : fromFallback.optionId(),
                fromPrimary.campaignId() != null ? fromPrimary.campaignId() : fromFallback.campaignId());
    }

    private static Long parseLongSafe(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }
}
