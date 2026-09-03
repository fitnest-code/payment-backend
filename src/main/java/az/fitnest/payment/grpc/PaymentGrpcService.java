package az.fitnest.payment.grpc;

import az.fitnest.payment.dto.coin.CoinWalletResponse;
import az.fitnest.payment.dto.epoint.EpointPaymentRequest;
import az.fitnest.payment.dto.epoint.EpointResponse;
import az.fitnest.payment.service.CoinWalletService;
import az.fitnest.payment.service.EpointIntegrationService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.devh.boot.grpc.server.service.GrpcService;

import az.fitnest.payment.dto.epoint.EpointExecutePayRequest;
import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.util.PaymentPackageRef;
import java.util.Collections;

import az.fitnest.payment.service.UserPaymentService;

@GrpcService
@RequiredArgsConstructor
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PaymentGrpcService.class);

    private final EpointIntegrationService integrationService;
    private final UserPaymentService userPaymentService;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;
    private final CoinWalletService coinWalletService;

    @Override
    public void createPayment(CreatePaymentRequest request, StreamObserver<CreatePaymentResponse> responseObserver) {
        log.info("Received gRPC CreatePayment request for orderId: {}", request.getOrderId());

        try {
            // Prefer server-side package pricing when package/option are present; never trust client amount alone.
            Double amount = request.getAmount();
            String currency = request.getCurrency();
            String otherAttr = request.getOtherAttrList().isEmpty()
                    ? null
                    : String.join(",", request.getOtherAttrList());

            Long packageId = null;
            Long optionId = null;
            try {
                var ref = PaymentPackageRef.parseWithFallback(request.getDescription(), otherAttr);
                if (ref.isComplete()) {
                    packageId = ref.packageId();
                    optionId = ref.optionId();
                }
            } catch (Exception ignored) {
                // fall through to amount validation
            }

            if (packageId != null && optionId != null) {
                var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
                amount = priceCurrency.amount;
                currency = priceCurrency.currency != null ? priceCurrency.currency : currency;
                otherAttr = PaymentPackageRef.encode(packageId, optionId);
            }

            if (amount == null || amount <= 0) {
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                        .withDescription("Amount must be resolved from package/option or provided as positive")
                        .asRuntimeException());
                return;
            }

            EpointPaymentRequest paymentRequest = EpointPaymentRequest.builder()
                    .orderId(request.getOrderId())
                    .amount(amount)
                    .currency(currency)
                    .description(request.getDescription())
                    .language(request.getLanguage())
                    .isInstallment(request.getIsInstallment())
                    .refund(request.getRefund())
                    .otherAttr(otherAttr)
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

    @Override
    public void payWithCard(PayWithCardRequest request, StreamObserver<PayWithCardResponse> responseObserver) {
        log.info("Received gRPC PayWithCard request for userId: {}, cardId: {}, packageId: {}, optionId: {}",
                request.getUserId(), request.getCardId(), request.getPackageId(), request.getOptionId());

        try {
            String orderId = java.util.UUID.randomUUID().toString();
            var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(request.getPackageId(), request.getOptionId());

            EpointExecutePayRequest payRequest = EpointExecutePayRequest.builder()
                    .publicKey(integrationService.getPublicKey())
                    .language("az")
                    .orderId(orderId)
                    .amount(priceCurrency.amount)
                    .currency(priceCurrency.currency)
                    .cardId(request.getCardId())
                    .description(PaymentPackageRef.encode(request.getPackageId(), request.getOptionId()))
                    .isInstallment(0)
                    .build();

            EpointResponse epointResponse = integrationService.executePay(payRequest, request.getUserId());

            PayWithCardResponse response = PayWithCardResponse.newBuilder()
                    .setStatus(epointResponse.status() != null ? epointResponse.status() : "error")
                    .setTransactionId(epointResponse.transaction() != null ? epointResponse.transaction() : "")
                    .setMessage(epointResponse.message() != null ? epointResponse.message() : "")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error in payWithCard", e);
            responseObserver.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getCoinWallet(GetCoinWalletRequest request, StreamObserver<GetCoinWalletResponse> responseObserver) {
        log.debug("Received gRPC GetCoinWallet request for userId: {}", request.getUserId());
        try {
            CoinWalletResponse wallet = coinWalletService.getWalletInfo(request.getUserId());
            GetCoinWalletResponse response = GetCoinWalletResponse.newBuilder()
                    .setCoinBalance(wallet.getTotalBalance() != null ? wallet.getTotalBalance().toPlainString() : "0")
                    .setAznEquivalent(wallet.getAznEquivalent() != null ? wallet.getAznEquivalent().toPlainString() : "0")
                    .setValidityDate(wallet.getExpiryDate() != null ? wallet.getExpiryDate().toString() : "")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error in getCoinWallet for userId={}", request.getUserId(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getUserCards(GetUserCardsRequest request, StreamObserver<GetUserCardsResponse> responseObserver) {
        log.info("Received gRPC GetUserCards request for userId: {}", request.getUserId());
        try {
            var cards = userPaymentService.getUserCards(request.getUserId());
            GetUserCardsResponse.Builder responseBuilder = GetUserCardsResponse.newBuilder();
            for (var card : cards) {
                responseBuilder.addCards(UserCardDto.newBuilder()
                        .setCardId(card.cardId())
                        .setCardMask(card.cardMask())
                        .setCardName(card.cardName() != null ? card.cardName() : "")
                        .setBrand(card.brand() != null ? card.brand() : "")
                        .build());
            }
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error in getUserCards", e);
            responseObserver.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
