package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.BolOdeApplication;
import az.fitnest.payment.model.enums.BolOdeApplicationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface BolOdeApplicationRepository
        extends JpaRepository<BolOdeApplication, Long>, JpaSpecificationExecutor<BolOdeApplication> {

    /** Backs the "BÖ-{n}" display id; a DB sequence is gap-tolerant and race-free. */
    @Query(value = "SELECT nextval('bol_ode_display_seq')", nativeQuery = true)
    long nextDisplaySeq();

    Optional<BolOdeApplication> findFirstByUserIdAndStatusInOrderByCreatedAtDesc(
            Long userId, Collection<BolOdeApplicationStatus> statuses);

    Optional<BolOdeApplication> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    java.util.List<BolOdeApplication> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<BolOdeApplication> findByDisplayId(String displayId);

    Optional<BolOdeApplication> findByPaymentReference(String paymentReference);

    /** Row lock so concurrent cancel / admin PATCH cannot interleave status transitions. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM BolOdeApplication a WHERE a.id = :id")
    Optional<BolOdeApplication> findByIdForUpdate(@Param("id") Long id);
}
