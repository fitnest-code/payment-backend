package az.fitnest.payment.model.enums;

import lombok.Getter;

/**
 * SmartVista EPG (Bank of Baku) ödəniş statusları.
 */
@Getter
public enum BobPaymentStatus {

    REGISTERED(0, "Ödəniş qeydə alınıb"),
    AUTHORIZED(1, "Məbləğ bloklanıb"),
    APPROVED(2, "Ödəniş uğurla tamamlandı"),
    REVERSED(3, "Tranzaksiya ləğv edildi"),
    REFUNDED(4, "Məbləğ geri qaytarıldı"),
    AUTHENTICATION_INITIATED(5, "3D Secure gözlənilir"),
    DECLINED(6, "Ödənişdən imtina edildi"),
    UNKNOWN(-1, "Naməlum status");

    private final int code;
    private final String description;

    BobPaymentStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static BobPaymentStatus fromCode(int code) {
        for (BobPaymentStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return UNKNOWN;
    }
}
