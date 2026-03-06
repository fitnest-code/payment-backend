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

/**
 * Test class for wallet status endpoint format verification.
 *
 * This test demonstrates both the current implementation (with data+signature envelope)
 * and the potential alternative implementation (direct JSON POST).
 */
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

    /**
     * Test current implementation using postSigned (data+signature envelope)
     */
    @Test
    void testWalletStatus_WithSignedEnvelope() {
        // Arrange
        EpointResponse mockResponse = EpointResponse.builder()
                .status("success")
                .message("Wallet is active")
                .build();

        when(httpClient.postSigned(eq("/wallet/status"), any(EpointRequestPayload.class)))
                .thenReturn(mockResponse);

        // Act
        EpointResponse response = epointService.walletStatus();

        // Assert
        assertNotNull(response);
        assertEquals("success", response.status());
        assertEquals("Wallet is active", response.message());

        // Verify that postSigned was called with correct parameters
        verify(httpClient).postSigned(eq("/wallet/status"), any(EpointRequestPayload.class));
        verify(properties).getPublicKey();
    }

    /**
     * Test alternative implementation using postDirect (no envelope)
     *
     * This demonstrates how to switch to direct JSON POST if needed.
     * To enable this format, change the implementation in EpointService:
     *
     * Before:
     *   return httpClient.postSigned("/wallet/status", request);
     *
     * After:
     *   return httpClient.postDirect("/wallet/status", request);
     */
    @Test
    void testWalletStatus_WithDirectPost() {
        // Arrange
        EpointResponse mockResponse = EpointResponse.builder()
                .status("success")
                .message("Wallet is active")
                .build();

        when(httpClient.postDirect(eq("/wallet/status"), any(EpointRequestPayload.class)))
                .thenReturn(mockResponse);

        // Act - Simulate the alternative implementation
        EpointRequestPayload request = EpointRequestPayload.builder()
                .publicKey(properties.getPublicKey())
                .build();
        EpointResponse response = httpClient.postDirect("/wallet/status", request);

        // Assert
        assertNotNull(response);
        assertEquals("success", response.status());
        assertEquals("Wallet is active", response.message());

        // Verify that postDirect was called (not postSigned)
        verify(httpClient).postDirect(eq("/wallet/status"), any(EpointRequestPayload.class));
    }

    /**
     * Test request payload structure
     */
    @Test
    void testWalletStatusRequestPayload() {
        // Arrange
        String expectedPublicKey = "merchant_public_key";
        when(properties.getPublicKey()).thenReturn(expectedPublicKey);

        // Act
        EpointRequestPayload request = EpointRequestPayload.builder()
                .publicKey(properties.getPublicKey())
                .build();

        // Assert
        assertNotNull(request);
        assertEquals(expectedPublicKey, request.publicKey());
    }

    /**
     * Test that wallet status doesn't require additional parameters
     * (unlike other endpoints that need orderId, amount, etc.)
     */
    @Test
    void testWalletStatus_OnlyRequiresPublicKey() {
        // Arrange
        EpointResponse mockResponse = EpointResponse.builder()
                .status("success")
                .build();

        when(httpClient.postSigned(eq("/wallet/status"), any(EpointRequestPayload.class)))
                .thenReturn(mockResponse);

        // Act
        EpointResponse response = epointService.walletStatus();

        // Assert
        assertNotNull(response);

        // Verify the request only contains public_key
        verify(httpClient).postSigned(eq("/wallet/status"), argThat(payload -> {
            if (payload instanceof EpointRequestPayload) {
                EpointRequestPayload req = (EpointRequestPayload) payload;
                return req.publicKey() != null && req.publicKey().equals("test_public_key");
            }
            return false;
        }));
    }
}

