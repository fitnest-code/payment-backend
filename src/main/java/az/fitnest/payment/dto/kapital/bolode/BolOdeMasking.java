package az.fitnest.payment.dto.kapital.bolode;

/** PII masking for admin lists / responses. Raw FIN is only shown on the admin detail view. */
public final class BolOdeMasking {

    private BolOdeMasking() {
    }

    /** "AB12CDE" → "AB***E". */
    public static String maskFin(String fin) {
        if (fin == null || fin.length() < 4) {
            return fin == null ? null : "***";
        }
        return fin.substring(0, 2) + "***" + fin.substring(fin.length() - 1);
    }
}
