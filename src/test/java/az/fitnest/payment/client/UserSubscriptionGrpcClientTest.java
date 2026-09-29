package az.fitnest.payment.client;

import az.fitnest.order.grpc.AssignSubscriptionToUserRequest;
import az.fitnest.order.grpc.AssignSubscriptionToUserResponse;
import az.fitnest.order.grpc.TerminateActiveFreezeRequest;
import az.fitnest.order.grpc.UserSubscriptionServiceGrpc;
import com.google.protobuf.Descriptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the outbox-facing gRPC client: request construction for
 * {@code AssignSubscriptionToUser} and {@code TerminateActiveFreeze}.
 *
 * <p>The blocking stub is injected at runtime by {@code @GrpcClient("order-backend")}; the test
 * injects a mock into that same private field via reflection so production code stays untouched.
 * Note: unlike most collaborators the stub field has no setter/constructor seam — see
 * {@code UserSubscriptionGrpcClient#stub} (field, L15).</p>
 */
@ExtendWith(MockitoExtension.class)
class UserSubscriptionGrpcClientTest {

    @Mock
    private UserSubscriptionServiceGrpc.UserSubscriptionServiceBlockingStub blockingStub;

    private UserSubscriptionGrpcClient client;

    @BeforeEach
    void setUp() {
        client = new UserSubscriptionGrpcClient();
        ReflectionTestUtils.setField(client, "stub", blockingStub);
    }

    private static List<String> fieldNames(Descriptors.Descriptor descriptor) {
        return descriptor.getFields().stream().map(Descriptors.FieldDescriptor::getName).toList();
    }

    @Test
    @DisplayName("assignSubscriptionToUser forwards all four fields and returns the stub response")
    void testAssignSubscriptionToUser_ForwardsAllFields() {
        AssignSubscriptionToUserResponse response = AssignSubscriptionToUserResponse.newBuilder()
                .setSubscriptionId(77L)
                .setUserId(5L)
                .setBonusMonths(3)
                .setCampaignApplied(true)
                .setCampaignId(9L)
                .build();
        when(blockingStub.assignSubscriptionToUser(any(AssignSubscriptionToUserRequest.class)))
                .thenReturn(response);

        AssignSubscriptionToUserResponse result = client.assignSubscriptionToUser(5L, 10L, 20L, true);

        assertSame(response, result);
        ArgumentCaptor<AssignSubscriptionToUserRequest> captor =
                ArgumentCaptor.forClass(AssignSubscriptionToUserRequest.class);
        verify(blockingStub, times(1)).assignSubscriptionToUser(captor.capture());
        AssignSubscriptionToUserRequest request = captor.getValue();
        assertEquals(5L, request.getUserId());
        assertEquals(10L, request.getPlanId());
        assertEquals(20L, request.getOptionId());
        assertTrue(request.getAutoPaymentEnabled());
    }

    @Test
    @DisplayName("3-arg overload defaults autoPaymentEnabled to false")
    void testAssignSubscriptionToUser_ThreeArgOverloadDefaultsAutoPaymentToFalse() {
        when(blockingStub.assignSubscriptionToUser(any(AssignSubscriptionToUserRequest.class)))
                .thenReturn(AssignSubscriptionToUserResponse.getDefaultInstance());

        client.assignSubscriptionToUser(5L, 10L, 20L);

        ArgumentCaptor<AssignSubscriptionToUserRequest> captor =
                ArgumentCaptor.forClass(AssignSubscriptionToUserRequest.class);
        verify(blockingStub, times(1)).assignSubscriptionToUser(captor.capture());
        assertFalse(captor.getValue().getAutoPaymentEnabled());
        assertEquals(5L, captor.getValue().getUserId());
        assertEquals(10L, captor.getValue().getPlanId());
        assertEquals(20L, captor.getValue().getOptionId());
    }

    @Test
    @DisplayName("null autoPaymentEnabled defaults to false")
    void testAssignSubscriptionToUser_NullAutoPaymentEnabledDefaultsToFalse() {
        when(blockingStub.assignSubscriptionToUser(any(AssignSubscriptionToUserRequest.class)))
                .thenReturn(AssignSubscriptionToUserResponse.getDefaultInstance());

        client.assignSubscriptionToUser(5L, 10L, 20L, null);

        ArgumentCaptor<AssignSubscriptionToUserRequest> captor =
                ArgumentCaptor.forClass(AssignSubscriptionToUserRequest.class);
        verify(blockingStub, times(1)).assignSubscriptionToUser(captor.capture());
        assertFalse(captor.getValue().getAutoPaymentEnabled());
    }

    @Test
    @DisplayName("assign request carries exactly user_id/plan_id/option_id/auto_payment_enabled - no campaign fields")
    void testAssignSubscriptionToUser_RequestCarriesNoCampaignFields() {
        when(blockingStub.assignSubscriptionToUser(any(AssignSubscriptionToUserRequest.class)))
                .thenReturn(AssignSubscriptionToUserResponse.getDefaultInstance());

        client.assignSubscriptionToUser(5L, 10L, 20L, true);

        ArgumentCaptor<AssignSubscriptionToUserRequest> captor =
                ArgumentCaptor.forClass(AssignSubscriptionToUserRequest.class);
        verify(blockingStub).assignSubscriptionToUser(captor.capture());
        assertEquals(
                List.of("user_id", "plan_id", "option_id", "auto_payment_enabled"),
                fieldNames(captor.getValue().getDescriptorForType()));
    }

    @Test
    @DisplayName("stub failure propagates to the caller after being logged")
    void testAssignSubscriptionToUser_PropagatesStubFailure() {
        StatusRuntimeException failure = new StatusRuntimeException(Status.UNAVAILABLE);
        when(blockingStub.assignSubscriptionToUser(any(AssignSubscriptionToUserRequest.class)))
                .thenThrow(failure);

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class,
                () -> client.assignSubscriptionToUser(5L, 10L, 20L, true));

        assertSame(failure, thrown);
        verify(blockingStub, times(1)).assignSubscriptionToUser(any(AssignSubscriptionToUserRequest.class));
    }

    @Test
    @DisplayName("terminateActiveFreeze sets both ids and the PAYMENT_REFUND_CANCEL reason")
    void testTerminateActiveFreeze_SetsBothIdsAndReason() {
        client.terminateActiveFreeze(11L, 22L);

        ArgumentCaptor<TerminateActiveFreezeRequest> captor =
                ArgumentCaptor.forClass(TerminateActiveFreezeRequest.class);
        verify(blockingStub, times(1)).terminateActiveFreeze(captor.capture());
        TerminateActiveFreezeRequest request = captor.getValue();
        assertEquals("PAYMENT_REFUND_CANCEL", request.getReason());
        assertEquals(11L, request.getSubscriptionId());
        assertEquals(22L, request.getUserId());
    }

    @Test
    @DisplayName("null subscriptionId builds a userId-only request")
    void testTerminateActiveFreeze_NullSubscriptionIdUsesUserOnly() {
        client.terminateActiveFreeze(null, 22L);

        ArgumentCaptor<TerminateActiveFreezeRequest> captor =
                ArgumentCaptor.forClass(TerminateActiveFreezeRequest.class);
        verify(blockingStub, times(1)).terminateActiveFreeze(captor.capture());
        TerminateActiveFreezeRequest request = captor.getValue();
        assertEquals("PAYMENT_REFUND_CANCEL", request.getReason());
        assertEquals(0L, request.getSubscriptionId());
        assertEquals(22L, request.getUserId());
    }

    @Test
    @DisplayName("both ids null builds a request with neither id set")
    void testTerminateActiveFreeze_BothNullNeitherIdSet() {
        client.terminateActiveFreeze(null, null);

        ArgumentCaptor<TerminateActiveFreezeRequest> captor =
                ArgumentCaptor.forClass(TerminateActiveFreezeRequest.class);
        verify(blockingStub, times(1)).terminateActiveFreeze(captor.capture());
        TerminateActiveFreezeRequest request = captor.getValue();
        assertEquals("PAYMENT_REFUND_CANCEL", request.getReason());
        assertEquals(0L, request.getSubscriptionId());
        assertEquals(0L, request.getUserId());
    }

    @Test
    @DisplayName("non-positive ids (0 / negative) are not set on the request")
    void testTerminateActiveFreeze_NonPositiveIdsAreNotSet() {
        client.terminateActiveFreeze(0L, -1L);

        ArgumentCaptor<TerminateActiveFreezeRequest> captor =
                ArgumentCaptor.forClass(TerminateActiveFreezeRequest.class);
        verify(blockingStub, times(1)).terminateActiveFreeze(captor.capture());
        assertEquals(0L, captor.getValue().getSubscriptionId());
        assertEquals(0L, captor.getValue().getUserId());
    }

    @Test
    @DisplayName("single-arg overload delegates with a null userId")
    void testTerminateActiveFreeze_SingleArgOverloadPassesNullUserId() {
        client.terminateActiveFreeze(7L);

        ArgumentCaptor<TerminateActiveFreezeRequest> captor =
                ArgumentCaptor.forClass(TerminateActiveFreezeRequest.class);
        verify(blockingStub, times(1)).terminateActiveFreeze(captor.capture());
        TerminateActiveFreezeRequest request = captor.getValue();
        assertEquals(7L, request.getSubscriptionId());
        assertEquals(0L, request.getUserId());
        assertEquals("PAYMENT_REFUND_CANCEL", request.getReason());
    }

    @Test
    @DisplayName("terminateActiveFreeze logs and rethrows stub failures (never swallowed)")
    void testTerminateActiveFreeze_StubFailurePropagates() {
        StatusRuntimeException failure = new StatusRuntimeException(Status.UNAVAILABLE);
        when(blockingStub.terminateActiveFreeze(any(TerminateActiveFreezeRequest.class))).thenThrow(failure);

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class,
                () -> client.terminateActiveFreeze(11L, 22L));

        assertSame(failure, thrown);
        verify(blockingStub, times(1)).terminateActiveFreeze(any(TerminateActiveFreezeRequest.class));
    }

    @Test
    @DisplayName("terminate request descriptor keeps subscription_id=1, reason=2, user_id=3")
    void testTerminateActiveFreeze_RequestFieldNumbersUnchanged() {
        Descriptors.Descriptor descriptor = TerminateActiveFreezeRequest.getDescriptor();
        assertEquals(1, descriptor.findFieldByName("subscription_id").getNumber());
        assertEquals(2, descriptor.findFieldByName("reason").getNumber());
        assertEquals(3, descriptor.findFieldByName("user_id").getNumber());
        assertNotNull(descriptor);
    }
}
