package az.fitnest.payment.model.enums;

/**
 * Mobil history kartının "sourceType" sahəsi üçün semantik tip.
 * Mobil dizayn buna görə ikonu, rəngi və başlığı seçir.
 */
public enum CoinTransactionSourceType {
    SUBSCRIPTION_PURCHASE,  // "Bronze - 1 aylıq abunəlik"
    WELCOME_BONUS,          // "Qeydiyyat bonusu"
    CAMPAIGN,               // "Yay kampaniyası"
    MANUAL_ADJUSTMENT,      // "Admin korreksiyası"
    REFUND,                 // "Ödəniş geri qaytarıldı"
    EXPIRY                  // "Müddət bitdi"
}
