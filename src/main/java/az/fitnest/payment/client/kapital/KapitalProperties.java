package az.fitnest.payment.client.kapital;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Kapital Bank (HPP / Order_SMS) configuration, bound from the {@code kapital:} block in application.yml.
 * Credentials come from Vault/env; nothing secret is defaulted here.
 */
@Data
@Component
@ConfigurationProperties(prefix = "kapital")
public class KapitalProperties {

    /** REST API base, e.g. https://txpgtst.kapitalbank.az/api (no trailing slash needed). */
    private String baseUrl = "https://txpgtst.kapitalbank.az/api";

    /** Basic-auth merchant user. */
    private String username;

    /** Basic-auth merchant password. */
    private String password;

    /**
     * Public URL of /payment/kapital/result; sent as hppRedirectUrl so the bank returns the
     * payer here with ?ID=..&STATUS=.. (STATUS is untrusted — we always re-read GET /order).
     */
    private String callbackUrl;

    private String successRedirectUrl;

    private String errorRedirectUrl;

    private String defaultCurrency = "AZN";

    private String defaultLanguage = "az";

    /** Merchant title shown on the HPP page. */
    private String title = "FitNest";

    private boolean reconcileEnabled = true;

    private long reconcileIntervalMs = 180_000L;

    /** Do not reconcile orders younger than this: the payer may still be on the HPP. */
    private long pendingMinAgeMs = 120_000L;

    /** Orders still Preparing after this long are abandoned and marked FAILED. */
    private long preparingTimeoutMs = 3_600_000L;

    private boolean maintenanceMode = false;

    private int timeoutMs = 15_000;

    /**
     * Comma-separated TAKSIT months Kapital currently allows (e.g. {@code 3,6,12}).
     * Eligible chips for a package are this list filtered to {@code <= package duration}.
     */
    private String activeInstallmentMonths = "3,6,12";

    /** Parsed once when the config string changes — avoid CSV split on every chip request. */
    private transient volatile String parsedActiveSource;
    private transient volatile List<Integer> parsedActiveMonths;

    /**
     * Sorted bank TAKSIT months from {@link #activeInstallmentMonths}; defaults to 3,6,12.
     */
    public List<Integer> resolvedInstallmentMonths() {
        String active = activeInstallmentMonths;
        List<Integer> cached = parsedActiveMonths;
        if (cached != null && java.util.Objects.equals(active, parsedActiveSource)) {
            return cached;
        }
        List<Integer> fallback = List.of(3, 6, 12);
        if (active == null || active.isBlank()) {
            parsedActiveSource = active;
            parsedActiveMonths = fallback;
            return fallback;
        }
        try {
            List<Integer> parsed = Arrays.stream(active.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .filter(m -> m > 0)
                    .distinct()
                    .sorted()
                    .toList();
            List<Integer> resolved = parsed.isEmpty() ? fallback : List.copyOf(parsed);
            parsedActiveSource = active;
            parsedActiveMonths = resolved;
            return resolved;
        } catch (NumberFormatException e) {
            parsedActiveSource = active;
            parsedActiveMonths = fallback;
            return fallback;
        }
    }

    /** AES key (32 bytes after padding) for payments.encrypted_order_password. */
    private String orderPasswordEncryptionKey;
}
