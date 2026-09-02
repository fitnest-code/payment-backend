package az.fitnest.payment.service;

import az.fitnest.payment.dto.coin.CoinTermsAdminRequest;
import az.fitnest.payment.dto.coin.CoinTermsAdminResponse;
import az.fitnest.payment.dto.coin.CoinTermsResponse;

public interface CoinTermsService {
    CoinTermsAdminResponse getAdminTerms();

    CoinTermsAdminResponse saveAdminTerms(CoinTermsAdminRequest request);

    void deleteTerms();

    CoinTermsResponse getLocalizedTerms(String language);
}
