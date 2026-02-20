package az.fitnest.payment.client.epoint;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "epoint")
public class EpointProperties {
    private String publicKey;
    private String privateKey;
    private String baseUrl = "https://epoint.az/api/1";
    private String resultUrl;
    private String successRedirectUrl;
    private String errorRedirectUrl;
}
