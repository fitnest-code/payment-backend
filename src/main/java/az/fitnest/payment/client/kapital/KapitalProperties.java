package az.fitnest.payment.client.kapital;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /** AES key (32 bytes after padding) for payments.encrypted_order_password. */
    private String orderPasswordEncryptionKey;
}
