# cafeteria
# Table of contents

- [음료주문](#---)
  - [서비스 시나리오](#서비스-시나리오)
  - [분석/설계](#분석설계)
  - [구현:](#구현-)
    - [DDD 의 적용](#ddd-의-적용)
    - [API Gateway](#API-GATEWAY)
    - [폴리글랏 퍼시스턴스](#폴리글랏-퍼시스턴스)
    - [동기식 호출 과 Fallback 처리](#동기식-호출-과-Fallback-처리)
    - [비동기식 호출 과 Eventual Consistency](#비동기식-호출--시간적-디커플링--장애격리--최종-eventual-일관성-테스트)
    - [CQRS / Meterialized View](#CQRS--Meterialized-View)
  - [운영](#운영)
    - [Liveness / Readiness 설정](#Liveness--Readiness-설정)
    - [CI/CD 설정](#cicd-설정)
    - [Self Healing](#Self-Healing)
    - [동기식 호출 / 서킷 브레이킹 / 장애격리](#동기식-호출--서킷-브레이킹--장애격리)
    - [오토스케일 아웃](#오토스케일-아웃)
    - [무정지 재배포](#무정지-재배포)
    - [Persistence Volum Claim](#Persistence-Volum-Claim)
    - [ConfigMap / Secret](#ConfigMap--Secret)

# 서비스 시나리오

음료주문

기능적 요구사항
1. 고객이 음료를 주문한다
1. 고객이 결제한다
1. 결제가 되면 주문 내역이 바리스타에게 전달된다
2. 결제된 금액의 10%는 포인트로 적립되고 향후 연계된 포인트 몰에서 사용할 수 있다.
3. 고객은 포인트 적립내역을 확인할 수 있다.
4. 바리스타는 주문내역을 확인하여 음료를 접수하고 제조한다.
5. 고객이 주문을 취소할 수 있다.
6. 주문이 취소되면 적립된 포인트도 취소된다.
7. 주문이 취소되면 음료를 취소한다.
8. 음료가 취소되면 결제를 취소한다.
9. 고객이 주문상태를 중간중간 조회한다.
10. 주문상태가 바뀔 때 마다 카톡으로 알림을 보낸다.

비기능적 요구사항
1. 트랜잭션
    1. 결제가 되지 않은 주문건은 아예 거래가 성립되지 않아야 한다.  Sync 호출
    2. 주문이 취소되어도 바리스타가 접수하여 음료제조를 시작한 주문인 경우 주문 취소는 원복되어야 한다.  Saga(보상 트랜잭션)
    3. 주문이 취소되려면 반드시 포인트가 먼저 취소되어야 한다. Sync 호출
1. 장애격리
    1. 음료제조 기능이 수행되지 않더라도 주문은 받을 수 있어야 한다.  Async (event-driven), Eventual Consistency
    1. 결제시스템이 과중되면 사용자를 잠시동안 받지 않고 결제를 잠시후에 하도록 유도한다.  Circuit breaker, fallback
1. 성능
    1. 고객이 자주 확인할 수 있는 주문상태를 마이페이지(프론트엔드)에서 확인할 수 있어야 한다.  CQRS
    1. 주문상태가 바뀔때마다 카톡 등으로 알림을 줄 수 있어야 한다.  Event driven

# 분석설계

1. Event Storming 모델
![image](https://user-images.githubusercontent.com/75828964/108789696-1dda1680-75be-11eb-8b36-8fe8b1c87a3e.png)
1. 헥사고날 아키텍처 다이어그램 도출
![image](https://user-images.githubusercontent.com/75828964/108683149-8e3c5580-7534-11eb-9d76-c09a3c79c744.png)

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

cd point
mvn spring-boot:run
```

## DDD 의 적용

- 각 서비스내에 도출된 핵심 Aggregate Root 객체를 Entity 로 선언하였다

```
package cafeteriapoint;

import javax.persistence.*;
import org.springframework.beans.BeanUtils;
import java.util.List;

@Entity
@Table(name="Point_table")
public class Point {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private String phoneNumber;
    private Integer point;
    
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
    public Integer getPoint() {
        return point;
    }

    public void setPoint(Integer point) {
        this.point = point;
    }
    :

```
- Entity Pattern 과 Repository Pattern 을 적용하여 JPA 를 통하여 다양한 데이터소스 유형 (RDB or NoSQL) 에 대한 별도의 처리가 없도록 데이터 접근 어댑터를 자동 생성하기 위하여 Spring Data REST 의 RestRepository 를 적용하였다
```
package cafeteriapoint;

import java.util.List;

import org.springframework.data.repository.PagingAndSortingRepository;

public interface PointRepository extends PagingAndSortingRepository<Point, Long>{

	public List<Point> findByPhoneNumber(String phoneNumber);
}
```
- 적용 후 REST API 의 테스트
```
# point 서비스의 전화번호 조회
root@siege-5b99b44c9c-ldf2l:/# http http://point:8080/points/search/findByPhoneNumber?phoneNumber="01012345679"
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Tue, 23 Feb 2021 06:36:08 GMT
Transfer-Encoding: chunked

{
    "_embedded": {
        "points": [
            {
                "_links": {
                    "point": {
                        "href": "http://point:8080/points/1"
                    },
                    "self": {
                        "href": "http://point:8080/points/1"
                    }
                },
                "phoneNumber": "01012345679",
                "point": 150
            }
        ]
    },
    "_links": {
        "self": {
            "href": "http://point:8080/points/search/findByPhoneNumber?phoneNumber=01012345679"
        }
    }
}


# order 서비스의 주문 취소처리
http patch http://order:8080/orders/3 status="OrderCanceled"
HTTP/1.1 200 
Content-Type: application/json;charset=UTF-8
Date: Tue, 23 Feb 2021 06:37:12 GMT
Transfer-Encoding: chunked

{
    "_links": {
        "order": {
            "href": "http://order:8080/orders/3"
        },
        "self": {
            "href": "http://order:8080/orders/3"
        }
    },
    "amt": 500,
    "createTime": "2021-02-23T06:35:06.413+0000",
    "phoneNumber": "01012345679",
    "productName": "coffee",
    "qty": 3,
    "status": "OrderCanceled"
}

# point 조회
root@siege-5b99b44c9c-ldf2l:/# http http://point:8080/points/search/findByPhoneNumber?phoneNumber="01012345679"
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Tue, 23 Feb 2021 06:38:02 GMT
Transfer-Encoding: chunked

{
    "_embedded": {
        "points": [
            {
                "_links": {
                    "point": {
                        "href": "http://point:8080/points/1"
                    },
                    "self": {
                        "href": "http://point:8080/points/1"
                    }
                },
                "phoneNumber": "01012345679",
                "point": 100
            }
        ]
    },
    "_links": {
        "self": {
            "href": "http://point:8080/points/search/findByPhoneNumber?phoneNumber=01012345679"
        }
    }
}


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
        - id: point
          uri: http://point:8080
          predicates:
            - Path= /points/**,/pointHistories/**

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
    
$ kubectl get svc
NAME             TYPE           CLUSTER-IP       EXTERNAL-IP                                                                  PORT(S)          AGE
customercenter   ClusterIP      10.100.52.95     <none>                                                                       8080/TCP         25h
drink            ClusterIP      10.100.136.6     <none>                                                                       8080/TCP         25h
gateway          LoadBalancer   10.100.164.152   a6826d83b5c8e4f5dad7129c7cdf0ded-93964597.ap-northeast-2.elb.amazonaws.com   8080:30109/TCP   25h
order            ClusterIP      10.100.197.15    <none>                                                                       8080/TCP         25h
payment          ClusterIP      10.100.242.153   <none>                                                                       8080/TCP         25h
point            ClusterIP      10.100.165.131   <none>                                                                       8080/TCP         3h47m

```
 - order  
![image](https://user-images.githubusercontent.com/75828964/108970362-46990380-76c5-11eb-8939-f0b7b7df2cf1.png)
 - payment  
![image](https://user-images.githubusercontent.com/75828964/108970393-4862c700-76c5-11eb-8e4d-121cdd80468c.png)
 - drink  
![image](https://user-images.githubusercontent.com/75828964/108970420-4993f400-76c5-11eb-8b11-423a59b1c8b2.png)
 - customercenter  
![image](https://user-images.githubusercontent.com/75828964/108970445-4b5db780-76c5-11eb-9a24-14f73be5e715.png)
 - point
![image](https://user-images.githubusercontent.com/75828964/108970481-4dc01180-76c5-11eb-80d5-c85bf9711f07.png)

## 폴리글랏 퍼시스턴스

point 서비스는 maria DB에 익숙한 개발자가 많아 maria DB를 사용하기로 하였다. 이를 위해 point는 별다른 작업없이 기존의 Entity Pattern 과 Repository Pattern 적용과 데이터베이스 제품의 설정 (application.yml) 만으로 maria db에 부착시켰다
```
#application.yml
spring:
  datasource:
    url: jdbc:mariadb://my-mariadb-mariadb-galera.mariadb.svc.cluster.local:3306/${MARIADB_DATABASE}
    driver-class-name: org.mariadb.jdbc.Driver
    username: ${MARIADB_USERNAME}
    password: ${MARIADB_PASSWORD} 
	  
#pom.xml
<dependencies>
:
	<dependency>
		<groupId>org.mariadb.jdbc</groupId> 
		<artifactId>mariadb-java-client</artifactId> 
	</dependency>
:
</dependencies>

```

## 동기식 호출 과 Fallback 처리

분석단계에서의 조건 중 하나로 주문(order)->결제(payment) 간의 호출은 동기식 일관성을 유지하는 트랜잭션으로 처리하기로 하였다. 호출 프로토콜은 이미 앞서 Rest Repository 에 의해 노출되어있는 REST 서비스를 FeignClient 를 이용하여 호출하도록 한다. 

- 결제서비스를 호출하기 위하여 Stub과 (FeignClient) 를 이용하여 Service 대행 인터페이스 (Proxy) 를 구현 

```
# (order) PointService.java

package cafeteria.external;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import cafeteria.OrderCanceled;

@RequestMapping("/points")
@FeignClient(name="point", url="${feign.client.point.url}")
public interface PointService {

    @RequestMapping(path = "/cancelPoint", method = RequestMethod.PATCH)
	public void cancelPoint(@RequestBody OrderCanceled orderCanceled);

}
```

- 주문 취소 직후(@PostUpdate) 결제를 요청하도록 처리
```
# Order.java (Entity)

    @PostUpdate
    public void onPostUpdate(){
        switch(this.status) {
    	case "OrderCanceled" : 
    		
    	    OrderCanceled orderCanceled = new OrderCanceled();
            BeanUtils.copyProperties(this, orderCanceled);
            orderCanceled.publishAfterCommit();
            
            OrderApplication.applicationContext.getBean(PointService.class).cancelPoint(orderCanceled);
            break;
    	}

    }
```

- 동기식 호출에서는 호출 시간에 따른 타임 커플링이 발생하며, 포인트 시스템이 장애가 나면 주문취소도 못한는다는 것을 확인:


```
# 포인트 (point) 서비스를 잠시 내려놓음
$ kubectl delete deploy point
deployment.apps "point" deleted

#주문취소

root@siege-5b99b44c9c-ldf2l:/# http patch http://order:8080/orders/4 status="OrderCanceled"
HTTP/1.1 500 
Connection: close
Content-Type: application/json;charset=UTF-8
Date: Tue, 23 Feb 2021 06:52:57 GMT
Transfer-Encoding: chunked

{
    "error": "Internal Server Error",
    "message": "Could not commit JPA transaction; nested exception is javax.persistence.RollbackException: Error while committing the transaction",
    "path": "/orders/4",
    "status": 500,
    "timestamp": "2021-02-23T06:52:57.358+0000"
}


# 포인트 서비스 재기동
$ kubectl apply -f deployment.yml
deployment.apps/point created

#주문처리

root@siege-5b99b44c9c-ldf2l:/# http patch http://order:8080/orders/4 status="OrderCanceled"
HTTP/1.1 200 
Content-Type: application/json;charset=UTF-8
Date: Tue, 23 Feb 2021 06:54:44 GMT
Transfer-Encoding: chunked

{
    "_links": {
        "order": {
            "href": "http://order:8080/orders/4"
        },
        "self": {
            "href": "http://order:8080/orders/4"
        }
    },
    "amt": 500,
    "createTime": "2021-02-23T06:52:42.048+0000",
    "phoneNumber": "01012345679",
    "productName": "coffee",
    "qty": 3,
    "status": "OrderCanceled"
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
- 음료 서비스에서는 Ordered 이벤트에 대해서 이를 수신하여 자신의 정책을 처리하도록 PolicyHandler 를 구현한다:

```
package cafeteria;

:

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
            }
        }catch (Exception e){
            e.printStackTrace();
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
          group: point
          destination: cafeteria
          contentType: application/json
        :
```

포인트 시스템은 주문/결제와 완전히 분리되어있으며, 이벤트 수신에 따라 처리되기 때문에, 포인트시스템이 유지보수로 인해 잠시 내려간 상태라도 주문을 받는데 문제가 없다:
```
# 포인트 서비스 (point) 를 잠시 내려놓음
$ kubectl delete deploy point
deployment.apps "point" deleted

#현재 포인트 확인
root@siege-5b99b44c9c-ldf2l:/# http http://point:8080/points/search/findByPhoneNumber?phoneNumber="01012345679"
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Tue, 23 Feb 2021 06:56:02 GMT
Transfer-Encoding: chunked

{
    "_embedded": {
        "points": [
            {
                "_links": {
                    "point": {
                        "href": "http://point:8080/points/1"
                    },
                    "self": {
                        "href": "http://point:8080/points/1"
                    }
                },
                "phoneNumber": "01012345679",
                "point": 100
            }
        ]
    },
    "_links": {
        "self": {
            "href": "http://point:8080/points/search/findByPhoneNumber?phoneNumber=01012345679"
        }
    }
}
#주문처리
root@siege-5b99b44c9c-ldf2l:/# http http://order:8080/orders phoneNumber="01012345679" productName="coffee" qty=3 amt=500
HTTP/1.1 201 
Content-Type: application/json;charset=UTF-8
Date: Tue, 23 Feb 2021 06:58:35 GMT
Location: http://order:8080/orders/5
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
    "amt": 500,
    "createTime": "2021-02-23T06:58:35.815+0000",
    "phoneNumber": "01012345679",
    "productName": "coffee",
    "qty": 3,
    "status": "Ordered"
}

# 포인트 서비스 기동
kubectl apply -f deployment.yml
deployment.apps/point created

# 포인트 적립 확인
root@siege-5b99b44c9c-ldf2l:/# http http://point:8080/points/search/findByPhoneNumber?phoneNumber="01012345679"
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Tue, 23 Feb 2021 07:00:04 GMT
Transfer-Encoding: chunked

{
    "_embedded": {
        "points": [
            {
                "_links": {
                    "point": {
                        "href": "http://point:8080/points/1"
                    },
                    "self": {
                        "href": "http://point:8080/points/1"
                    }
                },
                "phoneNumber": "01012345679",
                "point": 150
            }
        ]
    },
    "_links": {
        "self": {
            "href": "http://point:8080/points/search/findByPhoneNumber?phoneNumber=01012345679"
        }
    }
}

```

## CQRS / Meterialized View
Point서비스의 PointHistory를 구현하여 Order 서비스, Payment 서비스의 데이터를 Composite서비스나 DB Join없이 조회할 수 있다.
```
root@siege-5b99b44c9c-ldf2l:/# http http://point:8080/pointHistories/search/findByPhoneNumberOrderByCreateTime?phoneNumber="01012345678"
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Tue, 23 Feb 2021 07:27:16 GMT
Transfer-Encoding: chunked

{
    "_embedded": {
        "pointHistories": [
            {
                "_links": {
                    "pointHistory": {
                        "href": "http://point:8080/pointHistories/12"
                    },
                    "self": {
                        "href": "http://point:8080/pointHistories/12"
                    }
                },
                "amt": 500,
                "createTime": "2021-02-23T07:26:59.019+0000",
                "orderId": 16,
                "paymentId": 19,
                "phoneNumber": "01012345678",
                "point": 50,
                "productName": "coffee",
                "qty": 3,
                "totalPoint": 150,
                "type": "Order"
            },
            {
                "_links": {
                    "pointHistory": {
                        "href": "http://point:8080/pointHistories/13"
                    },
                    "self": {
                        "href": "http://point:8080/pointHistories/13"
                    }
                },
                "amt": 500,
                "createTime": "2021-02-23T07:27:00.128+0000",
                "orderId": 17,
                "paymentId": 20,
                "phoneNumber": "01012345678",
                "point": 50,
                "productName": "coffee",
                "qty": 3,
                "totalPoint": 200,
                "type": "Order"
            },
            {
                "_links": {
                    "pointHistory": {
                        "href": "http://point:8080/pointHistories/14"
                    },
                    "self": {
                        "href": "http://point:8080/pointHistories/14"
                    }
                },
                "amt": 500,
                "createTime": "2021-02-23T07:27:14.618+0000",
                "orderId": 18,
                "paymentId": 21,
                "phoneNumber": "01012345678",
                "point": 50,
                "productName": "coffee",
                "qty": 3,
                "totalPoint": 250,
                "type": "Order"
            }
        ]
    },
    "_links": {
        "self": {
            "href": "http://point:8080/pointHistories/search/findByPhoneNumberOrderByCreateTime?phoneNumber=01012345678"
        }
    }
}


```

# 운영

## Liveness / Readiness 설정
Pod 생성 시 준비되지 않은 상태에서 요청을 받아 오류가 발생하지 않도록 Readiness Probe와 Liveness Probe를 설정했다.
```
apiVersion: apps/v1
kind: Deployment
metadata:
  name: point
  labels:
    app: point
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

## Self Healing
livenessProbe를 설정하여 문제가 있을 경우 스스로 재기동 되도록 한다.
```	  
# mariadb down
$ helm delete my-mariadb --namespace mariadb
release "my-mariadb" uninstalled

# mariadb start
$ helm install my-mariadb bitnami/mariadb-galera --namespace mariadb -f values.yaml

# mariadb를 사용하는 point 서비스가 liveness에 실패하여 재기동하고 새롭게 시작한 maria db에 접속한다. 

$ kubectl describe pods point-55b4dc964d-2crld
:
Events:
  Type     Reason     Age               From                                                        Message
  ----     ------     ----              ----                                                        -------
  Normal   Scheduled  53m               default-scheduler                                           Successfully assigned cafeteria/point-55b4dc964d-2crld to ip-192-168-32-134.ap-northeast-2.compute.internal
  Warning  Unhealthy  5m (x5 over 6m)   kubelet, ip-192-168-32-134.ap-northeast-2.compute.internal  Liveness probe failed: Get http://192.168.39.137:8080/actuator/health: net/http: request canceled (Client.Timeout exceeded while awaiting headers)
  Normal   Killing    5m                kubelet, ip-192-168-32-134.ap-northeast-2.compute.internal  Container point failed liveness probe, will be restarted
  Warning  Unhealthy  5m (x6 over 6m)   kubelet, ip-192-168-32-134.ap-northeast-2.compute.internal  Readiness probe failed: Get http://192.168.39.137:8080/actuator/health: net/http: request canceled (Client.Timeout exceeded while awaiting headers)
  Normal   Pulling    5m (x3 over 53m)  kubelet, ip-192-168-32-134.ap-northeast-2.compute.internal  Pulling image "496278789073.dkr.ecr.ap-northeast-2.amazonaws.com/skteam04/point:v5"
  Normal   Created    5m (x3 over 53m)  kubelet, ip-192-168-32-134.ap-northeast-2.compute.internal  Created container point
  Normal   Started    5m (x3 over 53m)  kubelet, ip-192-168-32-134.ap-northeast-2.compute.internal  Started container point
  Normal   Pulled     5m (x3 over 53m)  kubelet, ip-192-168-32-134.ap-northeast-2.compute.internal  Successfully pulled image "496278789073.dkr.ecr.ap-northeast-2.amazonaws.com/skteam04/point:v5"
  Warning  Unhealthy  5m (x6 over 53m)  kubelet, ip-192-168-32-134.ap-northeast-2.compute.internal  Readiness probe failed: Get http://192.168.39.137:8080/actuator/health: dial tcp 192.168.39.137:8080: connect: connection refused
  Warning  BackOff    1m (x17 over 5m)  kubelet, ip-192-168-32-134.ap-northeast-2.compute.internal  Back-off restarting failed container
$ kubectl get pods -w
NAME                             READY     STATUS    RESTARTS   AGE
customercenter-54cf98c78-qq5kg   1/1       Running   0          16h
drink-dcd7d598-vc8z4             1/1       Running   0          17h
gateway-54b895b5f6-bchbn         1/1       Running   0          17h
order-98fb9d9bf-vhn2d            1/1       Running   0          3h
payment-5f77c97c67-jmzmm         1/1       Running   0          18h
point-55b4dc964d-2crld           1/1       Running   0          47m
siege-5b99b44c9c-ldf2l           1/1       Running   0          1d
point-55b4dc964d-2crld   0/1       Running   1         47m
point-55b4dc964d-2crld   0/1       Error     1         48m
point-55b4dc964d-2crld   0/1       Running   2         48m
point-55b4dc964d-2crld   0/1       Error     2         48m
point-55b4dc964d-2crld   0/1       CrashLoopBackOff   2         48m
point-55b4dc964d-2crld   0/1       Running   3         48m
point-55b4dc964d-2crld   0/1       Error     3         49m
point-55b4dc964d-2crld   0/1       CrashLoopBackOff   3         49m
point-55b4dc964d-2crld   0/1       Running   4         49m
point-55b4dc964d-2crld   0/1       Error     4         50m
point-55b4dc964d-2crld   0/1       CrashLoopBackOff   4         50m
point-55b4dc964d-2crld   0/1       Running   5         50m
point-55b4dc964d-2crld   0/1       Error     5         51m
point-55b4dc964d-2crld   0/1       CrashLoopBackOff   5         51m
point-55b4dc964d-2crld   0/1       Running   6         52m
point-55b4dc964d-2crld   1/1       Running   6         53m

```

## CI/CD 설정


각 구현체들은 하나의 source repository 에 구성되었고, 사용한 CI/CD 플랫폼은 AWS를 사용하였으며, pipeline build script는 각 프로젝트 폴더 아래에 buildspec.yml 에 포함되었다.

![image](https://user-images.githubusercontent.com/75828964/108964978-8e1c9100-76bf-11eb-99e3-14b0d48dae4a.png)
![image](https://user-images.githubusercontent.com/75828964/108964985-8fe65480-76bf-11eb-9f78-9dfc433c64d1.png)
![image](https://user-images.githubusercontent.com/75828964/108964987-91178180-76bf-11eb-9df3-79375b047983.png)
![image](https://user-images.githubusercontent.com/75828964/108964990-9248ae80-76bf-11eb-95ba-a99c438a97d9.png)
![image](https://user-images.githubusercontent.com/75828964/108964992-94127200-76bf-11eb-98cf-dbf4e087488f.png)
![image](https://user-images.githubusercontent.com/75828964/108965001-97a5f900-76bf-11eb-9f54-1ae2b6b7d35d.png)

## 동기식 호출 / 서킷 브레이킹 / 장애격리

* 서킷 브레이킹 프레임워크의 선택: Spring FeignClient + Hystrix 옵션을 사용하여 구현함

시나리오는 주문(order)-->포인트(point) 호출 시의 연결을 RESTful Request/Response 로 연동하여 구현이 되어있고, 결제 요청이 과도할 경우 CB 를 통하여 장애격리.

- Hystrix 를 설정:  요청처리 쓰레드에서 처리시간이 610 밀리가 넘어서기 시작하여 어느정도 유지되면 CB 회로가 닫히도록 (요청을 빠르게 실패처리, 차단) 설정
```
# application.yml

feign:
  hystrix:
    enabled: true 

hystrix:
  command:
    point: #payment의 경우 timeout 610ms에 걸리지 않도록 point service만 등록
      execution:
        isolation:
          thread:
            timeoutInMilliseconds: 610         #설정 시간동안 처리 지연발생시 timeout and 설정한 fallback 로직 수행     

```

- 피호출 서비스(결제:payment) 의 임의 부하 처리 - 400 밀리에서 증감 220 밀리 정도 왔다갔다 하게
```
# (point) PointController.java (Entity)

    @PatchMapping("/cancelPoint")
	public void cancelPoint(@RequestBody OrderCanceled orderCanceled) {
        
        try {
            Thread.currentThread().sleep((long) (400 + Math.random() * 220));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
```

* 부하테스트의 취소할 데이터를 만들기 위해 siege부하를 먼저 발생시킨다.
```
root@siege-5b99b44c9c-br928:/sh# siege -v -c10 -t60s --content-type "application/json" 'http://order:8080/orders POST {"phoneNumber":"01087654321", "productName":"coffee", "qty":2, "amt":1000}'
** SIEGE 4.0.4
** Preparing 100 concurrent users for battle.
The server is now under siege...
Lifting the server siege...
Lifting the server siege...
Transactions:		        6636 hits
Availability:		      100.00 %
Elapsed time:		       59.29 secs
Data transferred:	        2.04 MB
Response time:		        0.09 secs
Transaction rate:	      111.92 trans/sec
Throughput:		        0.03 MB/sec
Concurrency:		        9.96
Successful transactions:        6636
Failed transactions:	           0
Longest transaction:	        0.46
Shortest transaction:	        0.01

```

* 부하테스터 siege 툴을 통한 서킷 브레이커 동작 확인:
- 동시사용자 100명
- 취소 url의 번호가 바뀌어야 하므로 취소를 위한 파일 생성
```
# cat url.sh 
count=0
stop=$1
while [ 1 ];
do 
    count=$(($count+1))
    echo "http://order:8080/orders/$count PATCH {\"status\":\"OrderCanceled\"}"
    if [ $count -eq $stop ]; then
        break
    fi
done

#url.sh 2000 > urls
```

```
root@siege-5b99b44c9c-br928:/sh# siege -f urls -v -c 100 --content-type "application/json"
** SIEGE 4.0.4
** Preparing 100 concurrent users for battle.
The server is now under siege...
HTTP/1.1 200     1.64 secs:     327 bytes ==> PATCH http://order:8080/orders/541
HTTP/1.1 200     1.87 secs:     327 bytes ==> PATCH http://order:8080/orders/341
HTTP/1.1 200     2.01 secs:     327 bytes ==> PATCH http://order:8080/orders/181
HTTP/1.1 200     2.03 secs:     327 bytes ==> PATCH http://order:8080/orders/601
HTTP/1.1 200     2.05 secs:     327 bytes ==> PATCH http://order:8080/orders/281
HTTP/1.1 200     2.06 secs:     327 bytes ==> PATCH http://order:8080/orders/621
HTTP/1.1 200     2.12 secs:     327 bytes ==> PATCH http://order:8080/orders/161
HTTP/1.1 200     2.10 secs:     329 bytes ==> PATCH http://order:8080/orders/1981
HTTP/1.1 200     2.16 secs:     327 bytes ==> PATCH http://order:8080/orders/461
HTTP/1.1 200     2.16 secs:     327 bytes ==> PATCH http://order:8080/orders/721
HTTP/1.1 200     2.18 secs:     327 bytes ==> PATCH http://order:8080/orders/121
HTTP/1.1 200     2.36 secs:     327 bytes ==> PATCH http://order:8080/orders/801
HTTP/1.1 200     2.47 secs:     329 bytes ==> PATCH http://order:8080/orders/1621
HTTP/1.1 200     2.55 secs:     327 bytes ==> PATCH http://order:8080/orders/901
HTTP/1.1 200     2.55 secs:     329 bytes ==> PATCH http://order:8080/orders/1701
HTTP/1.1 200     2.69 secs:     327 bytes ==> PATCH http://order:8080/orders/561
HTTP/1.1 200     2.71 secs:     327 bytes ==> PATCH http://order:8080/orders/921
HTTP/1.1 200     2.76 secs:     327 bytes ==> PATCH http://order:8080/orders/841
HTTP/1.1 200     2.75 secs:     329 bytes ==> PATCH http://order:8080/orders/1901
HTTP/1.1 500     2.82 secs:     252 bytes ==> PATCH http://order:8080/orders/761
HTTP/1.1 500     2.84 secs:     253 bytes ==> PATCH http://order:8080/orders/1421
HTTP/1.1 200     2.89 secs:     329 bytes ==> PATCH http://order:8080/orders/1241
HTTP/1.1 200     3.12 secs:     327 bytes ==> PATCH http://order:8080/orders/441
HTTP/1.1 200     3.22 secs:     327 bytes ==> PATCH http://order:8080/orders/521
HTTP/1.1 200     3.24 secs:     327 bytes ==> PATCH http://order:8080/orders/261
HTTP/1.1 500     3.25 secs:     252 bytes ==> PATCH http://order:8080/orders/221
HTTP/1.1 200     3.26 secs:     327 bytes ==> PATCH http://order:8080/orders/481
HTTP/1.1 200     3.24 secs:     329 bytes ==> PATCH http://order:8080/orders/1641
HTTP/1.1 200     3.33 secs:     327 bytes ==> PATCH http://order:8080/orders/861
HTTP/1.1 200     3.34 secs:     327 bytes ==> PATCH http://order:8080/orders/681
HTTP/1.1 200     3.35 secs:     329 bytes ==> PATCH http://order:8080/orders/1961
HTTP/1.1 200     0.65 secs:     327 bytes ==> PATCH http://order:8080/orders/842
HTTP/1.1 200     3.65 secs:     329 bytes ==> PATCH http://order:8080/orders/1581
...

Transactions:		         513 hits
Availability:		       31.36 %
Elapsed time:		       29.59 secs
Data transferred:	        0.17 MB
Response time:		        5.14 secs
Transaction rate:	       17.34 trans/sec
Throughput:		        0.01 MB/sec
Concurrency:		       89.19
Successful transactions:         513
Failed transactions:	        1123
Longest transaction:	        9.99
Shortest transaction:	        0.54
```

- 운영시스템은 죽지 않고 지속적으로 CB 에 의하여 적절히 회로가 열림과 닫힘이 벌어지면서 자원을 보호하고 있음을 보여줌. 하지만, 30% 가 성공하였고, 70%가 실패했다는 것은 고객 사용성에 있어 좋지 않기 때문에 Retry 설정과 동적 Scale out (replica의 자동적 추가,HPA) 을 통하여 시스템을 확장 해주는 후속처리가 필요.

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


$ kubectl autoscale deploy point --min=1 --max=10 --cpu-percent=15
horizontalpodautoscaler.autoscaling/point autoscaled

$ kubectl get hpa
NAME      REFERENCE            TARGETS   MINPODS   MAXPODS   REPLICAS   AGE
payment   Deployment/payment   2%/15%    1         10        1          2m35s

# CB 에서 했던 방식대로 워크로드를 1분 동안 걸어준다.

root@siege-5b99b44c9c-ldf2l:/# siege -v -c100 -t60s --content-type "application/json" 'http://order:8080/orders POST {"phoneNumber":"01087654321", "productName":"coffee", "qty":2, "amt":1000}'
** SIEGE 4.0.4
** Preparing 100 concurrent users for battle.
The server is now under siege...

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

- HPA를 확인한다.
```
$ kubectl get hpa 
NAME      REFERENCE            TARGETS   MINPODS   MAXPODS   REPLICAS   AGE
payment   Deployment/payment   72%/15%   1         10        5          12m
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

## 무정지 재배포

* 먼저 무정지 재배포가 100% 되는 것인지 확인하기 위해서 Autoscaler 이나 CB 설정을 제거함

- seige 로 배포작업 직전에 워크로드를 모니터링 함.
```
root@siege-5b99b44c9c-ldf2l:/#  siege -v -c100 -t120s http://point:8080/pointHistories/search/findByPhoneNumberOrderByCreateTime?phoneNumber="01012345678"
** SIEGE 4.0.4
** Preparing 100 concurrent users for battle.
The server is now under siege...
HTTP/1.1 200     0.04 secs:     696 bytes ==> GET  /pointHistories/search/findByPhoneNumberOrderByCreateTime?phoneNumber=01012345678
HTTP/1.1 200     0.05 secs:     696 bytes ==> GET  /pointHistories/search/findByPhoneNumberOrderByCreateTime?phoneNumber=01012345678
HTTP/1.1 200     0.05 secs:     696 bytes ==> GET  /pointHistories/search/findByPhoneNumberOrderByCreateTime?phoneNumber=01012345678
HTTP/1.1 200     0.05 secs:     696 bytes ==> GET  /pointHistories/search/findByPhoneNumberOrderByCreateTime?phoneNumber=01012345678
HTTP/1.1 200     0.08 secs:     696 bytes ==> GET  /pointHistories/search/findByPhoneNumberOrderByCreateTime?phoneNumber=01012345678
HTTP/1.1 200     0.09 secs:     696 bytes ==> GET  /pointHistories/search/findByPhoneNumberOrderByCreateTime?phoneNumber=01012345678
:

```

- 새버전으로의 배포 시작


- 쿠버네티스가 성급하게 새로 올려진 서비스를 READY 상태로 인식하여 서비스 유입을 진행할 수 있기 때문에 이를 막기위해 Readiness Probe를 설정하고 성급하게 기존 서비스의 처리 중 종료를 막기위해 Graceful Shutdown을 적용

```
# Graceful Shutdown 적용 
public class TomcatGracefulShutdown implements TomcatConnectorCustomizer, ApplicationListener<ContextClosedEvent> {

	private Integer waiting = 30; 
	
    private volatile Connector connector;

    @Override
    public void customize(Connector connector) {
        this.connector = connector;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        this.connector.pause();
        Executor executor = this.connector.getProtocolHandler().getExecutor();
        if (executor instanceof ThreadPoolExecutor) {
            try {
                ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) executor;
                threadPoolExecutor.shutdown();
                if (!threadPoolExecutor.awaitTermination(waiting, TimeUnit.SECONDS)) {
                    log.error("Tomcat thread pool did not shut down gracefully within {} seconds. Proceeding with forceful shutdown", waiting);

                    threadPoolExecutor.shutdownNow();

                    if (!threadPoolExecutor.awaitTermination(waiting, TimeUnit.SECONDS)) {
                        log.error("Tomcat thread pool did not terminate");
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
```
- 부하를 발생시키고 배포 한 후 Availability 확인:

```
root@siege-5b99b44c9c-ldf2l:/#  siege -v -c100 -t120s http://point:8080/pointHistories/search/findByPhoneNumberOrderByCreateTime?phoneNumber="01012345678"
** SIEGE 4.0.4
** Preparing 100 concurrent users for battle.
The server is now under siege...
:
Lifting the server siege...
Transactions:		       43045 hits
Availability:		      100.00 %
Elapsed time:		      119.74 secs
Data transferred:	       28.57 MB
Response time:		        0.28 secs
Transaction rate:	      359.49 trans/sec
Throughput:		        0.24 MB/sec
Concurrency:		       99.69
Successful transactions:       43045
Failed transactions:	           0
Longest transaction:	        4.28
Shortest transaction:	        0.00
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
    basedir: /logs/point

logging:
  path: /logs/point
  file:
    max-history: 30
  level:
    org.springframework.cloud: debug

# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: point
  labels:
    app: point
spec:
  replicas: 1
  selector:
    matchLabels:
      app: point
  template:
    metadata:
      labels:
        app: point
  template:
    metadata:
      labels:
        app: point
    spec:
      containers:
      - name: point
        image: 496278789073.dkr.ecr.ap-northeast-2.amazonaws.com/skteam04/point:v2
        :
        volumeMounts:
        - name: logs
          mountPath: /logs
      volumes:
      - name: logs
        persistentVolumeClaim:
          claimName: point-logs

# pvc.yaml

apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: point-logs
spec:
  accessModes:
  - ReadWriteOnce
  resources:
    requests:
      storage: 1Gi
```
point deployment를 삭제하고 재기동해도 log는 삭제되지 않는다.

```
$ kubectl delete -f point/kubernetes/deployment.yml
deployment.apps "point" deleted

$ kubectl apply -f drink/kubernetes/deployment.yml
deployment.apps/point created

$ kubectl exec -it point-598d96c945-46ds9 -- /bin/sh

/logs/point # ls -ltr
total 64
drwxr-xr-x    3 root     root            20 Feb 23 09:06 work
drwxr-xr-x    2 root     root            39 Feb 23 09:06 logs
-rw-r--r--    1 root     root         63360 Feb 23 09:06 spring.log

```

## ConfigMap / Secret
maria db의 database이름과 username, password는 환경변수를 지정해서 사용핳 수 있도록 하였다.
database 이름은 kubernetes의 configmap을 사용하였고 username, password는 secret을 사용하여 지정하였다.

```
# secret 생성
kubectl create secret generic mariadb --from-literal=username=mariadb --from-literal=password=mariadb --namespace cafeteria

# configmap.yaml

apiVersion: v1
kind: ConfigMap
metadata:
  name: mariadb
  namespace: cafeteria
data:
  database: "cafeteria"
  
# application.yml

spring:
  datasource:
    url: jdbc:mariadb://my-mariadb-mariadb-galera.mariadb.svc.cluster.local:3306/${MARIADB_DATABASE}
    driver-class-name: org.mariadb.jdbc.Driver
    username: ${MARIADB_USERNAME}
    password: ${MARIADB_PASSWORD} 

#buildspec.yaml

      spec:
	containers:
	    env:
	      - name: SPRING_PROFILES_ACTIVE
		value: "docker"
	      - name: MARIADB_DATABASE
		valueFrom:
		  configMapKeyRef:
		    name: mariadb 
		    key: database
	      - name: MARIADB_USERNAME
		valueFrom:
		  secretKeyRef:
		    name: mariadb
		    key: username
	      - name: MARIADB_PASSWORD
		valueFrom:
		  secretKeyRef:
		    name: mariadb 
		    key: password
```
