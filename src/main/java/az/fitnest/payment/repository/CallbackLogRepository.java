package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.CallbackLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CallbackLogRepository extends JpaRepository<CallbackLog, Long> {
}
