package az.fitnest.payment.service.impl;

import az.fitnest.payment.dto.coin.CoinTermsAdminRequest;
import az.fitnest.payment.dto.coin.CoinTermsAdminResponse;
import az.fitnest.payment.dto.coin.CoinTermsResponse;
import az.fitnest.payment.model.entity.CoinTerms;
import az.fitnest.payment.repository.CoinTermsRepository;
import az.fitnest.payment.service.CoinTermsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CoinTermsServiceImpl implements CoinTermsService {

    private final CoinTermsRepository coinTermsRepository;

    @Override
    @Transactional(readOnly = true)
    public CoinTermsAdminResponse getAdminTerms() {
        return coinTermsRepository.findFirstByOrderByIdAsc()
                .map(this::toAdminResponse)
                .orElseGet(() -> CoinTermsAdminResponse.builder()
                        .htmlContentAz("")
                        .htmlContentEn("")
                        .htmlContentRu("")
                        .build());
    }

    @Override
    @Transactional
    public CoinTermsAdminResponse saveAdminTerms(CoinTermsAdminRequest request) {
        CoinTerms terms = coinTermsRepository.findFirstByOrderByIdAsc().orElseGet(CoinTerms::new);
        terms.setHtmlContentAz(nullToEmpty(request.getHtmlContentAz()));
        terms.setHtmlContentEn(nullToEmpty(request.getHtmlContentEn()));
        terms.setHtmlContentRu(nullToEmpty(request.getHtmlContentRu()));
        return toAdminResponse(coinTermsRepository.save(terms));
    }

    @Override
    @Transactional
    public void deleteTerms() {
        coinTermsRepository.findFirstByOrderByIdAsc().ifPresent(coinTermsRepository::delete);
    }

    @Override
    @Transactional(readOnly = true)
    public CoinTermsResponse getLocalizedTerms(String language) {
        CoinTerms terms = coinTermsRepository.findFirstByOrderByIdAsc().orElse(null);
        if (terms == null) {
            return CoinTermsResponse.builder().htmlContent("").build();
        }
        String lang = normalizeLanguage(language);
        String content = switch (lang) {
            case "EN" -> firstNonBlank(terms.getHtmlContentEn(), terms.getHtmlContentAz(), terms.getHtmlContentRu());
            case "RU" -> firstNonBlank(terms.getHtmlContentRu(), terms.getHtmlContentAz(), terms.getHtmlContentEn());
            default -> firstNonBlank(terms.getHtmlContentAz(), terms.getHtmlContentEn(), terms.getHtmlContentRu());
        };
        return CoinTermsResponse.builder().htmlContent(content).build();
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "AZ";
        }
        String primary = language.split(",")[0].trim();
        if (primary.contains(";")) {
            primary = primary.substring(0, primary.indexOf(';')).trim();
        }
        String lang = primary.toUpperCase();
        if (lang.contains("-")) {
            lang = lang.substring(0, lang.indexOf('-'));
        }
        if (lang.length() > 2) {
            lang = lang.substring(0, 2);
        }
        return switch (lang) {
            case "EN", "RU", "AZ" -> lang;
            default -> "AZ";
        };
    }

    private CoinTermsAdminResponse toAdminResponse(CoinTerms terms) {
        return CoinTermsAdminResponse.builder()
                .htmlContentAz(nullToEmpty(terms.getHtmlContentAz()))
                .htmlContentEn(nullToEmpty(terms.getHtmlContentEn()))
                .htmlContentRu(nullToEmpty(terms.getHtmlContentRu()))
                .build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
