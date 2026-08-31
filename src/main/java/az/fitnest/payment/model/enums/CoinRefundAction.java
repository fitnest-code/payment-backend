package az.fitnest.payment.model.enums;

/**
 * Refund (ödəniş ləğvi/geri qaytarılması) əməliyyatı zamanı hadisənin növü.
 *
 * RESTORE_SPENT_COINS  → Alış zamanı istifadə olunmuş Coin-lər balansa qaytarıldı (+amount)
 * REVERSE_EARNED_COINS → Alış zamanı qazanılmış Coin-lər balansdan ləğv edildi (-amount)
 */
public enum CoinRefundAction {
    RESTORE_SPENT_COINS,
    REVERSE_EARNED_COINS
}
