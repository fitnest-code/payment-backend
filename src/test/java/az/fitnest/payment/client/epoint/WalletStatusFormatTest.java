package az.fitnest.payment.client.epoint;

import az.fitnest.payment.dto.epoint.EpointRequestPayload;
import az.fitnest.payment.dto.epoint.EpointResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WalletStatusFormatTest {

    @Mock
    private EpointHttpClient httpClient;

    @Mock
    private EpointProperties properties;

    @InjectMocks
    private EpointService epointService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(properties.getPublicKey()).thenReturn("test_public_key");
    }

    @Test
    void testWalletStatus_WithSignedEnvelope() {
        EpointResponse mockResponse = EpointResponse.builder()
                .status("success")
                .message("Wallet is active")
                .build();

        when(httpClient.postSigned(eq("/wallet/status"), any(EpointRequestPayload.class)))
                .thenReturn(mockResponse);

        EpointResponse response = epointService.walletStatus();

        assertNotNull(response);
        assertEquals("success", response.status());
        assertEquals("Wallet is active", response.message());

        verify(httpClient).postSigned(eq("/wallet/status"), any(EpointRequestPayload.class));
        verify(properties).getPublicKey();
    }

    @Test
    void testWalletStatus_WithDirectPost() {
        EpointResponse mockResponse = EpointResponse.builder()
                .status("success")
                .message("Wallet is active")
                .build();

        when(httpClient.postDirect(eq("/wallet/status"), any(EpointRequestPayload.class)))
                .thenReturn(mockResponse);

        EpointRequestPayload request = EpointRequestPayload.builder()
                .publicKey(properties.getPublicKey())
                .build();
        EpointResponse response = httpClient.postDirect("/wallet/status", request);

        assertNotNull(response);
        assertEquals("success", response.status());
        assertEquals("Wallet is active", response.message());

        verify(httpClient).postDirect(eq("/wallet/status"), any(EpointRequestPayload.class));
    }

    @Test
    void testWalletStatusRequestPayload() {
        String expectedPublicKey = "merchant_public_key";
        when(properties.getPublicKey()).thenReturn(expectedPublicKey);

        EpointRequestPayload request = EpointRequestPayload.builder()
                .publicKey(properties.getPublicKey())
                .build();

        assertNotNull(request);
        assertEquals(expectedPublicKey, request.publicKey());
    }

    @Test
    void testWalletStatus_OnlyRequiresPublicKey() {
        EpointResponse mockResponse = EpointResponse.builder()
                .status("success")
                .build();

        when(httpClient.postSigned(eq("/wallet/status"), any(EpointRequestPayload.class)))
                .thenReturn(mockResponse);

        EpointResponse response = epointService.walletStatus();

        assertNotNull(response);

        verify(httpClient).postSigned(eq("/wallet/status"), argThat(payload -> {
            if (payload instanceof EpointRequestPayload) {
                EpointRequestPayload req = (EpointRequestPayload) payload;
                return req.publicKey() != null && req.publicKey().equals("test_public_key");
            }
            return false;
        }));
    }
}
