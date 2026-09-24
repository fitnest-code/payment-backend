package az.fitnest.payment.client;

import az.fitnest.order.grpc.UserSubscriptionServiceGrpc;
import az.fitnest.order.grpc.AssignSubscriptionToUserRequest;
import az.fitnest.order.grpc.AssignSubscriptionToUserResponse;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class UserSubscriptionGrpcClient {
    private static final Logger log = LoggerFactory.getLogger(UserSubscriptionGrpcClient.class);
    @GrpcClient("order-backend")
    private UserSubscriptionServiceGrpc.UserSubscriptionServiceBlockingStub stub;

    public AssignSubscriptionToUserResponse assignSubscriptionToUser(Long userId, Long planId, Long optionId, Boolean autoPaymentEnabled) {
        AssignSubscriptionToUserRequest request = AssignSubscriptionToUserRequest.newBuilder()
                .setUserId(userId)
                .setPlanId(planId)
                .setOptionId(optionId)
                .setAutoPaymentEnabled(autoPaymentEnabled != null ? autoPaymentEnabled : false)
                .build();
        log.info("[gRPC] Sending AssignSubscriptionToUser request: userId={}, planId={}, optionId={}, autoPaymentEnabled={}", userId, planId, optionId, autoPaymentEnabled);
        try {
            AssignSubscriptionToUserResponse response = stub.assignSubscriptionToUser(request);
            log.info("[gRPC] Received AssignSubscriptionToUser response: subscriptionId={}, userId={}", response.getSubscriptionId(), response.getUserId());
            return response;
        } catch (Exception e) {
            log.error("[gRPC] Error during AssignSubscriptionToUser request: userId={}, planId={}, optionId={}", userId, planId, optionId, e);
            throw e;
        }
    }

    public AssignSubscriptionToUserResponse assignSubscriptionToUser(Long userId, Long planId, Long optionId) {
        return assignSubscriptionToUser(userId, planId, optionId, false);
    }

    public void terminateActiveFreeze(Long subscriptionId) {
        terminateActiveFreeze(subscriptionId, null);
    }

    /**
     * Terminate active freeze by subscription id and/or user id (pass null subscriptionId to use userId only).
     */
    public void terminateActiveFreeze(Long subscriptionId, Long userId) {
        log.info("[gRPC] Terminating active freeze subscriptionId={} userId={}", subscriptionId, userId);
        try {
            az.fitnest.order.grpc.TerminateActiveFreezeRequest.Builder builder =
                    az.fitnest.order.grpc.TerminateActiveFreezeRequest.newBuilder()
                            .setReason("PAYMENT_REFUND_CANCEL");
            if (subscriptionId != null && subscriptionId > 0) {
                builder.setSubscriptionId(subscriptionId);
            }
            if (userId != null && userId > 0) {
                builder.setUserId(userId);
            }
            stub.terminateActiveFreeze(builder.build());
            log.info("[gRPC] Active freeze terminate requested subscriptionId={} userId={}", subscriptionId, userId);
        } catch (Exception e) {
            log.error("[gRPC] Failed to terminate active freeze subscriptionId={} userId={}", subscriptionId, userId, e);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }
}
