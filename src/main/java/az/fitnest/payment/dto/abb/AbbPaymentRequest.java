package az.fitnest.payment.dto.abb;

import lombok.Builder;
import lombok.Data;

/**
 * ABB (Azericard) E-Commerce Gateway-ə ödəniş başlatmaq üçün sorğu modeli.
 *
 * <p>Bu DTO spec §2 (TRTYPE=0/1) əsasında bütün tələb olunan
 * və isteğe bağlı sahələri əhatə edir. Serialization zamanı null sahələr
 * form parametr siyahısına daxil edilmir.</p>
 *
 * <h3>TRTYPE dəyərləri</h3>
 * <ul>
 *   <li>{@code 0} – Preavtorizasiya (Pre-auth): TRTYPE=21 ilə tamamlanmalıdır</li>
 *   <li>{@code 1} – Birbaşa avtorizasiya (Direct authorization)</li>
 * </ul>
 */
@Data
@Builder
public class AbbPaymentRequest {

    // ── Məcburi sahələr ────────────────────────────────────────────────────

    /** Sifarişin ümumi məbləği sürüşkən nöqtəli formatda. Məs: "10.00" */
    private String amount;

    /** Sifariş valyutası: 3 simvollu ISO 4217 kodu. Məs: "AZN", "USD" */
    private String currency;

    /**
     * Satıcı sifariş ID-si (6-32 rəqəmli, günlük terminal üzrə unikal).
     * Son 6 rəqəm sistem izi audit nömrəsi kimi istifadə olunur.
     */
    private String order;

    /** Tranzaksiya növü: 0 (pre-auth) və ya 1 (direct) */
    private String trtype;

    /** Avtorizasiya nəticəsinin POST ediləcəyi Merchant URL (callback/BACKREF). */
    private String backref;

    // ── İmzalama sahələri (mandatory if MAC used) ─────────────────────────

    /**
     * GMT formatında əməliyyat vaxt damğası: YYYYMMDDHHMMSS.
     * Merchant server ilə Gateway arasında fərq 1 saatı keçməməlidir.
     */
    private String timestamp;

    /** Hexadecimal formatda 8-32 bayt təsadüfi nonce. MAC üçün məcburidir. */
    private String nonce;

    /** RSA-SHA256 imzası (hex format). AbbSigner tərəfindən hesablanır. */
    private String pSign;

    // ── Merchant məlumatları ───────────────────────────────────────────────

    /** Bank tərəfindən təyin edilmiş Merchant Terminal ID (8 simvol). */
    private String terminal;

    /** Merchant-ın ticarət adı (max 25 simvol). */
    private String merchName;

    /** Merchant-ın internet mağazası URL-i (max 250 simvol). */
    private String merchUrl;

    /** Merchant-ın e-mail ünvanı (bildirimler üçün). */
    private String email;

    /** Merchant-ın ölkə kodu (ISO 3166-1 alpha-2, məs: "AZ"). */
    private String country;

    /** Merchant-ın UTC/GMT vaxt zonası. Məs: "+4". */
    private String merchGmt;

    // ── Müştəri məlumatları (isteğe bağlı) ───────────────────────────────

    /** Sifariş açıqlaması (ödəniş ekranında görünür). */
    private String desc;

    /** Dil kodu (məs: "AZ", "EN", "RU"). */
    private String lang;

    /** Müştərinin adı soyadı. */
    private String name;

    // ── İnstallment sahəsi (isteğe bağlı) ────────────────────────────────

    /**
     * Taksit sayı parametri. Dəyərlər:
     * <ul>
     *   <li>Taksit üçün: {@code "INST_ALL3"}, {@code "INST_ALL6"}, {@code "INST_ALL12"}, və s.
     *       (*=3,6,9,12,18,24,27,30)</li>
     *   <li>Taksitsiz: {@code "INST_ALLX"}</li>
     * </ul>
     */
    private String acqInstPayin;

    // ── Browser məlumatları 3DS v2 üçün (isteğe bağlı) ───────────────────

    /**
     * 3DS v2 üçün brauzer məlumatları JSON formatında, Base64 kodlaşdırılmış.
     * Məzmun: browserScreenHeight, browserScreenWidth, browserTZ, mobilePhone
     */
    private String mInfo;
}
