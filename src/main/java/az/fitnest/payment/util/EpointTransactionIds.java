package az.fitnest.payment.util;

import az.fitnest.payment.dto.epoint.EpointResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Epoint transaction ids: {@code te} (redirect checkout) and {@code tw} (widget / Apple Pay).
 * Live ids pad the numeric token to 9 digits ({@code te022240154}). Widget URLs omit the prefix
 * and leading zeros ({@code /widget/22240181}), so callers must try equivalent forms when
 * matching callbacks or querying {@code /get-status}.
 */
public final class EpointTransactionIds {

    private EpointTransactionIds() {
    }

    /** Canonical widget id stored on create: {@code tw} + 9-digit padded token. */
    public static String fromWidgetToken(String token) {
        String digits = digitsOf(token);
        if (digits.isEmpty()) {
            return token == null ? null : token.trim();
        }
        return "tw" + pad(digits, 9);
    }

    /**
     * When Epoint reports the same numeric token with a different prefix ({@code te} vs {@code tw})
     * or padding, keep the prefix we already stored and canonicalize to 9 digits. Otherwise the
     * client-held widget id ({@code tw022241588}) no longer matches history lookup after callback.
     */
    public static String preferredStoredId(String existing, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return existing;
        }
        String inc = incoming.trim();
        if (existing == null || existing.isBlank()) {
            return inc;
        }
        if (existing.equals(inc)) {
            return existing;
        }
        String existingDigits = digitsOf(existing);
        String incomingDigits = digitsOf(inc);
        if (existingDigits.isEmpty() || incomingDigits.isEmpty()) {
            return inc;
        }
        try {
            if (Long.parseLong(existingDigits) != Long.parseLong(incomingDigits)) {
                return inc;
            }
        } catch (NumberFormatException e) {
            return inc;
        }
        String prefix = prefixOf(existing);
        if (prefix.isEmpty()) {
            prefix = prefixOf(inc);
        }
        if (prefix.isEmpty()) {
            return inc;
        }
        return prefix + pad(incomingDigits, 9);
    }

    static String prefixOf(String raw) {
        if (raw == null || raw.length() < 2) {
            return "";
        }
        String head = raw.substring(0, 2).toLowerCase(Locale.ROOT);
        if ("tw".equals(head) || "te".equals(head)) {
            return head;
        }
        return "";
    }

    public static boolean sameNumericToken(String left, String right) {
        String a = digitsOf(left);
        String b = digitsOf(right);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        try {
            return Long.parseLong(a) == Long.parseLong(b);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean isWidgetLike(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String t = type.toUpperCase(Locale.ROOT);
        return "WIDGET_PAYMENT".equals(t)
                || "APPLE_PAY".equals(t)
                || "GOOGLE_PAY".equals(t);
    }

    /**
     * Padding variants of the same prefix are the same Epoint payment. {@code tw}↔{@code te} is
     * only an alias when the row is a widget/Apple Pay/Google Pay payment — a card {@code te}
     * checkout with the same digits is a different payment and must not be returned.
     */
    public static boolean isAliasCompatible(String requestedId, String storedId, String paymentType) {
        String requestedPrefix = prefixOf(requestedId);
        String storedPrefix = prefixOf(storedId);
        if (requestedPrefix.isEmpty() || storedPrefix.isEmpty() || requestedPrefix.equals(storedPrefix)) {
            return true;
        }
        return isWidgetLike(paymentType);
    }

    /**
     * Picks at most one row for a history lookup. Returns empty when two different payments share
     * the same numeric token (for example card {@code te} and widget {@code tw}) and neither is an
     * unambiguous alias of the requested id.
     */
    public static <T> Optional<T> selectAliasMatch(
            String requestedId,
            List<T> matches,
            Long requiredUserId,
            Function<T, String> transactionId,
            Function<T, String> type,
            Function<T, Long> userId) {
        if (requestedId == null || requestedId.isBlank() || matches == null || matches.isEmpty()) {
            return Optional.empty();
        }
        String requested = requestedId.trim();
        List<T> pool = new ArrayList<>();
        for (T match : matches) {
            if (match == null) {
                continue;
            }
            String stored = transactionId.apply(match);
            if (stored == null || !sameNumericToken(requested, stored)) {
                continue;
            }
            if (requiredUserId != null && !requiredUserId.equals(userId.apply(match))) {
                continue;
            }
            if (!isAliasCompatible(requested, stored, type.apply(match))) {
                continue;
            }
            pool.add(match);
        }
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        if (pool.size() == 1) {
            return Optional.of(pool.get(0));
        }

        String requestedPrefix = prefixOf(requested);
        List<T> samePrefix = new ArrayList<>();
        for (T match : pool) {
            if (requestedPrefix.equals(prefixOf(transactionId.apply(match)))) {
                samePrefix.add(match);
            }
        }
        List<T> ranked = !samePrefix.isEmpty() ? samePrefix : pool;
        if (ranked.size() == 1) {
            return Optional.of(ranked.get(0));
        }

        String canonical = canonicalForm(requested);
        if (canonical != null) {
            List<T> canon = new ArrayList<>();
            for (T match : ranked) {
                if (canonical.equals(transactionId.apply(match))) {
                    canon.add(match);
                }
            }
            if (canon.size() == 1) {
                return Optional.of(canon.get(0));
            }
        }
        return Optional.empty();
    }

    static String canonicalForm(String requested) {
        String prefix = prefixOf(requested);
        String digits = digitsOf(requested);
        if (prefix.isEmpty() || digits.isEmpty()) {
            return null;
        }
        try {
            return prefix + pad(digits, 9);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Lookup/query variants for a stored or callback transaction id, most likely first.
     * Includes 9- and 10-digit {@code tw}/{@code te} forms so historic 10-digit widget rows still match.
     */
    public static List<String> lookupCandidates(String... ids) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String trimmed = id.trim();
            out.add(trimmed);
            if (trimmed.indexOf('-') >= 0) {
                continue;
            }
            String digits = digitsOf(trimmed);
            if (digits.isEmpty() || digits.length() > 12) {
                continue;
            }
            String pad9 = pad(digits, 9);
            String pad10 = pad(digits, 10);
            out.add("tw" + pad9);
            out.add("tw" + pad10);
            out.add("te" + pad9);
            out.add("te" + pad10);
            out.add("tw" + stripLeadingZeros(digits));
            out.add("te" + stripLeadingZeros(digits));
        }
        return new ArrayList<>(out);
    }

    /** Epoint recognized this id: do not try another padding variant. */
    public static boolean isDefinitiveStatus(EpointResponse response) {
        if (response == null || response.status() == null || response.status().isBlank()) {
            return false;
        }
        String status = response.status().toLowerCase(Locale.ROOT);
        if ("success".equals(status) || "returned".equals(status) || "new".equals(status)) {
            return true;
        }
        if ("error".equals(status) || "failed".equals(status)) {
            return hasBankAttempt(response);
        }
        return false;
    }

    public static boolean hasBankAttempt(EpointResponse response) {
        if (response == null) {
            return false;
        }
        return (response.cardMask() != null && !response.cardMask().isBlank())
                || (response.bankTransaction() != null && !response.bankTransaction().isBlank())
                || (response.bankResponse() != null && !response.bankResponse().isBlank())
                || (response.code() != null && !response.code().isBlank()
                && !"500".equals(response.code()) && !"ERROR".equalsIgnoreCase(response.code()));
    }

    static String digitsOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    private static String pad(String digits, int width) {
        long n = Long.parseLong(digits);
        return String.format("%0" + width + "d", n);
    }

    private static String stripLeadingZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }
}
