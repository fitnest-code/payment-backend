package az.fitnest.payment.client.epoint;

import az.fitnest.payment.dto.epoint.EpointResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class EpointHttpClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final EpointSigner signer;
    private final EpointProperties properties;

    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2),
            exclude = {IllegalArgumentException.class}
    )
    public EpointResponse postSigned(String endpoint, Object payload) {
        String data = signer.encodeData(payload);
        String signature = signer.sign(data, properties.getPrivateKey());

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("data", data);
        body.add("signature", signature);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        String url = properties.getBaseUrl() + endpoint;
        log.info("Sending signed POST to Epoint: {}", url);

        try {
            return restTemplate.postForObject(url, request, EpointResponse.class);
        } catch (Exception e) {
            log.error("Epoint API call failed: {}", url, e);
            throw e;
        }
    }
}
