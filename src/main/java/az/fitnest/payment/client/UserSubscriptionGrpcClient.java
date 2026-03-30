package az.fitnest.payment.client;

import az.fitnest.order.grpc.UserSubscriptionServiceGrpc;
import az.fitnest.order.grpc.AssignSubscriptionToUserRequest;
import az.fitnest.order.grpc.AssignSubscriptionToUserResponse;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

@Component
public class UserSubscriptionGrpcClient {
    @GrpcClient("order-service")
    private UserSubscriptionServiceGrpc.UserSubscriptionServiceBlockingStub stub;

    public AssignSubscriptionToUserResponse assignSubscriptionToUser(Long userId, Long planId, Long optionId) {
        AssignSubscriptionToUserRequest request = AssignSubscriptionToUserRequest.newBuilder()
                .setUserId(userId)
                .setPlanId(planId)
                .setOptionId(optionId)
                .build();
        return stub.assignSubscriptionToUser(request);
    }
}

