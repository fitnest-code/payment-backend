package az.fitnest.payment.dto.epoint;

public record EpointStatusRequest(
    @com.fasterxml.jackson.annotation.JsonProperty("public_key")
    String publicKey,
    String transaction
) {}
