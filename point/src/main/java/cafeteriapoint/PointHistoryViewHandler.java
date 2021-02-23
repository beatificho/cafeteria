package cafeteriapoint;

import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.data.repository.Repository;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import cafeteriapoint.config.kafka.KafkaProcessor;

@Service
public class PointHistoryViewHandler {


    @Autowired
    private PointHistoryRepository pointHistoryRepository;
    
    @Autowired
    private PointRepository pointRepository;


    @StreamListener(KafkaProcessor.INPUT)
    public void whenPaymentApproved_then_CREATE_1 (@Payload PaymentApproved paymentApproved) {
        try {
            if (paymentApproved.isMe()) {
            	
            	List<Point> points = pointRepository.findByPhoneNumber(paymentApproved.getPhoneNumber());
            	int p = (int)(paymentApproved.getAmt() * 0.1);
            	
            	Point point = null;
            	if(points.size() == 0) {
            		point = new Point();
            		point.setPhoneNumber(paymentApproved.getPhoneNumber());
            		point.setPoint(p);
            		pointRepository.save(point);
            	} else {
            		point = points.get(0);
            		point.setPoint(point.getPoint() + p);
	        		pointRepository.save(point);
            	}
            	
                // view 객체 생성
                PointHistory history = new PointHistory();
                // view 객체에 이벤트의 Value 를 set 함
                history.setOrderId(paymentApproved.getOrderId());
                history.setPaymentId(paymentApproved.getId());
                history.setAmt(paymentApproved.getAmt());
                history.setPoint(p);
                history.setType("Order");
                history.setTotalPoint(point.getPoint());
                history.setPhoneNumber(paymentApproved.getPhoneNumber());
                history.setCreateTime(new Date());
                // view 레파지 토리에 save
                pointHistoryRepository.save(history);
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    
    @StreamListener(KafkaProcessor.INPUT)
    public void whenPaymentCanceled_then_CREATE_2 (@Payload PaymentCanceled paymentCanceled) {
        try {
            if (paymentCanceled.isMe()) {
            	
            	List<Point> points = pointRepository.findByPhoneNumber(paymentCanceled.getPhoneNumber());
            	int p = (int)(paymentCanceled.getAmt() * 0.1);
            	
            	Point point = points.get(0);
            	
        		List<PointHistory> histories = pointHistoryRepository.findByPaymentId(paymentCanceled.getId());
        		
        		PointHistory beforeHistory = histories.get(0);
        		
        		// view 객체 생성
                PointHistory history = new PointHistory();
                // view 객체에 이벤트의 Value 를 set 함
                history.setOrderId(paymentCanceled.getOrderId());
                history.setPaymentId(paymentCanceled.getId());
                history.setAmt(paymentCanceled.getAmt());
                history.setPhoneNumber(paymentCanceled.getPhoneNumber());
                history.setPoint(p * -1);
                history.setType("Cancel");
                history.setProductName(beforeHistory.getProductName());
                history.setQty(beforeHistory.getQty());
                history.setCreateTime(new Date());
                history.setTotalPoint(point.getPoint());
                // view 레파지 토리에 save
                pointHistoryRepository.save(history);
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }


    @StreamListener(KafkaProcessor.INPUT)
    public void whenOrdered_then_UPDATE_1(@Payload Ordered ordered) {
        try {
            if (ordered.isMe()) {
                // view 객체 조회
                List<PointHistory> histories = pointHistoryRepository.findByOrderId(ordered.getId());
                for(PointHistory history : histories){
                    // view 객체에 이벤트의 eventDirectValue 를 set 함
                	history.setProductName(ordered.getProductName());
                	history.setQty(ordered.getQty());
                    // view 레파지 토리에 save
                	pointHistoryRepository.save(history);
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

}