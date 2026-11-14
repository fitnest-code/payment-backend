package az.fitnest.payment.service.bnpl;

import az.fitnest.payment.exception.BnplPaymentException;
import az.fitnest.payment.model.enums.BnplOrderStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stateless rules for the ABB BNPL partner contract.
 *
 * <p>ABB confirms the following (mail exchange, Oct 2026):</p>
 * <ul>
 *   <li>{@code term} is a <b>number</b> — {@code "12 ay"} is rejected with
 *       "The selected credit term is not available".</li>
 *   <li>The available terms are fixed per client/contract (test client: {@code 1,4,12}),
 *       so validation must use the configured list, not the documented union.</li>
 *   <li>{@code phone} must be {@code +994XXXXXXXXX}.</li>
 *   <li>Duplicate submits answer "Order already exists" without an {@code orderId}.</li>
 *   <li>{@link BnplOrderStatus#CLOSED} is just another credit-application status; it must
 *       not downgrade an already {@code SUCCESS} payment.</li>
 * </ul>
 */
public final class BnplContractRules {

    /** Terms mentioned anywhere in the ABB documentation. Not all are enabled per client. */
    public static final Set<Integer> DOCUMENTED_TERMS = Set.of(1, 3, 4, 6, 9, 12, 18, 24);

    /** Azerbaijani mobile numbers: +994 followed by 9 digits (10/5x/6x/7x/9x prefixes). */
    private static final Pattern AZ_MOBILE =
            Pattern.compile("^\\+994(10|[5-7]\\d|9\\d)\\d{7}$");

    private static final Set<String> AMBIGUOUS_ERROR_CODES = Set.of(
            "BNPL_CONNECTION_ERROR",
            "BNPL_TIMEOUT",
            "BNPL_RATE_LIMIT",
            "BNPL_PARSE_ERROR",
            "BNPL_UNKNOWN_SUBMIT_RESULT");

    private BnplContractRules() {
    }

    // ------------------------------------------------------------------ term

    /**
     * ABB expects the raw numeric term. Sending "N ay" yields
     * {@code 400 The selected credit term is not available: N ay}.
     */
    public static String formatTerm(Integer term) {
        if (term == null) {
            throw new BnplPaymentException("BNPL_INVALID_TERM", "Kredit müddəti məcburidir");
        }
        return String.valueOf(term);
    }

    public static boolean isConfiguredTerm(Integer term, List<Integer> configured) {
        if (term == null) {
            return false;
        }
        if (configured == null || configured.isEmpty()) {
            return DOCUMENTED_TERMS.contains(term);
        }
        return configured.contains(term);
    }

    // ----------------------------------------------------------------- phone

    /**
     * Normalises to {@code +994XXXXXXXXX}. Rejects everything else instead of forwarding
     * an invalid value to ABB (which answers "mobile number is not in the correct format").
     */
    public static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new BnplPaymentException("BNPL_INVALID_PHONE", "Telefon nömrəsi məcburidir");
        }
        String digits = phone.replaceAll("[^0-9]", "");
        String candidate;
        if (digits.startsWith("994") && digits.length() == 12) {
            candidate = "+" + digits;
        } else if (digits.startsWith("0") && digits.length() == 10) {
            candidate = "+994" + digits.substring(1);
        } else if (digits.length() == 9) {
            candidate = "+994" + digits;
        } else {
            candidate = null;
        }
        if (candidate == null || !AZ_MOBILE.matcher(candidate).matches()) {
            throw new BnplPaymentException("BNPL_INVALID_PHONE",
                    "Telefon nömrəsi +994XXXXXXXXX formatında olmalıdır");
        }
        return candidate;
    }

    static boolean isValidPhone(String normalized) {
        return normalized != null && AZ_MOBILE.matcher(normalized).matches();
    }

    // ---------------------------------------------------------------- status

    /**
     * Decides which ABB status should be persisted.
     *
     * @return the status to apply, or {@code null} when the update must be ignored
     *         (out-of-order / regressive callback).
     */
    public static BnplOrderStatus resolveStatus(BnplOrderStatus previous, BnplOrderStatus next) {
        if (next == null) {
            return null;
        }
        if (previous == null || previous == next) {
            return next;
        }
        // REVERSED is final for the declaration itself.
        if (previous == BnplOrderStatus.REVERSED) {
            return null;
        }
        if (next == BnplOrderStatus.REVERSED) {
            // Only a completed order can be reversed.
            return previous == BnplOrderStatus.COMPLETED ? next : null;
        }
        if (previous.isTerminal()) {
            // Terminal statuses never regress; CLOSED is a no-op after COMPLETED.
            return null;
        }
        return next;
    }

    /**
     * FitNest {@code Payment.status} for a given ABB status.
     * {@code CLOSED} keeps a previously successful payment as {@code SUCCESS}.
     */
    public static String resolvePaymentStatus(String previousPaymentStatus,
                                              BnplOrderStatus next) {
        if (next == null) {
            return previousPaymentStatus;
        }
        if (next == BnplOrderStatus.CLOSED && "SUCCESS".equalsIgnoreCase(previousPaymentStatus)) {
            return previousPaymentStatus;
        }
        if (next == BnplOrderStatus.CLOSED && "REVERSED".equalsIgnoreCase(previousPaymentStatus)) {
            return previousPaymentStatus;
        }
        return next.toPaymentStatus();
    }

    // --------------------------------------------------------------- errors

    public static boolean isDuplicateSubmit(String detail) {
        return detail != null && detail.toLowerCase(Locale.ROOT).contains("already exists");
    }

    public static boolean isTermUnavailable(String detail) {
        if (detail == null) {
            return false;
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        return lower.contains("term") && (lower.contains("not available") || lower.contains("unavailable"));
    }

    /**
     * True when the submit outcome is unknown: the order may exist at ABB.
     * These must never be persisted as {@code FAILED}.
     */
    public static boolean isAmbiguousSubmitFailure(String errorCode, String detail) {
        if (errorCode == null) {
            return false;
        }
        if (AMBIGUOUS_ERROR_CODES.contains(errorCode)) {
            return true;
        }
        return errorCode.startsWith("BNPL_HTTP_5");
    }

    // --------------------------------------------------------------- reverse

    public enum ReverseOutcome {PARTIAL, COMPLETE, EXCEEDS}

    public static ReverseOutcome reverseOutcome(BigDecimal totalAmount,
                                                BigDecimal alreadyReversed,
                                                BigDecimal requested) {
        BigDecimal total = totalAmount != null ? totalAmount : BigDecimal.ZERO;
        BigDecimal done = alreadyReversed != null ? alreadyReversed : BigDecimal.ZERO;
        BigDecimal add = requested != null ? requested : BigDecimal.ZERO;
        BigDecimal sum = done.add(add);
        int cmp = sum.compareTo(total);
        if (cmp > 0) {
            return ReverseOutcome.EXCEEDS;
        }
        return cmp == 0 ? ReverseOutcome.COMPLETE : ReverseOutcome.PARTIAL;
    }

    public static boolean isWithinReverseWindow(LocalDateTime createdAt, int windowDays) {
        if (createdAt == null) {
            return false;
        }
        return !createdAt.isBefore(LocalDateTime.now().minusDays(windowDays));
    }

    // ------------------------------------------------------------- signature

    /** HMAC-SHA256 over the raw request body; accepts hex, base64 and base64url. */
    public static boolean isValidSignature(String secret, byte[] rawBody, String provided) {
        if (secret == null || secret.isBlank() || provided == null || provided.isBlank() || rawBody == null) {
            return false;
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = mac.doFinal(rawBody);
        } catch (Exception e) {
            return false;
        }

        String candidate = provided.trim();
        if (candidate.regionMatches(true, 0, "sha256=", 0, 7)) {
            candidate = candidate.substring(7);
        }
        for (String encoded : List.of(
                HexFormat.of().formatHex(expected),
                HexFormat.of().formatHex(expected).toUpperCase(Locale.ROOT),
                Base64.getEncoder().encodeToString(expected),
                Base64.getUrlEncoder().withoutPadding().encodeToString(expected))) {
            if (MessageDigest.isEqual(
                    encoded.getBytes(StandardCharsets.US_ASCII),
                    candidate.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    /** Basic Auth check with constant-time comparison. */
    public static boolean isValidBasicAuth(String header, String expectedUser, String expectedPass) {
        if (header == null || expectedUser == null || expectedPass == null) {
            return false;
        }
        if (!header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int idx = decoded.indexOf(':');
            if (idx < 0) {
                return false;
            }
            return MessageDigest.isEqual(
                    decoded.substring(0, idx).getBytes(StandardCharsets.UTF_8),
                    expectedUser.getBytes(StandardCharsets.UTF_8))
                    && MessageDigest.isEqual(
                    decoded.substring(idx + 1).getBytes(StandardCharsets.UTF_8),
                    expectedPass.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** Parses {@code X-ABB-Timestamp} (ISO-8601 or epoch seconds/millis). */
    public static Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.matches("^\\d{13}$")) {
                return Instant.ofEpochMilli(Long.parseLong(value));
            }
            if (value.matches("^\\d{10}$")) {
                return Instant.ofEpochSecond(Long.parseLong(value));
            }
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    public static boolean isFresh(Instant timestamp, long toleranceSeconds) {
        if (timestamp == null) {
            return false;
        }
        long delta = Math.abs(Instant.now().getEpochSecond() - timestamp.getEpochSecond());
        return delta <= toleranceSeconds;
    }

    static LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
