# ALB 뒤 로그인 IP와 신뢰 경계

기준: PR14가 병합된 `feature/12-social-auth`의 `8f38699`.
AWS·SG·ALB 속성·운영 환경파일을 이 작업에서 조회/변경하거나 배포하지 않는다. SQL은 변경하지 않는다.

## 기존 동작과 문제

AuthFilter는 `request.getRemoteAddr()`를 Redis 로그인 제한 키로 사용한다. 최신 기반의 ec2
프로필은 Tomcat RemoteIpValve와 하드코딩된 `10.0.0.0/16` 정규식을 사용한다. 따라서 이 값이
항상 socket peer라고 볼 수 없으며, CIDR 검사를 하기 전에 이미 헤더로 변환될 수 있다.

단순히 XFF 첫 주소를 고르면 사용자가 넣은 주소로 제한을 우회하거나 다른 사용자를 제한할 수 있다.
전달 체인을 오른쪽부터 따라가더라도 **실제 클라이언트가 내부 프록시 범위와 겹치면** 그 주소까지
건너뛰고 사용자가 넣은 왼쪽 주소를 고를 수 있다. VPC 전체를 ALB로 간주하지 않는다.

## 현재 처리 계약

- 모든 프로필에서 `server.forward-headers-strategy=none`으로 socket peer를 보존한다.
  EC2 진입점은 native/framework 및 Tomcat remoteip 환경변수 우회를 거절한다.
- `AUTH_TRUSTED_PROXY_CIDRS`는 명시적인 ALB 서브넷 CIDR 목록이다. 코드에는 운영 CIDR이나
  RFC1918/loopback 전체 신뢰 기본값이 없다. 로컬에서 비어 있으면 아무 프록시도 신뢰하지 않는다.
  EC2 Compose/사전 검사는 명시적인 값을 요구한다. `/0`, 호스트명, zone, host-bit CIDR은 거절한다.
- **CIDR만으로 ALB를 식별할 수 없다.** 기존 `hobbytree-ec2-sg` TCP8080의 소스를
  `hobbytree-alb-sg`로만 제한하는 구조가 핵심 신뢰 경계다. 같은 서브넷의 다른 ENI를 신뢰한다는 뜻이 아니다.
- 비신뢰 peer의 XFF/Forwarded/X-Real-IP는 전부 무시하고 원래 peer로 제한한다.
- 신뢰 peer는 **단일 ALB + append 모드**를 전제로 XFF의 마지막 주소만 사용한다.
  사용자 입력인 왼쪽 prefix는 신뢰하지 않는다. 마지막 클라이언트가 신뢰 CIDR에 있어도 더 왼쪽으로 이동하지 않는다.
- 신뢰 peer의 헤더 누락·중복 필드·빈 마지막 항목·잘못된 IP/포트·과도한 길이는400으로 거절한다.
  공급자 검증/로그인 성공으로 이어지지 않으며 첫 주소나 peer로 대체하지 않는다.
- 숫자 IPv4/IPv6만 허용하고 DNS 조회용 호스트명은 거절한다. IPv6 동등 표기와 IPv4-mapped
  IPv6를 정규화하고 ALB의 선택적인 client port를 제외해, 표기·포트 변경으로 새 제한 키를 만들지 못한다.
- 로그인 60초20회·Retry-After, JWT/Refresh/회원 상태 업무 규칙은 변경하지 않는다.
  같은 NAT 공인 IP를 공유하는 사용자들은 기존의 IP 기반 정책대로 한도를 공유한다.
- 이 계약은 다중 CDN/프록시 체인의 최종 사용자 복원 규칙이 아니다. ALB 앞에 다른 프록시를
  추가하면 마지막 값은 그 프록시의 IP가 되므로 별도 신뢰 모델 검토가 필요하다.

## 운영 설정과 배포 체크리스트

사용자가 제공한 현재 구성의 ALB 서브넷은 2a=`10.0.1.0/24`, 2b=`10.0.2.0/24`다.
이는 ALB 전용 IP 집합이 아니라 서브넷 전체이므로 SG 제한을 함께 유지해야 한다.
운영자가 기존 `.env.ec2`를 덮어쓰지 않고 비밀이 아닌 다음 설정을 명시한다.

```dotenv
AUTH_TRUSTED_PROXY_CIDRS=10.0.1.0/24,10.0.2.0/24
```

1. SG TCP8080 소스가 ALB SG뿐인지 확인한다. 인터넷/전체 VPC/전체 Docker bridge를 허용하지 않는다.
2. 실제 ALB의 `routing.http.xff_header_processing.mode`가 **append**인지 확인한다.
   preserve/remove이면 이 계약이 성립하지 않는다. 이 작업에서는 속성을 변경하거나 실제 값을 조회하지 않았다.
3. `routing.http.xff_client_port.enabled`도 확인한다. 구현은 IPv4:port와 [IPv6]:port를 처리한다.
4. 기존 `BACKEND_BIND_IP=0.0.0.0`, ALB target HTTP8080, `/actuator/health`, RDS TLS·권한을 유지한다.
5. 실제 EC2 Docker 경로에서 `getRemoteAddr()`가 ALB 서브넷의 원래 peer인지 확인한다.
   일시적으로 `LOGGING_LEVEL_HOBBYTREE_AUTH_PROXY=DEBUG`로 backend만 재생성하고 제어된 로그인
   요청을 보내 `Login socket peer=..., trustedProxy=true`를 확인할 수 있다. 헤더·토큰·본문은 기록하지 않는다.
6. Docker/NAT가 peer를 bridge/gateway 주소로 바꾸면 **그 범위를 신뢰 목록에 추가해 우회하지 않는다.**
   원래 source를 보존하는 인입 경로를 별도로 검토한 후 배포한다. CI 결과를 실제 ALB/NAT 검증으로 대체하지 않는다.
7. 다른 공인 IP 두 곳에서 각각 제한이 독립적인지 확인한다. 같은 클라이언트가 XFF prefix를 바꿔도
   한도가 초기화되지 않는지, private client·IPv6·client port도 검증한다. HTTPS API/Swagger 동작도 확인한다.
8. 확인 후 DEBUG를 INFO로 되돌린다. 운영 진단 로그는 접근·보존 정책에 따라 처리한다.

필요한 환경변수를 설정하지 않은 채 기존 EC2에 새 이미지를 배포하면 사전 검사가 실패한다.
이 PR은 환경파일·AWS 변경·RDS 마이그레이션·EC2 재배포를 자동 실행하지 않는다.

## 테스트와 CI

- `ClientIpResolverTests`: 위조된 앞 주소, 사설 클라이언트, 신뢰 경계, 숫자 IP 파싱·표기 정규화,
  port·중복/잘못된 헤더·잘못된 CIDR을 단위 테스트한다.
- 기존 `AuthIntegrationTests` **18개를 그대로 유지**한다. 별도 실제 Tomcat/Redis
  `ProxyRateLimitIntegrationTests` **8개**로 같은 ALB peer 뒤 여러 클라이언트, prefix/표기/port 변경,
  잘못된 전달 헤더와 공급자 미호출을 검증한다.
- runner는 두 XML의18개/8개와 실패0·skip0을 각각 강제한다. 기존18개를 줄이거나 일반 테스트 skip으로 대체하지 않는다.
- CI만의 internal Docker 네트워크192.0.2.0/24에서 `.10/32`를 신뢰 peer로 지정한다.
  실제 socket peer `.10`과 비신뢰 `.11` 컨테이너가 같은 backend에 요청한다. 실제 Redis 키로
  사용자별 독립 제한·앞 주소 변조 방지·비신뢰 IP 우회 방지·사설 클라이언트 처리를 확인한다.
- CI용 CIDR은 운영 CIDR과 다르다. Secret 준비·JWT/TLS/DDL·Redis 장애/AOF 복구·backend-only
  재배포 검사는 유지한다. 생성한 고유 테스트 프로젝트 자원만 정리한다.
- 실행 결과는 별도 검증 기록/PR에 남긴다. 실제 AWS·ALB 설정과 EC2 Docker source 보존은 배포 전 검증 대상이다.

공식 근거: [AWS ALB X-Forwarded headers](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/x-forwarded-headers.html).
