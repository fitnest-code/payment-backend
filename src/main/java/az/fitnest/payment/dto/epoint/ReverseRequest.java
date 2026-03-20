package az.fitnest.payment.dto.epoint;

public record ReverseRequest(String transactionId, Double amount, String currency) {}
