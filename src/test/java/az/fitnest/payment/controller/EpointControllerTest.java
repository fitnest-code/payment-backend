package az.fitnest.payment.controller;

import az.fitnest.payment.client.epoint.EpointProperties;
import az.fitnest.payment.client.epoint.EpointSigner;
import az.fitnest.payment.service.EpointIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerTest {

    private MockMvc mockMvc;

    @Mock
    private EpointSigner signer;

    @Mock
    private EpointIntegrationService integrationService;

    @Mock
    private EpointProperties properties;

    @InjectMocks
    private PaymentController paymentController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(paymentController).build();
    }

    @Test
    void testHandleCallbackSuccess() throws Exception {
        when(properties.getPrivateKey()).thenReturn("test_key");
        when(signer.verify(anyString(), anyString(), anyString())).thenReturn(true);

        mockMvc.perform(post("/epoint/result")
                        .param("data", "base64data")
                        .param("signature", "vaildsignature"))
                .andExpect(status().isOk());

        verify(integrationService, times(1)).processCallback("base64data", "vaildsignature");
    }

    @Test
    void testHandleCallbackInvalidSignature() throws Exception {
        doThrow(new SecurityException("Invalid signature"))
                .when(integrationService).processCallback("base64data", "invalidsignature");

        mockMvc.perform(post("/epoint/result")
                        .param("data", "base64data")
                        .param("signature", "invalidsignature"))
                .andExpect(status().isUnauthorized());

        verify(integrationService, times(1)).processCallback("base64data", "invalidsignature");
    }
}
