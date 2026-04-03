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

    public OptionPriceCurrency getOptionPriceCurrency(Long packageId, Long optionId) {
        az.fitnest.order.grpc.GetOptionDetailsRequest request = az.fitnest.order.grpc.GetOptionDetailsRequest.newBuilder()
                .setPackageId(packageId)
                .setOptionId(optionId)
                .build();
        az.fitnest.order.grpc.GetOptionDetailsResponse response = stub.getOptionDetails(request);
        return new OptionPriceCurrency(response.getAmount(), response.getCurrency(), response.getDurationMonths());
    }

    public static class OptionPriceCurrency {
        public final double amount;
        public final String currency;
        public final int durationMonths;
        public OptionPriceCurrency(double amount, String currency, int durationMonths) {
            this.amount = amount;
            this.currency = currency;
            this.durationMonths = durationMonths;
        }
    }
}
