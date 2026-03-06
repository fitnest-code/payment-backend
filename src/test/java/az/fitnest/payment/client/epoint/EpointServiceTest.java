package az.fitnest.payment.client.epoint;

import az.fitnest.payment.dto.epoint.EpointPaymentRequest;
import az.fitnest.payment.dto.epoint.EpointResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EpointServiceTest {

    @Mock
    private EpointHttpClient httpClient;

    @Mock
    private EpointProperties properties;

    @InjectMocks
    private EpointService epointService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(properties.getPublicKey()).thenReturn("test_pub");
    }

    @Test
    void testCreatePayment() {
        EpointPaymentRequest request = EpointPaymentRequest.builder()
                .orderId("123")
                .amount(10.0)
                .build();
        
        EpointResponse mockResponse = EpointResponse.builder()
                .status("success")
                .transaction("trans_123")
                .build();

        when(httpClient.postSigned(eq("/request"), any())).thenReturn(mockResponse);
        
        EpointResponse response = epointService.createPayment(request);
        
        assertEquals("success", response.status());
        assertEquals("trans_123", response.transaction());
        verify(httpClient).postSigned(eq("/request"), any());
    }

    @Test
    void testGetStatus() {
        EpointResponse mockResponse = EpointResponse.builder()
                .status("success")
                .build();

        when(httpClient.postSigned(eq("/get-status"), any())).thenReturn(mockResponse);
        
        EpointResponse response = epointService.getStatus("trans_123");
        
        assertEquals("success", response.status());
        verify(httpClient).postSigned(eq("/get-status"), any());
    }
}
