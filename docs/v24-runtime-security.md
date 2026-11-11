# V24 런타임 OS 보안 업데이트

Issue #73은 기존 V5 보안 조사에 이어 V24 후보의 OS 패키지를 보완한다.
기준 develop은 `390329cfe6a11d3ca5abeb3d43d2b011493cdb4b`이다.
기존 V5 작업 브랜치와 이미지, V24 최초 후보는 보존한다.

## 수정 범위

| 패키지 | 기존 후보 | 최소 수정 버전 | 근거 |
| --- | --- | --- | --- |
| apt / libapt-pkg6.0 | 2.4.14 | 2.4.15ubuntu0.1 | [USN-8996-1](https://ubuntu.com/security/notices/USN-8996-1) |
| libpcre2-8-0 | 10.39-3ubuntu0.1 | 10.39-3ubuntu0.2 | [USN-8974-1](https://ubuntu.com/security/notices/USN-8974-1) |
| perl-base | 5.34.0-3ubuntu1.9 | 5.34.0-3ubuntu1.10 | [USN-9006-1](https://ubuntu.com/security/notices/USN-9006-1) |

고정된 Temurin JDK/JRE와 Java 소스는 유지하며 runtime 단계에서 위 패키지만
명시적으로 업그레이드한다. 설치가 끝나면 `dpkg --compare-versions`로 하한을
검사한다. 향후 보안 수정 버전은 허용하되 이전 버전으로 빌드되는 것을 막는다.
기존 curl·CA·Python 설치와 non-root 실행, healthcheck도 유지한다.

APT 패치 다운로드 단계에서도 기존 APT가 실행되므로 모든 APT 명령에
`Acquire::Retries=0`을 지정한다. 이는 [CVE-2026-109518](https://ubuntu.com/security/CVE-2026-109518)의
재시도 조건을 차단한다. `update`에는 `--error-on=any`도 지정해 일부 저장소의
인덱스 조회 실패를 경고로 넘기지 않고 빌드를 실패시킨다. 설치 실패와 버전
하한 미달도 이후 명령을 실행하지 않고 빌드를 중단한다.
첫 후보의 전체 패키지 대조에서는 네 패키지 외에 기존 설치 명령이
ca-certificates를 20260816ubuntu0.22.04.1에서 20260816ubuntu0.22.04.2로 갱신했다.
이는 [USN-9001-1](https://ubuntu.com/security/notices/USN-9001-1)의 JKS 동기화 보완이다.
실제 게시 후보마다 전체 패키지 목록과 TLS 기동 검증 결과를 다시 기록한다.

기존 ECR Basic 검사에는 High 11건(PCRE2 8건, APT 2건, Perl 1건)이 있었다.
이는 6개 CVE의 패키지별 검출 건수이다. Perl CVE-2026-82560의 취약 코드는
Pod::Text 모듈이다. 기존 최소 이미지에는 해당 모듈이 없지만, 예외 처리로
숨기지 않고 설치된 perl-base도 동일 소스 패키지의 보안 업데이트로 교체한다.
전체 Perl 또는 perl-modules를 새로 설치하지 않는다.

## 검증 및 적용 경계

1. linux/amd64 이미지를 빌드하고 실제 설치 버전, 실행 UID, JAR SHA256과
   V1~V24 migration SQL을 기존 후보와 대조한다.
2. 격리 PostgreSQL·Redis에서 health, 인증, 장애 복구 및 V18→V24 전환을
   기존 CI로 검증한다. Spotless와 적용되는 Flyway·Gitleaks 검사도 확인한다.
3. 승인된 ECR 저장소의 새 검증 태그에 게시하고 동일 Basic 검사 결과를
   비교한다. 인덱스가 아닌 linux/amd64 manifest digest로 결과를 조회한다.
   패키지 버전만으로 High 0건을 추정하지 않으며 잔여 항목은 그대로 보고한다.
4. PR에는 소스 SHA, 이미지 digest, 검사 시점과 결과를 기록한다.
   ECR Basic 결과는 전체 애플리케이션 의존성의 무취약 보증이 아니다.

운영 배포와 운영 DB 변경은 이 수정에 포함되지 않는다. 사용자 병합 후
배포용 이미지·전환 plan의 source SHA와 digest를 다시 결합하고, 최종 후보의
사전 검사 결과를 공유한 뒤 B 배포와 C 데이터 적재를 각각 승인받는다.
