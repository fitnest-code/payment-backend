package az.fitnest.payment.dto.epoint;

public record EpointCardRegistrationRequest(
    String publicKey,
    String language,
    Integer refund,
    String description,
    String resultUrl,
    String successRedirectUrl,
    String errorRedirectUrl
) {}
