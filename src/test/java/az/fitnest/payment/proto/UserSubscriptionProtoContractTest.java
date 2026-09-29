package az.fitnest.payment.proto;

import az.fitnest.order.grpc.AssignSubscriptionToUserRequest;
import az.fitnest.order.grpc.AssignSubscriptionToUserResponse;
import az.fitnest.order.grpc.TerminateActiveFreezeRequest;
import az.fitnest.order.grpc.UserSubscriptionProto;
import az.fitnest.order.grpc.UserSubscriptionServiceGrpc;
import com.google.protobuf.Descriptors;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the wire contract of {@code src/main/proto/user_subscription.proto}.
 *
 * <p>payment-backend and order-backend are deployed independently; a renumbered or dropped
 * field here silently corrupts gRPC traffic between them. The October-campaign fields
 * ({@code bonus_months}, {@code campaign_applied}, {@code campaign_id}) must keep their
 * numbers 3-5 on the response, and the request must stay exactly the four assignment ids.</p>
 *
 * <p>Generated sources are compiled into the main source set, so the descriptors are on the
 * test classpath — no .proto text parsing needed.</p>
 */
class UserSubscriptionProtoContractTest {

    private static int fieldNumber(Descriptors.Descriptor descriptor, String fieldName) {
        Descriptors.FieldDescriptor field = descriptor.findFieldByName(fieldName);
        assertNotNull(field, () -> "Field '" + fieldName + "' missing from " + descriptor.getFullName());
        return field.getNumber();
    }

    private static List<String> fieldNames(Descriptors.Descriptor descriptor) {
        return descriptor.getFields().stream().map(Descriptors.FieldDescriptor::getName).toList();
    }

    @Test
    @DisplayName("AssignSubscriptionToUserResponse keeps subscription_id=1, user_id=2, bonus_months=3, campaign_applied=4, campaign_id=5")
    void testResponseFieldNumbersUnchanged() {
        Descriptors.Descriptor descriptor =
                UserSubscriptionProto.getDescriptor().findMessageTypeByName("AssignSubscriptionToUserResponse");
        assertNotNull(descriptor);

        assertEquals(5, descriptor.getFields().size());
        assertEquals(1, fieldNumber(descriptor, "subscription_id"));
        assertEquals(2, fieldNumber(descriptor, "user_id"));
        assertEquals(3, fieldNumber(descriptor, "bonus_months"));
        assertEquals(4, fieldNumber(descriptor, "campaign_applied"));
        assertEquals(5, fieldNumber(descriptor, "campaign_id"));
        assertEquals(
                List.of("subscription_id", "user_id", "bonus_months", "campaign_applied", "campaign_id"),
                fieldNames(descriptor));
    }

    @Test
    @DisplayName("AssignSubscriptionToUserRequest keeps user_id=1, plan_id=2, option_id=3, auto_payment_enabled=4")
    void testRequestFieldNumbersUnchanged() {
        Descriptors.Descriptor descriptor =
                UserSubscriptionProto.getDescriptor().findMessageTypeByName("AssignSubscriptionToUserRequest");
        assertNotNull(descriptor);

        assertEquals(4, descriptor.getFields().size());
        assertEquals(1, fieldNumber(descriptor, "user_id"));
        assertEquals(2, fieldNumber(descriptor, "plan_id"));
        assertEquals(3, fieldNumber(descriptor, "option_id"));
        assertEquals(4, fieldNumber(descriptor, "auto_payment_enabled"));
        assertEquals(
                List.of("user_id", "plan_id", "option_id", "auto_payment_enabled"),
                fieldNames(descriptor));
    }

    @Test
    @DisplayName("TerminateActiveFreezeRequest keeps subscription_id=1, reason=2, user_id=3")
    void testTerminateRequestFieldNumbersUnchanged() {
        Descriptors.Descriptor descriptor =
                UserSubscriptionProto.getDescriptor().findMessageTypeByName("TerminateActiveFreezeRequest");
        assertNotNull(descriptor);

        assertEquals(3, descriptor.getFields().size());
        assertEquals(1, fieldNumber(descriptor, "subscription_id"));
        assertEquals(2, fieldNumber(descriptor, "reason"));
        assertEquals(3, fieldNumber(descriptor, "user_id"));
    }

    @Test
    @DisplayName("Generated message classes expose the same descriptors the client code builds against")
    void testGeneratedClassesMatchFileDescriptor() {
        assertEquals(UserSubscriptionProto.getDescriptor().findMessageTypeByName("AssignSubscriptionToUserRequest"),
                AssignSubscriptionToUserRequest.getDescriptor());
        assertEquals(UserSubscriptionProto.getDescriptor().findMessageTypeByName("AssignSubscriptionToUserResponse"),
                AssignSubscriptionToUserResponse.getDescriptor());
        assertEquals(UserSubscriptionProto.getDescriptor().findMessageTypeByName("TerminateActiveFreezeRequest"),
                TerminateActiveFreezeRequest.getDescriptor());
    }

    /** grpc's {@code ServiceDescriptor} exposes only {@code getMethods()}, so look up by full name suffix. */
    private static MethodDescriptor<?, ?> methodByName(io.grpc.ServiceDescriptor service, String name) {
        return service.getMethods().stream()
                .filter(method -> method.getFullMethodName().endsWith("/" + name))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("Service exposes both unary RPCs used by the outbox relay")
    void testServiceExposesBothRpcs() {
        io.grpc.ServiceDescriptor service = UserSubscriptionServiceGrpc.getServiceDescriptor();

        MethodDescriptor<?, ?> assign = methodByName(service, "AssignSubscriptionToUser");
        MethodDescriptor<?, ?> terminate = methodByName(service, "TerminateActiveFreeze");
        assertNotNull(assign);
        assertNotNull(terminate);
        assertEquals(MethodDescriptor.MethodType.UNARY, assign.getType());
        assertEquals(MethodDescriptor.MethodType.UNARY, terminate.getType());
        assertTrue(assign.getFullMethodName().contains("AssignSubscriptionToUser"));
    }
}
