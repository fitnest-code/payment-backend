package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.BolOdeApplicationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BolOdeApplicationEventRepository extends JpaRepository<BolOdeApplicationEvent, Long> {

    List<BolOdeApplicationEvent> findByApplicationIdOrderByCreatedAtAscIdAsc(Long applicationId);
}
