package az.fitnest.payment.client.bob;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bank of Baku (SmartVista EPG) Gateway konfiqurasiyası.
 *
 * <p>Bütün xassələr {@code application.yml}-dəki {@code bob:} blokundan oxunur
 * və mühit dəyişənləri ilə üstəyazıla bilər.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "bob")
public class BobProperties {

    /**
     * SmartVista REST API gateway baza URL-i.
     * Məs: https://epg.bankofbaku.com/payment/rest/
     */
    private String gatewayUrl;

    /**
     * SmartVista API istifadəçi adı.
     */
    private String username;

    /**
     * SmartVista API şifrəsi.
     */
    private String password;

    /**
     * Merchant İD (Bank tərəfindən verilir).
     */
    private String merchantId;

    /**
     * Defolt valyuta kodu (ISO 4217, AZN = 944 və ya "AZN").
     */
    private String defaultCurrency = "AZN";

    /**
     * Defolt dil (az, en, ru).
     */
    private String defaultLanguage = "az";

    /**
     * Callback URL (Bankın ödəniş nəticəsini göndərəcəyi backend keçidi).
     */
    private String callbackUrl;

    /**
     * Uğurlu ödənişdən sonra istifadəçinin yönləndiriləcəyi frontend keçidi.
     */
    private String successRedirectUrl;

    /**
     * Uğursuz ödənişdən sonra istifadəçinin yönləndiriləcəyi frontend keçidi.
     */
    private String errorRedirectUrl;

    /**
     * Aktiv taksit ayları (məs: 2,3,6,9,12).
     */
    private String activeInstallmentMonths = "2,3,6,9,12";

    /**
     * Texniki işlər rejimi: true = bütün Bank of Baku endpoint-ləri maintenance mesajı qaytarır.
     */
    private boolean maintenanceMode = true;

    /**
     * Sorğu zamanı HTTP timeout (milli saniyə ilə).
     */
    private int timeoutMs = 10000;
}
