# GitHub Actions EC2 Preflight

관련 Issue: #32. 폐기된 PR #31과 삭제된 브랜치는 재사용하지 않습니다.

## 범위와 실행 조건

`HobbyTree EC2 Preflight`는 원본 저장소의 `develop`에서 수동으로만 실행합니다.
PR, 다른 브랜치, fork에서는 AWS 자격 증명을 받지 않습니다. 기존 ECR Push와
독립적이며, 이미지 업로드/컨테이너 재시작/DB 마이그레이션을 수행하지 않습니다.
실제 실행은 SSM 문서가 EC2에서 명령을 실행하는 작업이므로 아래 사전 확인 후
담당자가 명시적으로 수행합니다. 이 PR의 테스트에서는 AWS를 호출하지 않습니다.

## 기존 AWS 구성 및 첫 실행 전 확인

| 설정 | 고정 대상 |
| --- | --- |
| 계정 / 리전 | `494915081306` / `ap-northeast-2` |
| OIDC 역할 | `hobbytree-github-ec2-deploy-role` |
| EC2 | `i-0d71fcf646b7210f2` |
| SSM 문서 / 버전 | `HobbyTree-Dev-Preflight` / `1` |

2026-11-02 `hobbytree-readonly`로 문서와 역할을 읽기 전용 확인했습니다.
SSM 문서 v1은 Active, schema 2.2의 단일 `aws:runShellScript` step이며 실행 제한은
60초입니다. Docker 실행 가능 여부, 배포 파일 존재, systemd active, 두 컨테이너의
존재만 점검합니다. 파일/서비스 변경이나 Secret 조회 명령은 없고 출력도 고정 문구뿐입니다.
컨테이너 내부 health나 RDS/Redis 연결까지 보장하는 점검은 아닙니다.

역할에는 이 문서와 인스턴스에 한정된 `ssm:SendCommand`와,
`Resource: "*"`로 허용된 `ssm:GetCommandInvocation`만 있는 인라인 정책이 있습니다.
연결된 관리형 정책은 없습니다. 결과 조회 권한도 개별 ARN으로 제한된다는 의미는 아닙니다.
trust의 audience는 `sts.amazonaws.com`, subject는 아래 immutable ID 형식으로
`develop`에 제한되어 있음을 확인했습니다.

```text
repo:Kau-BugFree@336851079/hobby-tree-Backend@1405678704:ref:refs/heads/develop
```

실제 GitHub OIDC 토큰 발급/SSM 실행은 테스트하지 않았습니다. 첫 실행 전에 담당자가
다음을 다시 확인해야 합니다. 이 구현은 IAM/SSM 설정을 바꾸지 않습니다.

- OIDC trust의 audience가 `sts.amazonaws.com`이며 subject가 이 저장소의
  `develop` ref로 제한되어 있어야 합니다. 저장소가 immutable subject 형식을 쓰면
  실제 발급되는 subject에 맞춰 확인합니다. Workflow에서 Environment는 사용하지 않습니다.
- 역할이 위 인스턴스와 문서에 대한 `ssm:SendCommand`, 결과 조회를 위한
  `ssm:GetCommandInvocation`을 허용하는지 확인합니다. 역할 이름만으로 최소 권한을
  보장하지 않으므로 기존 정책 범위도 리뷰합니다. Access Key는 등록하지 않습니다.
- 문서 버전 1이 단일 step이며, 점검만 수행하고 서비스/파일/DB를 변경하지 않는지
  원문을 확인합니다. 여러 step이면 현재 결과 조회 방식과 호환되지 않으므로 실행하지 않습니다.
- 문서가 비밀번호/토큰/개인정보를 출력하지 않고, 점검 실패 시 0이 아닌 종료 코드를
  반환하는지 확인합니다. SSM 자체에는 출력이 남을 수 있습니다.
- EC2가 SSM managed node로 Online이며 실제 실행 제한이 아래 대기 시간과 맞는지 확인합니다.

## 결과 및 오류 처리

공식 AWS 인증 Action과 Checkout은 저장소의 기존 검증된 commit SHA로 고정합니다.
OIDC 권한은 Preflight job에만 부여하며 임시 자격 증명은 step output으로 내보내지 않습니다.

`send-command`의 120초는 아직 시작하지 않은 명령의 전달 제한입니다. 실행 완료
제한이 아닙니다. 전송 후 최대 240초 동안 5초 간격으로 결과를 조회합니다.
기본 `command-executed` waiter의 약 100초 한도는 사용하지 않습니다.
응답 유실에 따른 중복 제출을 막기 위해 전송의 AWS CLI 시도 횟수는 1회로 고정하고,
결과 조회만 최대 2회 시도를 허용합니다.

`InvocationDoesNotExist`는 eventual consistency로 간주하여 제한 시간 안에서
재조회합니다. 그 밖의 API 오류/응답 형식 오류/알 수 없는 상태는 실패합니다.
`Success`와 정수 응답 코드 `0`을 모두 확인해야만 성공합니다. terminal 실패와
대기 시간 초과는 Workflow를 실패 처리합니다. 조회 응답 수신 후에도 절대 마감 시간을
재확인하여 240초에 도달하거나 지난 성공 응답은 수락하지 않습니다. GitHub 로그와 성공 Summary에는
명령 ID와 결과만 기록하고 원격 stdout/stderr 및 AWS 오류 원문은 출력하지 않습니다.

시간 초과 또는 Workflow 취소가 원격 명령의 중단을 의미하지는 않습니다.
자동 취소/재전송은 하지 않으므로 SSM 콘솔에서 해당 명령 ID의 상태를 확인한 뒤
재실행 여부를 판단합니다. `guard` job 한도는 2분이고, 그 완료 후 시작하는
`preflight` job 한도는 8분입니다. 두 job의 실행 한도 합계는 10분이지만,
concurrency 큐 및 runner 배정 대기는 별도이므로 전체 경과 시간의 엄격한 상한은 아닙니다.
동시 실행은 직렬화합니다. 명령 ID를 수신한 뒤 검증 중 취소되면 `finally`에서
유효한 ID의 즉시 출력을 시도합니다. 응답 수신 전 취소나 강제 종료까지 기록을
보장하지는 않으며 원격 명령도 자동 취소하지 않습니다.
전송 응답이 유실되어 명령 ID를 받지 못한 경우에도 SSM의 최근 명령 기록을 먼저 확인합니다.

## 검증과 리뷰

```bash
python3 -m unittest discover -s scripts/tests -p test_ec2_preflight.py -v
actionlint .github/workflows/hobbytree-ec2-preflight.yml
```

모의 테스트는 subprocess를 대체하므로 AWS 자격 증명과 네트워크가 필요 없습니다.
기존 Docker Validation의 Python 테스트 탐색에도 포함됩니다. 기존 Java/Spotless/Docker
검증은 유지합니다. Gitleaks는 PR 차이와 추가 파일을 로컬에서 검사하고 결과를 PR에 기록합니다.

필수 CI와 CodeRabbit 검토 완료를 확인한 뒤 PR 작성자가 Merge Commit으로 병합합니다.
추가 리뷰 요건은 저장소 정책과 PR별 합의에 따릅니다. Auto-merge는 사용하지 않습니다.
이 저장소의 기본 브랜치는 `develop`입니다. 이 파일이 기본 브랜치에 병합되기 전에는
수동 실행에 사용할 수 없으며, 병합 후 Actions에서 `develop`을 선택해 실행합니다.
병합 후 수동 실행은
위 AWS 사전 확인을 마친 뒤 별도로 수행하며 이 PR 작업에서 대신 실행하지 않습니다.
