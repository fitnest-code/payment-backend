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
    @GrpcClient("order-service")
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
}
