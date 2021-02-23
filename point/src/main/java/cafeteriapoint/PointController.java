package cafeteriapoint;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/points")
@RestController
public class PointController {

	@Autowired
	private PointRepository pointRepository;
	
	@PatchMapping("/cancelPoint")
	public void cancelPoint(@RequestBody OrderCanceled orderCanceled) {
		
//		try {
//            Thread.currentThread().sleep((long) (400 + Math.random() * 220));
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
		
		List<Point> points = pointRepository.findByPhoneNumber(orderCanceled.getPhoneNumber());
		int p = (int)(orderCanceled.getAmt() * 0.1);
		Point point = points.get(0);
		point.setPoint(point.getPoint() - p);
		
		if(point.getPoint() < 0) throw new RuntimeException("Point is not enough.");
		pointRepository.save(point);
    }
 }
