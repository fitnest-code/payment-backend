package az.fitnest.payment.client.epoint;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "epoint")
public class EpointProperties {

    private String publicKey;
    private String privateKey;
    private String baseUrl = "https://epoint.az/api/1";
    private String resultUrl;
    private String successRedirectUrl;
    private String errorRedirectUrl;

    /**
     * Bank error kodu ilə dinamik error redirect URL qaytarır.
     * Nümunə: https://fitnest.az/payment/error/116
     *
     * Əgər kod null, blank və ya "000" (uğurlu) olarsa — base URL qaytarılır.
     * Bu metod yalnız cavab birbaşa gələn flowlarda (execute-pay, with-card)
     * istifadə olunur. Redirect-based flowlarda kod öncədən bilinmir.
     */
    public String getErrorRedirectUrlWithCode(String bankCode) {
        if (bankCode == null || bankCode.isBlank() || "000".equals(bankCode)) {
            return errorRedirectUrl;
        }
        // errorRedirectUrl: https://fitnest.az/payment/error
        // nəticə:          https://fitnest.az/payment/error/116
        return errorRedirectUrl + "/" + bankCode;
    }
}