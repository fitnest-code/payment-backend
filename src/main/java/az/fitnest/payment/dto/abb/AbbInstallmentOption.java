package az.fitnest.payment.dto.abb;

/**
 * Azericard taksit (installment) seçenekleri.
 *
 * <p>Spec §3 – ACQ_INST_PAYIN parametri üçün etibarlı dəyərlər.
 * Taksit olmadan ödəniş üçün {@link #NONE} istifadə edilir.</p>
 */
public enum AbbInstallmentOption {

    /** Taksitsiz ödəniş */
    NONE("INST_ALLX"),

    /** 3 aylıq taksit */
    MONTHS_3("INST_ALL3"),

    /** 6 aylıq taksit */
    MONTHS_6("INST_ALL6"),

    /** 9 aylıq taksit */
    MONTHS_9("INST_ALL9"),

    /** 12 aylıq taksit */
    MONTHS_12("INST_ALL12"),

    /** 18 aylıq taksit */
    MONTHS_18("INST_ALL18"),

    /** 24 aylıq taksit */
    MONTHS_24("INST_ALL24"),

    /** 27 aylıq taksit */
    MONTHS_27("INST_ALL27"),

    /** 30 aylıq taksit */
    MONTHS_30("INST_ALL30");

    private final String paramValue;

    AbbInstallmentOption(String paramValue) {
        this.paramValue = paramValue;
    }

    /**
     * ACQ_INST_PAYIN sahəsinə göndəriləcək dəyər.
     */
    public String getParamValue() {
        return paramValue;
    }

    /**
     * Taksit seçimi aktif olub-olmadığını yoxlayır.
     */
    public boolean isInstallment() {
        return this != NONE;
    }

    /**
     * Taksit sayından enum dəyərini tapır.
     *
     * @param months taksit sayı (3, 6, 9, 12, 18, 24, 27, 30)
     * @return uyğun enum dəyəri, tapılmazsa {@link #NONE}
     */
    public static AbbInstallmentOption fromMonths(int months) {
        return switch (months) {
            case 3  -> MONTHS_3;
            case 6  -> MONTHS_6;
            case 9  -> MONTHS_9;
            case 12 -> MONTHS_12;
            case 18 -> MONTHS_18;
            case 24 -> MONTHS_24;
            case 27 -> MONTHS_27;
            case 30 -> MONTHS_30;
            default -> NONE;
        };
    }
}
