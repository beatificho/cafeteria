package cafeteriapoint;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PointHistoryRepository extends CrudRepository<PointHistory, Long> {

    List<PointHistory> findByPaymentId(Long paymentId);
    List<PointHistory> findByOrderId(Long orderId);
    
    List<PointHistory> findByPhoneNumberOrderByCreatTime(String phoneNumber);

}