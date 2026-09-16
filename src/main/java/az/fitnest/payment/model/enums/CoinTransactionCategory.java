package az.fitnest.payment.model.enums;

/**
 * Mobil tarixçə ekranındakı tablar üçün filter kateqoriyası.
 *
 * ALL     → Hamısı
 * EARNED  → Qazanılan  (BONUS, EARN, CAMPAIGN_BONUS, müsbət ADJUSTMENT)
 * SPENT   → İstifadə edilən (SPEND)
 * EXPIRED → Bitən (EXPIRE)
 */
public enum CoinTransactionCategory {
    ALL,
    EARNED,
    SPENT,
    EXPIRED
}
