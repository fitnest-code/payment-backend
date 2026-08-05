package az.fitnest.payment.util;

/**
 * Encodes/decodes packageId/optionId in payment description (and Epoint otherAttr strings).
 * Subscription ownership stays in order-backend; payment only carries these refs.
 */
public final class PaymentPackageRef {

    private PaymentPackageRef() {
    }

    public record Ref(Long packageId, Long optionId) {
        public boolean isComplete() {
            return packageId != null && optionId != null;
        }
    }

    public static String encode(Long packageId, Long optionId) {
        return "packageId:" + packageId + ",optionId:" + optionId;
    }

    /**
     * Same rules historically used by BOB/ABB/Epoint description builders.
     */
    public static String appendToDescription(String requestDescription, Long packageId, Long optionId) {
        String packageDesc = encode(packageId, optionId);
        if (requestDescription == null || requestDescription.isBlank()) {
            return packageDesc;
        }
        if (!requestDescription.contains("packageId:")) {
            return requestDescription + "," + packageDesc;
        }
        return requestDescription;
    }

    public static Ref parse(String text) {
        if (text == null || text.isBlank() || !text.contains("packageId:")) {
            return new Ref(null, null);
        }
        Long packageId = null;
        Long optionId = null;
        for (String part : text.split(",")) {
            part = part.trim();
            if (part.startsWith("packageId:")) {
                packageId = parseLongSafe(part.substring("packageId:".length()).trim());
            } else if (part.startsWith("optionId:")) {
                optionId = parseLongSafe(part.substring("optionId:".length()).trim());
            }
        }
        return new Ref(packageId, optionId);
    }

    public static Ref parseWithFallback(String primary, String fallback) {
        Ref fromPrimary = parse(primary);
        if (fromPrimary.isComplete()) {
            return fromPrimary;
        }
        Ref fromFallback = parse(fallback);
        return new Ref(
                fromPrimary.packageId() != null ? fromPrimary.packageId() : fromFallback.packageId(),
                fromPrimary.optionId() != null ? fromPrimary.optionId() : fromFallback.optionId());
    }

    private static Long parseLongSafe(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }
}
