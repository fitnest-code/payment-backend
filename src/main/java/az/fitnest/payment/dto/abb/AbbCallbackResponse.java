package az.fitnest.payment.dto.abb;

import lombok.Builder;
import lombok.Data;

/**
 * ABB (Azericard) E-Commerce Gateway-dən callback/redirect zamanı
 * gəlir olan cavab modeli.
 *
 * <p>Azericard bu parametrləri HTTP POST vasitəsilə {@code BACKREF} URL-inə göndərir.
 * Bütün dəyərlər {@code application/x-www-form-urlencoded} formatında gəlir.</p>
 *
 * <h3>Cavab parametrləri (spec §2 – Cavab formatı)</h3>
 * <ul>
 *   <li>{@code ACTION=0} → Uğurlu tranzaksiya</li>
 *   <li>{@code ACTION=2} → Tranzaksiya rədd edildi</li>
 *   <li>{@code RC=00} → İcazə verildi (ISO-8583 Field 39)</li>
 * </ul>
 *
 * <h3>P_SIGN yoxlama sahə sırası (spec §Geri çağırış)</h3>
 * {@code AMOUNT → TERMINAL → APPROVAL → RRN → INT_REF}
 */
@Data
@Builder
public class AbbCallbackResponse {

    // ── Gateway nəticə sahələri ────────────────────────────────────────────

    /** Bank tərəfindən verilmiş Terminal ID. Sorğudan əks etdirilir. */
    private String terminal;

    /** Tranzaksiya növü. Sorğudan əks etdirilir (0 və ya 1). */
    private String trtype;

    /** Satıcı sifariş ID-si. Sorğudan əks etdirilir. */
    private String order;

    /** İcazə verilən məbləğ. */
    private String amount;

    /** Valyuta kodu. Sorğudan əks etdirilir. */
    private String currency;

    /**
     * E-Gateway fəaliyyət kodu:
     * <ul>
     *   <li>{@code 0} – Uğurla tamamlandı</li>
     *   <li>{@code 1} – Dublikat əməliyyat</li>
     *   <li>{@code 2} – Rədd edildi</li>
     *   <li>{@code 3} – Emal xətası</li>
     * </ul>
     */
    private String action;

    /** Əməliyyat cavab kodu (ISO-8583 Field 39). Məs: "00" = uğurlu. */
    private String rc;

    /** Müştəri bankının təsdiq kodu (ISO-8583 Field 38). Boş ola bilər. */
    private String approval;

    /** Müştəri bankının axtarış istinad nömrəsi (ISO-8583 Field 37, 12 simvol). */
    private String rrn;

    /** Elektron ticarət şlüzünün daxili istinad nömrəsi (1-128 simvol). */
    private String intRef;

    /** E-Gateway tərəfindən yaranan vaxt damğası (YYYYMMDDHHMMSS, GMT). */
    private String timestamp;

    /** E-Gateway tərəfindən yaranan hex nonce. */
    private String nonce;

    /** E-Gateway-dən gələn RSA-SHA256 imzası (hex format, P_SIGN). */
    private String pSign;

    // ── Kart məlumatları (isteğe bağlı, əgər tokenizasiya aktifdirsə) ────

    /** EXT_NET_REF – kart saxlanılma zamanı qaytarılır. */
    private String extNetRef;

    /** Kart tokenizasiya zamanı qaytarılan token (28 simvol). */
    private String token;

    /** E-Gateway-dən qayıdan maskalanmış kart nömrəsi (məs: 412721******0724). */
    private String card;

    // ── Köməkçi metodlar ──────────────────────────────────────────────────

    /**
     * Tranzaksiyanın uğurlu olub-olmadığını yoxlayır.
     * Uğurlu hesab olunur: {@code ACTION=0} VƏ {@code RC=00}.
     *
     * @return uğurludursa {@code true}
     */
    public boolean isSuccessful() {
        return "0".equals(action) && "00".equals(rc);
    }

    /**
     * İmzanı yoxlamaq üçün spec-ə əsasən MAC mənbə sətirinin sahə sırası:
     * AMOUNT → TERMINAL → APPROVAL → RRN → INT_REF
     *
     * <p>Bu metod bilavasitə istifadə üçün deyil, yalnız sənədləşmə məqsədinə
     * xidmət edir. Əsl yoxlama {@code AbbIntegrationService.processCallback()}-da
     * {@code AbbSigner.verify()} çağırışı ilə aparılır.</p>
     */
    public String[] getCallbackMacFields() {
        return new String[]{amount, terminal, approval, rrn, intRef};
    }
}
