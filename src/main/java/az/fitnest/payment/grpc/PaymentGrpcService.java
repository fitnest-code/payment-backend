package az.fitnest.payment.grpc;

import az.fitnest.payment.dto.coin.CoinWalletResponse;
import az.fitnest.payment.dto.epoint.EpointPaymentRequest;
import az.fitnest.payment.dto.epoint.EpointResponse;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.service.AbbIntegrationService;
import az.fitnest.payment.service.BobIntegrationService;
import az.fitnest.payment.service.CoinWalletService;
import az.fitnest.payment.service.EpointIntegrationService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.devh.boot.grpc.server.service.GrpcService;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.util.PaymentPackageRef;

import az.fitnest.payment.service.UserPaymentService;

import java.math.BigDecimal;
import java.math.RoundingMode;

@GrpcService
@RequiredArgsConstructor
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PaymentGrpcService.class);

    private final EpointIntegrationService integrationService;
    private final UserPaymentService userPaymentService;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;
    private final CoinWalletService coinWalletService;
    private final PaymentRepository paymentRepository;
    private final AbbIntegrationService abbIntegrationService;
    private final BobIntegrationService bobIntegrationService;

    @Override
    public void createPayment(CreatePaymentRequest request, StreamObserver<CreatePaymentResponse> responseObserver) {
        log.info("Received gRPC CreatePayment request for orderId: {}", request.getOrderId());

        try {
            // If the amount is pre-resolved (e.g. with promo discount), do not re-price
            // from package/option — keep the amount the client (order-backend) set.
            boolean prePriced = request.getPrePriced();
            Double amount = request.getAmount();
            String currency = request.getCurrency();
            String otherAttr = request.getOtherAttrList().isEmpty()
                    ? null
                    : String.join(",", request.getOtherAttrList());

            String provider = request.getProvider() == null ? "" : request.getProvider().trim().toLowerCase();
            if ("abb".equals(provider) || "bob".equals(provider)) {
                handleInstallmentProvider(request, provider, prePriced, otherAttr, responseObserver);
                return;
            }
            if ("apple_pay".equals(provider)) {
                handleApplePayPrePriced(request, prePriced, otherAttr, responseObserver);
                return;
            }
            if ("google_pay".equals(provider)) {
                handleGooglePayPrePriced(request, prePriced, otherAttr, responseObserver);
                return;
            }
            if ("saved_card".equals(provider)) {
                handleSavedCardPrePriced(request, prePriced, otherAttr, responseObserver);
                return;
            }
            if (!provider.isEmpty() && !"epoint".equals(provider)) {
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                        .withDescription("Unsupported provider: " + provider).asRuntimeException());
                return;
            }

            Long packageId = null;
            Long optionId = null;
            String campaignId = null;
            if (!prePriced) {
                try {
                    var ref = PaymentPackageRef.parseWithFallback(request.getDescription(), otherAttr);
                    if (ref.isComplete()) {
                        packageId = ref.packageId();
                        optionId = ref.optionId();
                        campaignId = ref.campaignId();
                    }
                } catch (Exception ignored) {
                    // fall through to amount validation
                }

                if (packageId != null && optionId != null) {
                    var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
                    amount = priceCurrency.amount;
                    currency = priceCurrency.currency != null ? priceCurrency.currency : currency;
                    otherAttr = PaymentPackageRef.encode(packageId, optionId, campaignId);
                }
            }

            if (amount == null || amount <= 0) {
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                        .withDescription("Amount must be resolved from package/option or provided as positive")
                        .asRuntimeException());
                return;
            }

            // Proto3 scalar: 0 means unset. Checkout must send the owning user so
            // GET /me/payments/order/{id} ownership checks do not 403 on null user_id.
            Long userId = request.getUserId() > 0 ? request.getUserId() : null;
            if (userId == null) {
                log.warn("CreatePayment missing user_id for orderId={}", request.getOrderId());
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

            EpointResponse epointResponse = integrationService.initiatePayment(paymentRequest, userId);

            // Pre-priced BF/promo checkouts already subtracted FitCoin from amount; persist
            // coins_used so SUCCESS spends the same units (preview total == bank charge).
            if (request.getCoinsUsed() > 0) {
                BigDecimal coins = BigDecimal.valueOf(request.getCoinsUsed()).setScale(2, RoundingMode.HALF_UP);
                paymentRepository.findByOrderId(request.getOrderId()).ifPresent(payment -> {
                    payment.setCoinsUsed(coins);
                    paymentRepository.save(payment);
                    log.info("[CreatePayment] Attached coinsUsed={} to orderId={}", coins, request.getOrderId());
                });
            }

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

    /**
     * TamKart (abb) / BolKart (bob) checkout from order-backend. Only pre-priced requests are accepted:
     * the amount already contains campaign discount + FitCoin, and re-quoting here would charge list price.
     * Package/option/campaign come from description/other_attr via {@link PaymentPackageRef}.
     */
    private void handleInstallmentProvider(CreatePaymentRequest request, String provider, boolean prePriced,
                                           String otherAttr,
                                           StreamObserver<CreatePaymentResponse> responseObserver) {
        if (!prePriced || request.getUserId() <= 0 || request.getAmount() <= 0) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(provider + " requires pre_priced, user_id and positive amount")
                    .asRuntimeException());
            return;
        }
        var ref = PaymentPackageRef.parseWithFallback(request.getDescription(), otherAttr);
        if (!ref.isComplete() || request.getInstallmentMonths() <= 0) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(provider + " requires packageId/optionId ref and installment_months")
                    .asRuntimeException());
            return;
        }

        BigDecimal coins = request.getCoinsUsed() > 0
                ? BigDecimal.valueOf(request.getCoinsUsed()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        String status;
        String redirectUrl;
        String transactionId;
        String message = "";
        try {
            if ("abb".equals(provider)) {
                var r = abbIntegrationService.initiateInstallmentPaymentPrePriced(
                        request.getUserId(), ref.packageId(), ref.optionId(), request.getInstallmentMonths(),
                        request.getAmount(), request.getCurrency(), request.getOrderId(), ref.campaignId(), coins);
                status = r.status();
                redirectUrl = r.redirectUrl();
                transactionId = r.orderId();
                message = r.message();
            } else {
                var r = bobIntegrationService.initiateInstallmentPaymentPrePriced(
                        request.getUserId(), ref.packageId(), ref.optionId(), request.getInstallmentMonths(),
                        request.getAmount(), request.getCurrency(), request.getOrderId(), ref.campaignId(), coins);
                status = "success";
                redirectUrl = r.getFormUrl();
                transactionId = r.getOrderId();
            }
        } catch (IllegalArgumentException e) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (az.fitnest.payment.exception.AbbMaintenanceException
                 | az.fitnest.payment.exception.BobMaintenanceException e) {
            // Surface as a normal error response so order-backend returns a clear failure, not a gRPC crash.
            log.warn("[CreatePayment] {} in maintenance: {}", provider, e.getMessage());
            responseObserver.onNext(CreatePaymentResponse.newBuilder()
                    .setStatus("error").setMessage(e.getMessage()).build());
            responseObserver.onCompleted();
            return;
        }

        responseObserver.onNext(CreatePaymentResponse.newBuilder()
                .setStatus(status != null ? status : "error")
                .setRedirectUrl(redirectUrl != null ? redirectUrl : "")
                .setTransactionId(transactionId != null ? transactionId : "")
                .setMessage(message != null ? message : "")
                .build());
        responseObserver.onCompleted();
    }

    /**
     * Apple Pay from v3 checkout. iOS pays through a WKWebView on the Epoint widget (not native
     * create/submit), so redirect_url carries a pre-priced widget URL that order-backend surfaces as
     * payment.paymentUrl. Pre-priced only: re-quoting here would drop the BF discount.
     * createApplePayPaymentPrePriced stays in the service but is no longer called from gRPC.
     */
    private void handleApplePayPrePriced(CreatePaymentRequest request, boolean prePriced, String otherAttr,
                                         StreamObserver<CreatePaymentResponse> responseObserver) {
        handleWalletWidgetPrePriced("apple_pay", request, prePriced, otherAttr, responseObserver);
    }

    /**
     * Google Pay from v3 checkout: same widget contract as {@link #handleApplePayPrePriced}.
     * createGooglePayPaymentPrePriced stays in the service but is no longer called from gRPC.
     */
    private void handleGooglePayPrePriced(CreatePaymentRequest request, boolean prePriced, String otherAttr,
                                          StreamObserver<CreatePaymentResponse> responseObserver) {
        handleWalletWidgetPrePriced("google_pay", request, prePriced, otherAttr, responseObserver);
    }

    /** Shared apple_pay/google_pay body: pre-priced Epoint widget URL -> redirect_url, widget txn -> transaction_id. */
    private void handleWalletWidgetPrePriced(String label, CreatePaymentRequest request, boolean prePriced,
                                             String otherAttr,
                                             StreamObserver<CreatePaymentResponse> responseObserver) {
        if (!prePriced || request.getUserId() <= 0 || request.getAmount() <= 0) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(label + " requires pre_priced, user_id and positive amount")
                    .asRuntimeException());
            return;
        }
        var ref = PaymentPackageRef.parseWithFallback(request.getDescription(), otherAttr);
        if (!ref.isComplete()) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(label + " requires packageId/optionId ref").asRuntimeException());
            return;
        }
        BigDecimal coins = request.getCoinsUsed() > 0
                ? BigDecimal.valueOf(request.getCoinsUsed()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        try {
            // campaignId rides in the package ref so renewal/assignment still sees the BF campaign.
            var widget = integrationService.createWidgetUrlPrePriced(
                    request.getUserId(), request.getOrderId(), request.getAmount(), request.getCurrency(),
                    PaymentPackageRef.encode(ref.packageId(), ref.optionId(), ref.campaignId()),
                    coins, false);
            responseObserver.onNext(CreatePaymentResponse.newBuilder()
                    .setStatus(widget.status() != null ? widget.status() : "error")
                    .setRedirectUrl(widget.widgetUrl() != null ? widget.widgetUrl() : "")
                    .setTransactionId(widget.transaction() != null ? widget.transaction() : "")
                    .setMessage(widget.message() != null ? widget.message() : "")
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /**
     * Epoint saved card from v3 checkout. Pre-priced only: legacy with-card re-quotes at list price and drops
     * the BF discount. status/redirect_url are passed through from Epoint (redirect_url is set when the bank
     * requires 3DS); transaction_id is the Epoint transaction.
     */
    private void handleSavedCardPrePriced(CreatePaymentRequest request, boolean prePriced, String otherAttr,
                                          StreamObserver<CreatePaymentResponse> responseObserver) {
        if (!prePriced || request.getUserId() <= 0 || request.getAmount() <= 0
                || request.getCardId() == null || request.getCardId().isBlank()) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("saved_card requires pre_priced, user_id, positive amount and card_id")
                    .asRuntimeException());
            return;
        }
        var ref = PaymentPackageRef.parseWithFallback(request.getDescription(), otherAttr);
        if (!ref.isComplete()) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("saved_card requires packageId/optionId ref").asRuntimeException());
            return;
        }
        BigDecimal coins = request.getCoinsUsed() > 0
                ? BigDecimal.valueOf(request.getCoinsUsed()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        // Same ",type:" suffix as legacy with-card; autopay is never enabled from BF checkout.
        String description = PaymentPackageRef.encode(ref.packageId(), ref.optionId(), ref.campaignId())
                + ",type:One-time payment";
        try {
            EpointResponse r = integrationService.executePayWithCardPrePriced(
                    request.getUserId(), request.getCardId(), request.getOrderId(), request.getAmount(),
                    request.getCurrency(), description, coins, false);
            responseObserver.onNext(CreatePaymentResponse.newBuilder()
                    .setStatus(r.status() != null ? r.status() : "error")
                    .setRedirectUrl(r.redirectUrl() != null ? r.redirectUrl() : "")
                    .setTransactionId(r.transaction() != null ? r.transaction() : "")
                    .setMessage(r.message() != null ? r.message() : "")
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void payWithCard(PayWithCardRequest request, StreamObserver<PayWithCardResponse> responseObserver) {
        log.info("Received gRPC PayWithCard request for userId: {}, cardId: {}, packageId: {}, optionId: {}",
                request.getUserId(), request.getCardId(), request.getPackageId(), request.getOptionId());

        try {
            // Auto-renewals always apply the full available FitNest Coin balance.
            EpointResponse epointResponse = integrationService.executeRenewalWithCard(
                    request.getUserId(),
                    request.getCardId(),
                    request.getPackageId(),
                    request.getOptionId());

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
