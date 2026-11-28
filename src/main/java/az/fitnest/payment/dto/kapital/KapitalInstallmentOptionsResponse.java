package az.fitnest.payment.dto.kapital;

import java.util.List;

/** Checkout Taksit chips only — no echo of request/catalog fields. */
public record KapitalInstallmentOptionsResponse(List<Integer> installmentMonths) {}
