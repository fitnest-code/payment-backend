package az.fitnest.payment.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "coin_terms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CoinTerms extends BaseAuditableEntity {

    @Column(name = "html_content_az", nullable = false, columnDefinition = "TEXT")
    private String htmlContentAz = "";

    @Column(name = "html_content_en", nullable = false, columnDefinition = "TEXT")
    private String htmlContentEn = "";

    @Column(name = "html_content_ru", nullable = false, columnDefinition = "TEXT")
    private String htmlContentRu = "";
}
