package az.fitnest.payment.grpc;

import az.fitnest.payment.dto.epoint.EpointPaymentRequest;
import az.fitnest.payment.dto.epoint.EpointResponse;
import az.fitnest.payment.service.EpointIntegrationService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.Collections;

@GrpcService
@RequiredArgsConstructor
@Slf4j
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private final EpointIntegrationService integrationService;

    @Override
    public void createPayment(CreatePaymentRequest request, StreamObserver<CreatePaymentResponse> responseObserver) {
        log.info("Received gRPC CreatePayment request for orderId: {}", request.getOrderId());

        try {
            EpointPaymentRequest paymentRequest = EpointPaymentRequest.builder()
                    .orderId(request.getOrderId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .description(request.getDescription())
                    .language(request.getLanguage())
                    .isInstallment(request.getIsInstallment())
                    .refund(request.getRefund())
                    .otherAttr(request.getOtherAttrList())
                    .build();

            EpointResponse epointResponse = integrationService.initiatePayment(paymentRequest, null);

            CreatePaymentResponse response = CreatePaymentResponse.newBuilder()
                    .setStatus(epointResponse.status() != null ? epointResponse.status() : "error")
                    .setRedirectUrl(epointResponse.redirectUrl() != null ? epointResponse.redirectUrl() : "")
                    .setTransactionId(epointResponse.transaction() != null ? epointResponse.transaction() : "")
                    .setMessage(epointResponse.message() != null ? epointResponse.message() : "")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error processing gRPC CreatePayment", e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Internal error processing payment: " + e.getMessage())
                    .asRuntimeException());
        }
    }
}
