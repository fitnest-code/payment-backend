package az.fitnest.payment.client.epoint;

import az.fitnest.payment.dto.epoint.EpointResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class EpointHttpClient {

    private final RestTemplate restTemplate;
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

        try {
            return restTemplate.postForObject(url, request, EpointResponse.class);
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * Send a direct JSON POST request without data+signature envelope.
     * Use this for endpoints that don't follow the standard signing pattern.
     *
     * @param endpoint The API endpoint path (e.g., "/wallet/status")
     * @param payload The request payload object (will be serialized to JSON)
     * @return EpointResponse from the API
     */
    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2),
            exclude = {IllegalArgumentException.class}
    )
    public EpointResponse postDirect(String endpoint, Object payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Object> request = new HttpEntity<>(payload, headers);

        String url = properties.getBaseUrl() + endpoint;

        try {
            return restTemplate.postForObject(url, request, EpointResponse.class);
        } catch (Exception e) {
            throw e;
        }
    }
}
