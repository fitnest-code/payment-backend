package az.fitnest.payment.client.abb;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ABB (Azericard) E-Commerce Gateway konfiqurasiyası.
 *
 * <p>Bütün xassələr {@code application.yml}-dəki {@code abb:} blokundan oxunur
 * və mühit dəyişənləri ilə üstəyazıla bilər.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "abb")
public class AbbProperties {

    /**
     * Azericard E-Commerce Gateway URL-i.
     * Sandbox: https://testmpi.3dsecure.az/cgi-bin/cgi_link
     * Production: https://mpi.3dsecure.az/cgi-bin/cgi_link
     */
    private String gatewayUrl;

    /**
     * Bank tərəfindən verilmiş Merchant Terminal ID (8 simvol).
     */
    private String terminalId;

    /**
     * Bank tərəfindən verilmiş Merchant adı.
     */
    private String merchantName;

    /**
     * Merchant-ın veb saytı URL-i.
     */
    private String merchantUrl;

    /**
     * Merchant-ın e-mail ünvanı.
     */
    private String merchantEmail;

    /**
     * Merchant-ın ölkə kodu (ISO 3166-1 alpha-2). Məs: AZ
     */
    private String countryCode;

    /**
     * Merchant-ın UTC/GMT vaxt zonası. Məs: +4
     */
    private String merchantGmt;

    /**
     * RSA SHA-256 imzalama üçün merchant-ın şəxsi açarı (PEM formatı, Base64 kodlaşdırılmış).
     * Başlıq/altlıq satırları olmadan yalnız Base64 dəyərini daxil edin.
     */
    private String privateKey;

    /**
     * Callback (BACKREF) imzasını yoxlamaq üçün Azericard-ın açıq açarı (PEM formatı).
     * Bank tərəfindən AZERICARDpublic.pem faylı kimi təqdim edilir.
     */
    private String publicKey;

    /**
     * ABB callback-i üçün ödəniş nəticəsinin POST ediləcəyi URL (BACKREF).
     * Məs: https://api.fitnest.az/payment/abb/callback
     */
    private String callbackUrl;

    /**
     * Uğurlu ödənişdən sonra istifadəçinin yönləndiriləcəyi URL.
     */
    private String successRedirectUrl;

    /**
     * Uğursuz ödənişdən sonra istifadəçinin yönləndiriləcəyi URL.
     */
    private String errorRedirectUrl;

    /**
     * Valyuta kodu (ISO 4217). Default: AZN (944)
     */
    private String defaultCurrency = "AZN";

    /**
     * Dil kodu. Default: AZ
     */
    private String defaultLanguage = "AZ";

    /**
     * Dəstəklənən taksit ayları siyahısı.
     * Default olaraq test terminalın aktiv ayları: 2, 3, 6, 9, 12, 18, 24, 27, 30
     */
    private java.util.List<Integer> activeInstallmentMonths = java.util.List.of(2, 3, 6, 9, 12, 18, 24, 27, 30);
}
