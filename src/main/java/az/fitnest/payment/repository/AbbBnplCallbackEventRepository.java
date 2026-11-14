package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.AbbBnplCallbackEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AbbBnplCallbackEventRepository extends JpaRepository<AbbBnplCallbackEvent, Long> {

    Optional<AbbBnplCallbackEvent> findByCallbackId(String callbackId);

    List<AbbBnplCallbackEvent> findTop50ByProcessedFalseOrderByReceivedAtAsc();

    long countByProcessedFalse();
}
