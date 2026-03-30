package az.fitnest.payment.client;

import az.fitnest.order.grpc.CheckOptionInPackageExistsRequest;
import az.fitnest.order.grpc.CheckOptionInPackageExistsResponse;
import az.fitnest.order.grpc.SubscriptionPackageServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionPackageGrpcClient {
    @GrpcClient("order-service")
    private SubscriptionPackageServiceGrpc.SubscriptionPackageServiceBlockingStub stub;

    public boolean checkOptionInPackageExists(Long packageId, Long optionId) {
        CheckOptionInPackageExistsRequest request = CheckOptionInPackageExistsRequest.newBuilder()
                .setPackageId(packageId)
                .setOptionId(optionId)
                .build();
        CheckOptionInPackageExistsResponse response = stub.checkOptionInPackageExists(request);
        return response.getExists();
    }
}
