# cafeteria
# Table of contents

- [음료주문](#---)
  - [서비스 시나리오](#서비스-시나리오)
  - [분석/설계](#분석설계)
  - [구현:](#구현-)
    - [DDD 의 적용](#ddd-의-적용)
    - [API Gateway](#API-GATEWAY)
    - [폴리글랏 퍼시스턴스](#폴리글랏-퍼시스턴스)
    - [폴리글랏 프로그래밍](#폴리글랏-프로그래밍)
    - [동기식 호출 과 Fallback 처리](#동기식-호출-과-Fallback-처리)
    - [비동기식 호출 과 Eventual Consistency](#비동기식-호출--시간적-디커플링--장애격리--최종-eventual-일관성-테스트)
    - [Saga Pattern / 보상 트랜잭션](#Saga-Pattern--보상-트랜잭션)
    - [CQRS / Meterialized View](#CQRS--Meterialized-View)
  - [운영](#운영)
    - [Liveness / Readiness 설정](#Liveness--Readiness-설정)
    - [CI/CD 설정](#cicd-설정)
    - [셀프힐링](#셀프힐링)
    - [동기식 호출 / 서킷 브레이킹 / 장애격리](#동기식-호출--서킷-브레이킹--장애격리)
    - [오토스케일 아웃](#오토스케일-아웃)
    - [무정지 재배포](#무정지-재배포)
    - [모니터링](#모니터링)
    - [Persistence Volum Claim](#Persistence-Volum-Claim)
    - [ConfigMap / Secret](#ConfigMap--Secret)

# 서비스 시나리오

음료주문

기능적 요구사항
1. 고객이 음료를 주문한다
1. 고객이 결제한다
1. 결제가 되면 주문 내역이 바리스타에게 전달된다
1. 바리스타는 주문내역을 확인하여 음료를 접수하고 제조한다.
1. 고객이 주문을 취소할 수 있다
1. 주문이 취소되면 음료를 취소한다.
2. 음료가 취소되면 결제를 취소한다.
3. 고객이 주문상태를 중간중간 조회한다.
4. 주문상태가 바뀔 때 마다 카톡으로 알림을 보낸다.

비기능적 요구사항
1. 트랜잭션
    1. 결제가 되지 않은 주문건은 아예 거래가 성립되지 않아야 한다  Sync 호출
    2. 바리스타가 접수하여 제조를 시작한 주문은 취소되지 않아야 한다. Saga(보상 트랜잭션)
1. 장애격리
    1. 음료제조 기능이 수행되지 않더라도 주문은 받을 수 있어야 한다  Async (event-driven), Eventual Consistency
    1. 결제시스템이 과중되면 사용자를 잠시동안 받지 않고 결제를 잠시후에 하도록 유도한다  Circuit breaker, fallback
1. 성능
    1. 고객이 자주 확인할 수 있는 주문상태를 마이페이지(프론트엔드)에서 확인할 수 있어야 한다  CQRS
    1. 주문상태가 바뀔때마다 카톡 등으로 알림을 줄 수 있어야 한다  Event driven

# 분석설계

1. Event Storming 모델
![image](https://user-images.githubusercontent.com/75828964/108632009-19273c80-74b0-11eb-91d2-d0c2b23f647a.png)
1. 헥사고날 아키텍처 다이어그램 도출
![image](https://user-images.githubusercontent.com/75828964/106765217-e8f03900-667b-11eb-8f19-10dc4756dc4b.png)

# 구현:

분석/설계 단계에서 도출된 헥사고날 아키텍처에 따라, 각 BC별로 대변되는 마이크로 서비스들을 스프링부트로 구현하였다. 구현한 각 서비스를 로컬에서 실행하는 방법은 아래와 같다 (각자의 포트넘버는 8081 ~ 808n
이다)

```
cd order
mvn spring-boot:run

cd payment
mvn spring-boot:run 

cd drink
mvn spring-boot:run  

cd customercneter
mvn spring-boot:run
```

## DDD 의 적용

- 각 서비스내에 도출된 핵심 Aggregate Root 객체를 Entity 로 선언하였다

```
package cafeteria;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PostPersist;
import javax.persistence.PostUpdate;
import javax.persistence.Table;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;

import cafeteria.external.Payment;
import cafeteria.external.PaymentService;

@Entity
@Table(name="ORDER_MANAGEMENT")
public class Order {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private String phoneNumber;
    private String productName;
    private Integer qty;
    private Integer amt;
    private String status = "Ordered";

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
    
    public String getPhoneNumber() {
    	return phoneNumber;
    }
    public void setPhoneNumber(String phoneNumber) {
    	this.phoneNumber = phoneNumber;
    }
    
    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
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
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

```
- Entity Pattern 과 Repository Pattern 을 적용하여 JPA 를 통하여 다양한 데이터소스 유형 (RDB or NoSQL) 에 대한 별도의 처리가 없도록 데이터 접근 어댑터를 자동 생성하기 위하여 Spring Data REST 의 RestRepository 를 적용하였다
```
package cafeteria;

import org.springframework.data.repository.PagingAndSortingRepository;

public interface OrderRepository extends PagingAndSortingRepository<Order, Long>{
}
```
- 적용 후 REST API 의 테스트
```
# order 서비스의 주문처리
root@siege-5b99b44c9c-8qtpd:/# http http://order:8080/orders phoneNumber="01012345678" productName="coffee" qty=3 amt=5000
HTTP/1.1 201 
Content-Type: application/json;charset=UTF-8
Date: Sat, 20 Feb 2021 14:20:20 GMT
Location: http://order:8080/orders/1
Transfer-Encoding: chunked
{
    "_links": {
        "order": {
            "href": "http://order:8080/orders/1"
        },
        "self": {
            "href": "http://order:8080/orders/1"
        }
    },
    "amt": 5000,
    "createTime": "2021-02-20T14:20:17.783+0000",
    "phoneNumber": "01012345678",
    "productName": "coffee",
    "qty": 3,
    "status": "Ordered"
}

# payment 조회
root@siege-5b99b44c9c-8qtpd:/# http http://payment:8080/payments/search/findByOrderId?orderId=1 
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Sat, 20 Feb 2021 14:21:21 GMT
Transfer-Encoding: chunked
{
    "_embedded": {
        "payments": [
            {
                "_links": {
                    "payment": {
                        "href": "http://payment:8080/payments/1"
                    },
                    "self": {
                        "href": "http://payment:8080/payments/1"
                    }
                },
                "amt": 5000,
                "createTime": "2021-02-20T14:20:19.020+0000",
                "orderId": 1,
                "phoneNumber": "01012345678",
                "status": "PaymentApproved"
            }
        ]
    },
    "_links": {
        "self": {
            "href": "http://payment:8080/payments/search/findByOrderId?orderId=1"
        }
    }
}

# drink 서비스의 접수처리
root@siege-5b99b44c9c-8qtpd:/# http patch http://drink:8080/drinks/1 status="Receipted"
HTTP/1.1 200 
Content-Type: application/json;charset=UTF-8
Date: Sat, 20 Feb 2021 14:32:03 GMT
Transfer-Encoding: chunked
{
    "_links": {
        "drink": {
            "href": "http://drink:8080/drinks/1"
        },
        "self": {
            "href": "http://drink:8080/drinks/1"
        }
    },
    "createTime": "2021-02-20T14:29:13.533+0000",
    "orderId": 1,
    "phoneNumber": "01012345678",
    "productName": "coffee",
    "qty": 3,
    "status": "Receipted"
}

# customercenter 서비스의 상태확인
root@siege-5b99b44c9c-8qtpd:/# http http://customercenter:8080/mypages/search/findByPhoneNumber?phoneNumber="01012345678"
HTTP/1.1 200 
Content-Type: application/json;charset=UTF-8
Date: Sat, 20 Feb 2021 14:36:15 GMT
Transfer-Encoding: chunked
[
    {
        "amt": 5000,
        "id": 1,
        "orderId": 1,
        "phoneNumber": "01012345678",
        "productName": "coffee",
        "qty": 3,
        "status": "Ordered"
    }
]
```

## API Gateway
API Gateway를 통하여 동일 진입점으로 진입하여 각 마이크로 서비스를 접근할 수 있다.
외부에서 접근을 위하여 Gateway의 Service는 LoadBalancer Type으로 생성했다.

```
# application.yml

spring:
  profiles: docker
  cloud:
    gateway:
      routes:
        - id: order
          uri: http://order:8080
          predicates:
            - Path=/orders/** 
        - id: payment
          uri: http://payment:8080
          predicates:
            - Path=/payments/** 
        - id: drink
          uri: http://drink:8080
          predicates:
            - Path=/drinks/**,/orderinfos/**
        - id: customercenter
          uri: http://customercenter:8080
          predicates:
            - Path= /mypages/**

# service.yaml
apiVersion: v1
kind: Service
metadata:
  name: gateway
  labels:
    app: gateway
spec:
  type: LoadBalancer
  ports:
    - port: 8080
      targetPort: 8080
  selector:
    app: gateway
```


- order
![image](https://user-images.githubusercontent.com/76020485/108672134-e53a2e80-7524-11eb-8008-ebcfbd8e9cbe.PNG)
- payment
![image](https://user-images.githubusercontent.com/76020485/108672136-e5d2c500-7524-11eb-824e-4066bb87376b.PNG)
- drink
![image](https://user-images.githubusercontent.com/76020485/108672138-e66b5b80-7524-11eb-9c27-cf2089f4ac08.PNG)
- customercenter
![image](https://user-images.githubusercontent.com/76020485/108672131-e4a19800-7524-11eb-894e-832ed6519b53.PNG)

## 폴리글랏 퍼시스턴스

고객센터(customercenter)는 RDB 보다는 Document DB / NoSQL 계열의 데이터베이스인 Mongo DB 를 사용하기로 하였다. 이를 위해 customercenter의 선언에는 @Entity 가 아닌 @Document로 변경 되었으며, 기존의 Entity Pattern 과 Repository Pattern 적용과 데이터베이스 제품의 설정 (application.yml)과 아래 채번기능 개발 만으로 MongoDB 에 부착시켰다
```
#Mypage.scala

@Document
class Mypage {
  
  @Id
  @BeanProperty
  var id :Long = 0L
  :
```
MongoDB는 Sequence가 지원되지 않아 별도 Collection을 통해서 id sequence를 생성했다.
```
# DatabseSequence.scala
abstract class Sequence {
  var seq :Long
}
case class InitialSequence(var seq : Long) extends Sequence

@Document(collection = "database_sequences")
class DatabaseSequence extends Sequence {

  
  @BeanProperty
  @Id
  var id: String = null
  
  @BeanProperty
  var seq :Long = 0L

}

# MypageViewHandler.scala

def generateSequence (seqName :String) :Long = {
    val query :Query = new Query(Criteria.where("_id").is(seqName));
    val options :FindAndModifyOptions = new FindAndModifyOptions().returnNew(true).upsert(true)
    val update :Update = new Update().inc("seq",1)
    
    val sequence :Option[DatabaseSequence] = Option(mongoOperations.findAndModify(query, update, options, classOf[DatabaseSequence]))
    sequence.getOrElse(InitialSequence(1L)).seq
}
  
  @StreamListener(KafkaProcessor.INPUT)
  def whenOrdered_then_CREATE_1(@Payload ordered :Ordered) {
    try {
      if (ordered.isMe()) {
        
        val mypage :Mypage = new Mypage()
        mypage.id = generateSequence(Mypage.SEQUENCE_NAME)
	:

#pom.xml

<dependencies>
:
    <dependency>
	<groupId>org.springframework.boot</groupId>
	<artifactId>spring-boot-starter-data-mongodb</artifactId>
    </dependency>
:
</dependencies>

```
## 폴리글랏 프로그래밍

고객관리 서비스(customercenter)의 시나리오인 주문상태 변경에 따라 고객에게 카톡메시지 보내는 기능의 구현 파트는 해당 팀이 scala를 이용하여 구현하기로 하였다. 해당 Scala 구현체는 각 이벤트를 수신하여 처리하는 Kafka consumer 로 구현되었고 코드는 다음과 같다:
```
import org.springframework.messaging.SubscribableChannel
import org.springframework.cloud.stream.annotation.Output
import org.springframework.cloud.stream.annotation.Input
import org.springframework.messaging.MessageChannel

object KafkaProcessor {
  final val INPUT = "event-in"
  final val OUTPUT = "event-out"
}

trait KafkaProcessor {

  @Input(KafkaProcessor.INPUT)
  def inboundTopic() :SubscribableChannel

  @Output(KafkaProcessor.OUTPUT)
  def outboundTopic() :MessageChannel
}

 # 카톡호출 API
import org.springframework.stereotype.Component

@Component
class KakaoServiceImpl extends KakaoService {
  
	override def sendKakao(message :KakaoMessage) {
		logger.info(s"\nTo. ${message.phoneNumber}\n${message.message}\n")
	}
}

```

## 동기식 호출 과 Fallback 처리

분석단계에서의 조건 중 하나로 주문(order)->결제(payment) 간의 호출은 동기식 일관성을 유지하는 트랜잭션으로 처리하기로 하였다. 호출 프로토콜은 이미 앞서 Rest Repository 에 의해 노출되어있는 REST 서비스를 FeignClient 를 이용하여 호출하도록 한다. 

- 결제서비스를 호출하기 위하여 Stub과 (FeignClient) 를 이용하여 Service 대행 인터페이스 (Proxy) 를 구현 

```
# (payment) PaymentService.java

package cafeteria.external;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@FeignClient(name="payment", url="${feign.client.payment.url}")
public interface PaymentService {

    @RequestMapping(method= RequestMethod.POST, path="/payments")
    public void pay(@RequestBody Payment payment);

}
```

- 주문을 받은 직후(@PostPersist) 결제를 요청하도록 처리
```
# Order.java (Entity)

    @PostPersist
    public void onPostPersist(){
        :

        Payment payment = new Payment();
        payment.setOrderId(this.id);
        payment.setPhoneNumber(this.phoneNumber);
        payment.setAmt(this.amt);
        
        OrderApplication.applicationContext.getBean(PaymentService.class).pay(payment);


    }
```

- 동기식 호출에서는 호출 시간에 따른 타임 커플링이 발생하며, 결제 시스템이 장애가 나면 주문도 못받는다는 것을 확인:


```
# 결제 (payment) 서비스를 잠시 내려놓음
$ kubectl delete deploy payment
deployment.apps "payment" deleted

#주문처리

root@siege-5b99b44c9c-8qtpd:/# http http://order:8080/orders phoneNumber="01012345679" productName="coffee" qty=3 amt=5000
HTTP/1.1 500 
Connection: close
Content-Type: application/json;charset=UTF-8
Date: Sat, 20 Feb 2021 14:39:23 GMT
Transfer-Encoding: chunked
{
    "error": "Internal Server Error",
    "message": "Could not commit JPA transaction; nested exception is javax.persistence.RollbackException: Error while committing the transaction",
    "path": "/orders",
    "status": 500,
    "timestamp": "2021-02-20T14:39:23.185+0000"
}

#결제서비스 재기동
$ kubectl apply -f deployment.yml
deployment.apps/payment created

#주문처리

root@siege-5b99b44c9c-8qtpd:/# http http://order:8080/orders phoneNumber="01012345679" productName="coffee" qty=3 amt=5000
HTTP/1.1 201 
Content-Type: application/json;charset=UTF-8
Date: Sat, 20 Feb 2021 14:51:42 GMT
Location: http://order:8080/orders/6
Transfer-Encoding: chunked

{
    "_links": {
        "order": {
            "href": "http://order:8080/orders/6"
        },
        "self": {
            "href": "http://order:8080/orders/6"
        }
    },
    "amt": 5000,
    "createTime": "2021-02-20T14:51:40.580+0000",
    "phoneNumber": "01012345679",
    "productName": "coffee",
    "qty": 3,
    "status": "Ordered"
}
```
- 또한 과도한 요청시에 서비스 장애가 도미노 처럼 벌어질 수 있다. (서킷브레이커, 폴백 처리는 운영단계에서 설명한다.)



## 비동기식 호출 / 시간적 디커플링 / 장애격리 / 최종 (Eventual) 일관성 테스트

결제가 이루어진 후에 상점시스템으로 이를 알려주는 행위는 동기식이 아니라 비 동기식으로 처리하여 상점 시스템의 처리를 위하여 결제주문이 블로킹 되지 않아도록 처리한다.
 
- 이를 위하여 결제이력에 기록을 남긴 후에 곧바로 결제승인이 되었다는 도메인 이벤트를 카프카로 송출한다(Publish)
 
```
package cafeteria;

@Entity
@Table(name="Payment")
public class Payment {

 :
    @PostPersist
    public void onPostPersist(){
        PaymentApproved paymentApproved = new PaymentApproved();
        BeanUtils.copyProperties(this, paymentApproved);
        paymentApproved.publishAfterCommit();

    }

}
```
- 음료 서비스에서는 결제승인 이벤트에 대해서 이를 수신하여 자신의 정책을 처리하도록 PolicyHandler 를 구현한다:

```
package cafeteria;

:

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverPaymentApproved_(@Payload PaymentApproved paymentApproved){

        if(paymentApproved.isMe()){
            System.out.println("##### listener  : " + paymentApproved.toJson());
            
            Drink drink = new Drink();
            drink.setOrderId(paymentApproved.getOrderId());
            drink.setStatus(paymentApproved.getStatus());
            drinkRepository.save(drink);
            
        }
    }

```
Replica를 추가했을 때 중복없이 수신할 수 있도록 서비스별 Kafka Group을 동일하게 지정했다.
```
spring:
  cloud:
    stream:
      bindings:
        event-in:
          group: drink
          destination: cafeteria
          contentType: application/json
        :
```
실제 구현에서 카톡은 화면에 출력으로 대체하였다.
  
```    
  @StreamListener(KafkaProcessor.INPUT)
  def whenReceipted_then_UPDATE_3(@Payload made :Made) {
    try {
      if (made.isMe()) {
        
        val message :KakaoMessage = new KakaoMessage()
        message.phoneNumber = made.phoneNumber
        message.message = s"""Your Order is ${made.status}\nCome and Take it, Please!"""
        kakaoService.sendKakao(message)
      }
    } catch {
      case e :Exception => e.printStackTrace()
    }
  }

@Component
class KakaoServiceImpl extends KakaoService {
  
	override def sendKakao(message :KakaoMessage) {
		logger.info(s"\nTo. ${message.phoneNumber}\n${message.message}\n")
	}
}

```

음료 시스템은 주문/결제와 완전히 분리되어있으며, 이벤트 수신에 따라 처리되기 때문에, 음료시스템이 유지보수로 인해 잠시 내려간 상태라도 주문을 받는데 문제가 없다:
```
# 음료 서비스 (drink) 를 잠시 내려놓음
$ kubectl delete deploy drink
deployment.apps "drink" deleted

#주문처리
root@siege-5b99b44c9c-8qtpd:/# http http://order:8080/orders phoneNumber="01012345679" productName="coffee" qty=3 amt=5000
HTTP/1.1 201 
Content-Type: application/json;charset=UTF-8
Date: Sat, 20 Feb 2021 14:53:25 GMT
Location: http://order:8080/orders/7
Transfer-Encoding: chunked

{
    "_links": {
        "order": {
            "href": "http://order:8080/orders/7"
        },
        "self": {
            "href": "http://order:8080/orders/7"
        }
    },
    "amt": 5000,
    "createTime": "2021-02-20T14:53:25.115+0000",
    "phoneNumber": "01012345679",
    "productName": "coffee",
    "qty": 3,
    "status": "Ordered"
}
#음료 서비스 기동
kubectl apply -f deployment.yml
deployment.apps/drink created

#음료등록 확인

root@siege-5b99b44c9c-8qtpd:/# http http://drink:8080/drinks/search/findByOrderId?orderId=7
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Sat, 20 Feb 2021 14:54:14 GMT
Transfer-Encoding: chunked

{
    "_embedded": {
        "drinks": [
            {
                "_links": {
                    "drink": {
                        "href": "http://drink:8080/drinks/4"
                    },
                    "self": {
                        "href": "http://drink:8080/drinks/4"
                    }
                },
                "createTime": "2021-02-20T14:53:25.194+0000",
                "orderId": 7,
                "phoneNumber": "01012345679",
                "productName": "coffee",
                "qty": 3,
                "status": "PaymentApproved"
            }
        ]
    },
    "_links": {
        "self": {
            "href": "http://drink:8080/drinks/search/findByOrderId?orderId=7"
        }
    }
}

```


## Saga Pattern / 보상 트랜잭션

음료 주문 취소는 바리스타가 음료 접수하기 전에만 취소가 가능하다.
음료 접수 후에 취소할 경우 보상트랜재션을 통하여 취소를 원복한다.
음료 주문 취소는 Saga Pattern으로 만들어져 있어 바리스타가 음료를 이미 접수하였을 경우 취소실패를 Event로 publish하고
Order 서비스에서 취소실패 Event를 Subscribe하여 주문취소를 원복한다.
```
# 주문
root@siege-5b99b44c9c-8qtpd:/# http http://order:8080/orders/5
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Sat, 20 Feb 2021 08:58:19 GMT
Transfer-Encoding: chunked
{
    "_links": {
        "order": {
            "href": "http://order:8080/orders/5"
        },
        "self": {
            "href": "http://order:8080/orders/5"
        }
    },
    "amt": 100,
    "createTime": "2021-02-20T08:51:17.441+0000",
    "phoneNumber": "01033132570",
    "productName": "coffee",
    "qty": 2,
    "status": "Ordered"
}

# 결제 상태 확인 
root@siege-5b99b44c9c-8qtpd:/# http http://payment:8080/payments/search/findByOrderId?orderId=5
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Sat, 20 Feb 2021 08:58:54 GMT
Transfer-Encoding: chunked

{
    "_embedded": {
        "payments": [
            {
                "_links": {
                    "payment": {
                        "href": "http://payment:8080/payments/5"
                    },
                    "self": {
                        "href": "http://payment:8080/payments/5"
                    }
                },
                "amt": 100,
                "createTime": "2021-02-20T08:51:17.452+0000",
                "orderId": 5,
                "phoneNumber": "01033132570",
                "status": "PaymentApproved"
            }
        ]
    },
    "_links": {
        "self": {
            "href": "http://payment:8080/payments/search/findByOrderId?orderId=5"
        }
    }
}

# 음료 상태 확인
root@siege-5b99b44c9c-8qtpd:/# http http://drink:8080/drinks/search/findByOrderId?orderId=5                              
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Sat, 20 Feb 2021 08:52:14 GMT
Transfer-Encoding: chunked

{
    "_embedded": {
        "drinks": [
            {
                "_links": {
                    "drink": {
                        "href": "http://drink:8080/drinks/5"
                    },
                    "self": {
                        "href": "http://drink:8080/drinks/5"
                    }
                },
                "createTime": "2021-02-20T08:51:17.515+0000",
                "orderId": 5,
                "phoneNumber": "01033132570",
                "productName": "coffee",
                "qty": 2,
                "status": "PaymentApproved"
            }
        ]
    },
    "_links": {
        "self": {
            "href": "http://drink:8080/drinks/search/findByOrderId?orderId=5"
        }
    }
}

# 음료 접수
root@siege-5b99b44c9c-8qtpd:/# http patch http://drink:8080/drinks/5 status="Receipted"
HTTP/1.1 200 
Content-Type: application/json;charset=UTF-8
Date: Sat, 20 Feb 2021 08:53:29 GMT
Transfer-Encoding: chunked
{
    "_links": {
        "drink": {
            "href": "http://drink:8080/drinks/5"
        },
        "self": {
            "href": "http://drink:8080/drinks/5"
        }
    },
    "createTime": "2021-02-20T08:51:17.515+0000",
    "orderId": 5,
    "phoneNumber": "01033132570",
    "productName": "coffee",
    "qty": 2,
    "status": "Receipted"
}

# 주문 취소
root@siege-5b99b44c9c-8qtpd:/# http patch http://order:8080/orders/5 status="OrderCanceled"
HTTP/1.1 200 
Content-Type: application/json;charset=UTF-8
Date: Sat, 20 Feb 2021 08:54:29 GMT
Transfer-Encoding: chunked
{
    "_links": {
        "order": {
            "href": "http://order:8080/orders/5"
        },
        "self": {
            "href": "http://order:8080/orders/5"
        }
    },
    "amt": 100,
    "createTime": "2021-02-20T08:51:17.441+0000",
    "phoneNumber": "01033132570",
    "productName": "coffee",
    "qty": 2,
    "status": "OrderCanceled"
}

# 주문 조회
root@siege-5b99b44c9c-8qtpd:/# http http://order:8080/orders/5
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Sat, 20 Feb 2021 09:07:49 GMT
Transfer-Encoding: chunked

{
    "_links": {
        "order": {
            "href": "http://order:8080/orders/5"
        },
        "self": {
            "href": "http://order:8080/orders/5"
        }
    },
    "amt": 100,
    "createTime": "2021-02-20T09:07:24.114+0000",
    "phoneNumber": "01033132570",
    "productName": "coffee",
    "qty": 2,
    "status": "Ordered"
}

# 결제 상태 확인
root@siege-5b99b44c9c-8qtpd:/# http http://payment:8080/payments/5
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Sat, 20 Feb 2021 09:21:59 GMT
Transfer-Encoding: chunked

{
    "_links": {
        "payment": {
            "href": "http://payment:8080/payments/5"
        },
        "self": {
            "href": "http://payment:8080/payments/5"
        }
    },
    "amt": 100,
    "createTime": "2021-02-20T08:51:17.452+0000",
    "orderId": 5,
    "phoneNumber": "01033132570",
    "status": "PaymentApproved"
}

# 음료 상태 확인
root@siege-5b99b44c9c-8qtpd:/# http http://drink:8080/drinks/5
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Sat, 20 Feb 2021 09:22:47 GMT
Transfer-Encoding: chunked

{
    "_links": {
        "drink": {
            "href": "http://drink:8080/drinks/5"
        },
        "self": {
            "href": "http://drink:8080/drinks/5"
        }
    },
    "createTime": "2021-02-20T08:51:17.515+0000",
    "orderId": 5,
    "phoneNumber": "01033132570",
    "productName": "coffee",
    "qty": 2,
    "status": "Receipted"
}

```

CancelFailed Event는 Customercenter 서비스에서도 subscribe하여 카카오톡으로 취소 실패된 내용을 전달한다.
```
2021-02-20 09:08:42.668  INFO 1 --- [container-0-C-1] cafeteria.external.KakaoServiceImpl      :
To. 01033132570
Your Order is already started. You cannot cancel!!
```

## CQRS / Meterialized View
CustomerCenter의 Mypage를 구현하여 Order 서비스, Payment 서비스, Drink 서비스의 데이터를 Composite서비스나 DB Join없이 조회할 수 있다.
```
root@siege-5b99b44c9c-8qtpd:/# http http://customercenter:8080/mypages/search/findByPhoneNumber?phoneNumber="01012345679"
HTTP/1.1 200 
Content-Type: application/json;charset=UTF-8
Date: Sat, 20 Feb 2021 14:57:45 GMT
Transfer-Encoding: chunked

[
    {
        "amt": 5000,
        "id": 4544,
        "orderId": 4,
        "phoneNumber": "01012345679",
        "productName": "coffee",
        "qty": 3,
        "status": "Made"
    },
    {
        "amt": 5000,
        "id": 4545,
        "orderId": 5,
        "phoneNumber": "01012345679",
        "productName": "coffee",
        "qty": 3,
        "status": "Ordered"
    },
    {
        "amt": 5000,
        "id": 4546,
        "orderId": 6,
        "phoneNumber": "01012345679",
        "productName": "coffee",
        "qty": 3,
        "status": "Receipted"
    },
    {
        "amt": 5000,
        "id": 4547,
        "orderId": 7,
        "phoneNumber": "01012345679",
        "productName": "coffee",
        "qty": 3,
        "status": "Ordered"
    }
]

```

# 운영

## Liveness / Readiness 설정
Pod 생성 시 준비되지 않은 상태에서 요청을 받아 오류가 발생하지 않도록 Readiness Probe와 Liveness Probe를 설정했다.
```
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order
  labels:
    app: order
spec:
  :
        readinessProbe:
          httpGet:
            path: '/actuator/health'
            port: 8080
          initialDelaySeconds: 10 
          timeoutSeconds: 2 
          periodSeconds: 5 
          failureThreshold: 10
        livenessProbe:
          httpGet:
            path: '/actuator/health'
            port: 8080
          initialDelaySeconds: 120
          timeoutSeconds: 2
          periodSeconds: 5
          failureThreshold: 5

```

## 셀프힐링
livenessProbe를 설정하여 문제가 있을 경우 스스로 재기동 되도록 한다.
```	  
# mongodb down
$ helm delete my-mongodb --namespace mongodb
release "my-mongodb" uninstalled

# mongodb start
$ helm install my-mongodb bitnami/mongodb --namespace mongodb -f values.yaml

# mongodb를 사용하는 customercenter 서비스가 liveness에 실패하여 재기동하고 새롭게 시작한 mongo db에 접속한다. 

$ kubectl describe pods customercenter-7f57cf5f9f-csp2b
:
Events:
  Type     Reason     Age                   From     Message
  ----     ------     ----                  ----     -------
  Normal   Killing    12m (x2 over 6h21m)   kubelet  Container customercenter failed liveness probe, will be restarted
  Normal   Pulling    12m (x3 over 20h)     kubelet  Pulling image "beatific/customercenter:v6"
  Normal   Created    12m (x3 over 20h)     kubelet  Created container customercenter
  Normal   Started    12m (x3 over 20h)     kubelet  Started container customercenter
  Normal   Pulled     12m (x3 over 20h)     kubelet  Successfully pulled image "beatific/customercenter:v6"
  Warning  Unhealthy  11m (x30 over 20h)    kubelet  Readiness probe failed: Get http://10.64.1.29:8080/actuator/health: dial tcp 10.64.1.29:8080: connect: connection refused
  Warning  Unhealthy  11m (x17 over 6h21m)  kubelet  Readiness probe failed: Get http://10.64.1.29:8080/actuator/health: net/http: request canceled (Client.Timeout exceeded while awaiting headers)
  Warning  Unhealthy  14s                   kubelet  Readiness probe failed: HTTP probe failed with statuscode: 503
  Warning  Unhealthy  11s (x13 over 6h21m)  kubelet  Liveness probe failed: Get http://10.64.1.29:8080/actuator/health: net/http: request canceled (Client.Timeout exceeded while awaiting headers)
  
$ kubectl get pods -w
NAME                              READY   STATUS    RESTARTS   AGE
customercenter-7f57cf5f9f-csp2b   1/1     Running   0          20h
drink-7cb565cb4-d2vwb             1/1     Running   0          59m
gateway-5dd866cbb6-czww9          1/1     Running   0          3d1h
order-595c9b45b9-xppbf            1/1     Running   0          58m
payment-698bfbdf7f-vp5ft          1/1     Running   0          24m
siege-5b99b44c9c-8qtpd            1/1     Running   0          3d1h
customercenter-7f57cf5f9f-csp2b   0/1     Running   1          20h
customercenter-7f57cf5f9f-csp2b   1/1     Running   1          20h

```

## CI/CD 설정


각 구현체들은 각자의 source repository 에 구성되었고, 사용한 CI/CD 플랫폼은 AWS를 사용하였으며, pipeline build script 는 각 프로젝트 폴더 아래에 buildspec.yml 에 포함되었다.


## 동기식 호출 / 서킷 브레이킹 / 장애격리

* 서킷 브레이킹 프레임워크의 선택: Spring FeignClient + Hystrix 옵션을 사용하여 구현함

시나리오는 단말앱(order)-->결제(payment) 호출 시의 연결을 RESTful Request/Response 로 연동하여 구현이 되어있고, 결제 요청이 과도할 경우 CB 를 통하여 장애격리.

- Hystrix 를 설정:  요청처리 쓰레드에서 처리시간이 610 밀리가 넘어서기 시작하여 어느정도 유지되면 CB 회로가 닫히도록 (요청을 빠르게 실패처리, 차단) 설정
```
# application.yml

feign:
  hystrix:
    enabled: false 

hystrix:
  command:
    default:
      execution:
        isolation:
          strategy: THREAD
          thread:
            timeoutInMilliseconds: 610         #설정 시간동안 처리 지연발생시 timeout and 설정한 fallback 로직 수행     
      circuitBreaker:
        requestVolumeThreshold: 20           # 설정수 값만큼 요청이 들어온 경우만 circut open 여부 결정 함
        errorThresholdPercentage: 10        # requestVolumn값을 넘는 요청 중 설정 값이상 비율이 에러인 경우 circuit open
        sleepWindowInMilliseconds: 5000    # 한번 오픈되면 얼마나 오픈할 것인지 
      metrics:
        rollingStats:
          timeInMilliseconds: 10000   

```

- 피호출 서비스(결제:payment) 의 임의 부하 처리 - 400 밀리에서 증감 220 밀리 정도 왔다갔다 하게
```
# (payment) Payment.java (Entity)

    @PrePersist
    public void onPrePersist(){  //결제이력을 저장한 후 적당한 시간 끌기

        :
        
        try {
            Thread.currentThread().sleep((long) (400 + Math.random() * 220));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
```

* 부하테스터 siege 툴을 통한 서킷 브레이커 동작 확인:
- 동시사용자 100명
- 60초 동안 실시
```

root@siege-5b99b44c9c-ldf2l:/# siege -v -c100 -t60s --content-type "application/json" 'http://order:8080/orders POST {"phoneNumber":"01087654321", "productName":"coffee", "qty":2, "amt":1000}'
** SIEGE 4.0.4
** Preparing 100 concurrent users for battle.
The server is now under siege...
HTTP/1.1 500     2.52 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     2.53 secs:     317 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     2.54 secs:     317 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     2.55 secs:     317 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     2.54 secs:     317 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     2.53 secs:     317 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     2.56 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     2.56 secs:     317 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     2.58 secs:     317 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     2.60 secs:     317 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     2.95 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.02 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.00 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.03 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.02 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.04 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     3.13 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     3.12 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     3.14 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.20 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.24 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     3.27 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     3.30 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.30 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     3.28 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     3.31 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.41 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     3.41 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.43 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.45 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.48 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.47 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.49 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     3.53 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     3.66 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.70 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.76 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.78 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.77 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     3.92 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.02 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.05 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     4.10 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.11 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.14 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.14 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.12 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     4.13 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.14 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     4.21 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     4.27 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     4.27 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.26 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.34 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     0.95 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.36 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     4.47 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     4.60 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     0.51 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.73 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.78 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     4.82 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     4.92 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     4.91 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     4.94 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 201     5.01 secs:     319 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     0.90 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     5.07 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     5.07 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     5.10 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     5.10 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     5.12 secs:     248 bytes ==> POST http://order:8080/orders
HTTP/1.1 500     5.12 secs:     248 bytes ==> POST http://order:8080/orders
...
Lifting the server siege...siege aborted due to excessive socket failure; you
can change the failure threshold in $HOME/.siegerc

Transactions:		         701 hits
Availability:		       39.58 %
Elapsed time:		       59.21 secs
Data transferred:	        0.47 MB
Response time:		        8.18 secs
Transaction rate:	       11.84 trans/sec
Throughput:		        0.01 MB/sec
Concurrency:		       96.90
Successful transactions:         701
Failed transactions:	        1070
Longest transaction:	        9.81
Shortest transaction:	        0.05
```

- order 서비스의 로그를 확인하여 Circuit이 OPEN된 것을 확인한다.
$ kubectl logs -f order-7ff9b5458-4wn28 | grep OPEN
```
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
java.lang.RuntimeException: Hystrix circuit short-circuited and is OPEN
```

- 운영시스템은 죽지 않고 지속적으로 CB 에 의하여 적절히 회로가 열림과 닫힘이 벌어지면서 자원을 보호하고 있음을 보여줌. 하지만, 63.55% 가 성공하였고, 46%가 실패했다는 것은 고객 사용성에 있어 좋지 않기 때문에 Retry 설정과 동적 Scale out (replica의 자동적 추가,HPA) 을 통하여 시스템을 확장 해주는 후속처리가 필요.

- Retry 의 설정 (istio)
- Availability 가 높아진 것을 확인 (siege)

### 오토스케일 아웃
앞서 CB 는 시스템을 안정되게 운영할 수 있게 해줬지만 사용자의 요청을 100% 받아들여주지 못했기 때문에 이에 대한 보완책으로 자동화된 확장 기능을 적용하고자 한다. 


- 결제서비스에 대한 replica 를 동적으로 늘려주도록 HPA 를 설정한다. 설정은 CPU 사용량이 15프로를 넘어서면 replica 를 10개까지 늘려준다:
```
$ kubectl get pods
NAME                              READY   STATUS    RESTARTS   AGE
customercenter-7f57cf5f9f-csp2b   1/1     Running   1          20h
drink-7cb565cb4-d2vwb             1/1     Running   0          37m
gateway-5dd866cbb6-czww9          1/1     Running   0          3d1h
order-595c9b45b9-xppbf            1/1     Running   0          36m
payment-698bfbdf7f-vp5ft          1/1     Running   0          2m32s
siege-5b99b44c9c-8qtpd            1/1     Running   0          3d1h


$ kubectl autoscale deploy payment --min=1 --max=10 --cpu-percent=15
horizontalpodautoscaler.autoscaling/payment autoscaled

$ kubectl get hpa
NAME      REFERENCE            TARGETS   MINPODS   MAXPODS   REPLICAS   AGE
payment   Deployment/payment   2%/15%    1         10        1          2m35s

# CB 에서 했던 방식대로 워크로드를 2분 동안 걸어준다.

root@siege-5b99b44c9c-ldf2l:/# siege -v -c100 -t60s --content-type "application/json" 'http://order:8080/orders POST {"phoneNumber":"01087654321", "productName":"coffee", "qty":2, "amt":1000}'
** SIEGE 4.0.4
** Preparing 100 concurrent users for battle.
The server is now under siege...

Transactions:		         900 hits
Availability:		       76.08 %
Elapsed time:		       59.33 secs
Data transferred:	        0.34 MB
Response time:		        6.14 secs
Transaction rate:	       15.17 trans/sec
Throughput:		        0.01 MB/sec
Concurrency:		       93.08
Successful transactions:         900
Failed transactions:	         283
Longest transaction:	       14.41
Shortest transaction:	        0.04

$ kubectl get pods
NAME                              READY     STATUS    RESTARTS   AGE
customercenter-59f4d6d897-lnpsh   1/1       Running   0          97m
drink-64bc64d49c-sdwlb            1/1       Running   0          112m
gateway-6dcdf4cb9-pghzz           1/1       Running   0          74m
order-7ff9b5458-4wn28             1/1       Running   2          21m
payment-6f75856f77-b6ctw          1/1       Running   0          118s
payment-6f75856f77-f2l5m          1/1       Running   0          102s
payment-6f75856f77-gl24n          1/1       Running   0          41m
payment-6f75856f77-htkn5          1/1       Running   0          118s
payment-6f75856f77-rplpb          1/1       Running   0          118s
siege-5b99b44c9c-ldf2l            1/1       Running   0          96m
```

- 오토스케일이 어떻게 되고 있는지 모니터링을 걸어둔다:
```
kubectl get deploy payment -w
```
- 어느정도 시간이 흐른 후 (약 30초) 스케일 아웃이 벌어지는 것을 확인할 수 있다:
```
NAME      DESIRED   CURRENT   UP-TO-DATE   AVAILABLE   AGE
payment   1         1         1         1         1h
payment   4         1         1         1         1h
payment   4         1         1         1         1h
payment   4         1         1         1         1h
payment   4         4         4         1         1h
payment   5         4         4         1         1h
payment   5         4         4         1         1h
payment   5         4         4         1         1h
payment   5         5         5         1         1h

# siege 의 로그를 보아도 전체적인 성공률이 높아진 것을 확인 할 수 있다. 

Transactions:		         900 hits
Availability:		       76.08 %
Elapsed time:		       59.33 secs
Data transferred:	        0.34 MB
Response time:		        6.14 secs
Transaction rate:	       15.17 trans/sec
Throughput:		        0.01 MB/sec
Concurrency:		       93.08
Successful transactions:         900
Failed transactions:	         283
Longest transaction:	       14.41
Shortest transaction:	        0.04

```

## 모니터링
모니터링을 위하여 monitor namespace에 Prometheus와 Grafana를 설치하였다.

```
$ kubectl get deploy -n monitor
NAME                                  READY   UP-TO-DATE   AVAILABLE   AGE
cafe-grafana                          1/1     1            1           2d
cafe-kube-prometheus-stack-operator   1/1     1            1           2d
cafe-kube-state-metrics               1/1     1            1           2d
```
grafana 접근을 위해서 grafana의 Service는 LoadBalancer로 생성하였다.
```
$ kubectl get svc -n monitor
NAME                                      TYPE           CLUSTER-IP     EXTERNAL-IP    PORT(S)                      AGE
alertmanager-operated                     ClusterIP      None           <none>         9093/TCP,9094/TCP,9094/UDP   2d
cafe-grafana                              LoadBalancer   10.68.15.180   34.84.30.157   80:32120/TCP                 2d
cafe-kube-prometheus-stack-alertmanager   ClusterIP      10.68.14.210   <none>         9093/TCP                     2d
cafe-kube-prometheus-stack-operator       ClusterIP      10.68.3.201    <none>         443/TCP                      2d
cafe-kube-prometheus-stack-prometheus     ClusterIP      10.68.6.110    <none>         9090/TCP                     2d
cafe-kube-state-metrics                   ClusterIP      10.68.9.55     <none>         8080/TCP                     2d
cafe-prometheus-node-exporter             ClusterIP      10.68.9.213    <none>         9100/TCP                     2d
prometheus-operated                       ClusterIP      None           <none>         9090/TCP                     2d
```
![image](https://user-images.githubusercontent.com/75828964/108602078-625d8a80-73e3-11eb-9517-486c2b5bd584.png)
![image](https://user-images.githubusercontent.com/75828964/108602105-89b45780-73e3-11eb-9bdc-268c1f929511.png)

## 무정지 재배포

* 먼저 무정지 재배포가 100% 되는 것인지 확인하기 위해서 Autoscaler 이나 CB 설정을 제거함

- seige 로 배포작업 직전에 워크로드를 모니터링 함.
```
siege -c100 -t120S -r10 --content-type "application/json" 'localhost:8081/orders POST {"phoneNumber": "01012345678","productName": "coffee","qty": 2,"amt": 1000}'

** SIEGE 4.0.5
** Preparing 100 concurrent users for battle.
The server is now under siege...

HTTP/1.1 201     0.68 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.68 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.70 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.70 secs:     207 bytes ==> POST http://localhost:8081/orders
:

```

- 새버전으로의 배포 시작
```
$ kubectl set image deployment/order order=496278789073.dkr.ecr.ap-northeast-2.amazonaws.com/skteam04/order:v2
deployment.apps/order image updated
```

- seige 의 화면으로 넘어가서 Availability 가 100% 미만으로 떨어졌는지 확인
```
Transactions:		        3078 hits
Availability:		       70.45 %
Elapsed time:		       120 secs
Data transferred:	        0.34 MB
Response time:		        5.60 secs
Transaction rate:	       17.15 trans/sec
Throughput:		        0.01 MB/sec
Concurrency:		       96.02

```
배포기간중 Availability 가 평소 100%에서 70% 대로 떨어지는 것을 확인. 원인은 쿠버네티스가 성급하게 새로 올려진 서비스를 READY 상태로 인식하여 서비스 유입을 진행한 것이기 때문. 이를 막기위해 Readiness Probe 를 설정함:

```
# deployment.yaml 의 readiness probe 의 설정:


kubectl apply -f kubernetes/deployment.yaml
```

- 동일한 시나리오로 재배포 한 후 Availability 확인:
```
Transactions:		        3078 hits
Availability:		       100 %
Elapsed time:		       120 secs
Data transferred:	        0.34 MB
Response time:		        5.60 secs
Transaction rate:	       17.15 trans/sec
Throughput:		        0.01 MB/sec
Concurrency:		       96.02

```

배포기간 동안 Availability 가 변화없기 때문에 무정지 재배포가 성공한 것으로 확인됨.

## Persistence Volum Claim
서비스의 log를 persistence volum을 사용하여 재기동후에도 남아 있을 수 있도록 하였다.
```

# application.yml

:
server:
  tomcat:
    accesslog:
      enabled: true
      pattern:  '%h %l %u %t "%r" %s %bbyte %Dms'
    basedir: /logs/drink

logging:
  path: /logs/drink
  file:
    max-history: 30
  level:
    org.springframework.cloud: debug

# deployment.yaml

apiVersion: apps/v1
kind: Deployment
metadata:
  name: drink
  labels:
    app: drink
spec:
  replicas: 1
  selector:
    matchLabels:
      app: drink
  template:
    metadata:
      labels:
        app: drink
    spec:
      containers:
      - name: drink
        image: beatific/drink:v1
        :
        volumeMounts:
        - name: logs
          mountPath: /logs
      volumes:
      - name: logs
        persistentVolumeClaim:
          claimName: logs

# pvc.yaml

apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: logs
spec:
  accessModes:
  - ReadWriteOnce
  resources:
    requests:
      storage: 1Gi
```
drink deployment를 삭제하고 재기동해도 log는 삭제되지 않는다.

```
$ kubectl delete -f drink/kubernetes/deployment.yml
deployment.apps "drink" deleted

$ kubectl apply -f drink/kubernetes/deployment.yml
deployment.apps/drink created

$ kubectl exec -it drink-7cb565cb4-8c7pq -- /bin/sh
/ # ls -l /logs/drink/
total 5568
drwxr-xr-x    2 root     root          4096 Feb 20 00:00 logs
-rw-r--r--    1 root     root       4626352 Feb 20 16:34 spring.log
-rw-r--r--    1 root     root        177941 Feb 20 08:17 spring.log.2021-02-19.0.gz
-rw-r--r--    1 root     root        235383 Feb 20 15:48 spring.log.2021-02-20.0.gz
-rw-r--r--    1 root     root        210417 Feb 20 15:55 spring.log.2021-02-20.1.gz
-rw-r--r--    1 root     root        214386 Feb 20 15:55 spring.log.2021-02-20.2.gz
-rw-r--r--    1 root     root        214686 Feb 20 16:01 spring.log.2021-02-20.3.gz
drwxr-xr-x    3 root     root          4096 Feb 19 17:34 work

```

## ConfigMap / Secret
mongo db의 database이름과 username, password는 환경변수를 지정해서 사용핳 수 있도록 하였다.
database 이름은 kubernetes의 configmap을 사용하였고 username, password는 secret을 사용하여 지정하였다.

```
# secret 생성
kubectl create secret generic mongodb --from-literal=username=mongodb --from-literal=password=mongodb --namespace cafeteria

# configmap.yaml

apiVersion: v1
kind: ConfigMap
metadata:
  name: mongodb
  namespace: cafeteria
data:
  database: "cafeteria"
  

# application.yml

spring:
  data:
    mongodb:
      uri: mongodb://my-mongodb-0.my-mongodb-headless.mongodb.svc.cluster.local:27017,my-mongodb-1.my-mongodb-headless.mongodb.svc.cluster.local:27017
      database: ${MONGODB_DATABASE}
      username: ${MONGODB_USERNAME}
      password: ${MONGODB_PASSWORD}

#buildspec.yaml
spec:
containers:
  - name: $_PROJECT_NAME
    image: $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/$_PROJECT_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION
    ports:
    - containerPort: 8080
    env:
    - name: SPRING_PROFILES_ACTIVE
      value: "docker"
    - name: MONGODB_DATABASE
      valueFrom:
	configMapKeyRef:
	  name: mongodb
	  key: database
    - name: MONGODB_USERNAME
      valueFrom:
	secretKeyRef:
	  name: mongodb
	  key: username
    - name: MONGODB_PASSWORD
      valueFrom:
	secretKeyRef:
	  name: mongodb
	  key: password
```
