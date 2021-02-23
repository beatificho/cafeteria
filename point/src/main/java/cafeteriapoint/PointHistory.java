package cafeteriapoint;

import java.util.Date;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name="PointHistory_table")
public class PointHistory {

        @Id
        @GeneratedValue(strategy=GenerationType.AUTO)
        private Long id;
        private Long orderId;
        private Long paymentId;
        private Integer qty;
        private Integer amt;
        private Integer point;
        private String productName;
        private String type;
        private Integer totalPoint;
        private String phoneNumber;
        private Date createTime;


        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }
        public Long getPaymentId() {
            return paymentId;
        }

        public void setPaymentId(Long paymentId) {
            this.paymentId = paymentId;
        }
        public Integer getQty() {
            return qty;
        }

        public void setQty(Integer qty) {
            this.qty = qty;
        }
        public Integer getAmt() {
            return amt;
        }

        public void setAmt(Integer amt) {
            this.amt = amt;
        }
        public Integer getPoint() {
            return point;
        }

        public void setPoint(Integer point) {
            this.point = point;
        }
        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
        public Integer getTotalPoint() {
            return totalPoint;
        }

        public void setTotalPoint(Integer totalPoint) {
            this.totalPoint = totalPoint;
        }
        public Date getCreateTime() {
            return createTime;
        }

        public void setCreateTime(Date createTime) {
            this.createTime = createTime;
        }

		public String getPhoneNumber() {
			return phoneNumber;
		}

		public void setPhoneNumber(String phoneNumber) {
			this.phoneNumber = phoneNumber;
		}

}
