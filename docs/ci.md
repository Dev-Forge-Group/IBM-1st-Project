# PR 검증

`.github/workflows/ci.yml`은 모든 pull request, `main`·`dev`·`release-validation/**` push, 수동 실행에서 아래 두 검사를 실행한다. 경로 필터로 검사가 빠지지 않으며, 같은 PR/브랜치의 오래된 실행은 취소한다.

`release-validation/**`는 배포 브랜치를 바꾸지 않고 릴리스 후보를 검사하는 경로다. 이 CI는 읽기 전용 권한으로 실행하며 ECR·S3·CloudFront 배포와 연결하지 않는다. 검사 결과와 실제 ruleset·배포 대기 상태는 각각 확인해야 한다.

| 검사 이름 | 도구 | 실행 내용 |
| --- | --- | --- |
| `Frontend checks` | `package.json`의 `volta.node` pin, npm lockfile | `npm ci`, lint, Vitest 단위/RTL 검사, Vite build |
| `Backend checks` | Java 21, 저장소 Gradle wrapper, Docker, Python 3 | wrapper 검증, Docker 확인, 결과 검사기 회귀, 전체 Gradle test/build, 실제 실행된 JUnit 결과 확인 |

프런트 JUnit과 백엔드 XML/HTML 테스트 보고서를 실패 시에도 7일 동안 보존한다. 검사는 읽기 전용 GitHub 권한을 사용하며 배포·AWS·외부 공급자 secret을 사용하지 않는다. fork PR에도 동일한 테스트 명령을 적용한다.

## 백엔드 테스트 실행 확인

백엔드는 다음 명령으로 이전 테스트 결과와 task cache 재사용을 배제한다. Maven/Gradle 의존성 cache는 계속 활용한다.

```bash
cd uni-place-backend
bash ./gradlew cleanTest test build --no-daemon --no-build-cache --max-workers=2 --console=plain
python3 ../scripts/ci/verify_junit.py build/test-results/test
```

검사기는 XML에 기록된 개수와 실제 `testcase` 항목이 일치하는지, 실패·오류·0개 실행이 없는지 확인한다. 아래 통합 suite가 누락되거나 비어 있거나 일부라도 skip이면 실패한다. 테스트 개수를 고정하지 않아 새 회귀를 추가할 수 있다.

- `CommunityPrivacyIntegrationTest`: 실제 MySQL·Flyway·JWT·HTTP 개인정보/권한 계약.
- `AiDataContractMySqlTest`: 실제 MySQL에 대한 AI 데이터 조회 계약. 원래 Docker가 없으면 skip할 수 있어 별도로 실행 여부를 확인한다.
- `CommonCodeLookupIntegrationTest`: 비활성 코드·그룹까지 포함한 공통코드 존재 조회 계약.
- `NotificationRecipientsIntegrationTest`: 삭제 여부·관리자 수신자 선택과 같은 트랜잭션의 변경·미커밋 가시성.
- `ReviewContractHistoryIntegrationTest`: active·ended 계약 이력과 사용자·방 일치, 같은 트랜잭션에서의 계약 변경 조회.
- `SpaceReservationConcurrencyIntegrationTest`: 실제 MySQL의 활성 슬롯 유일성, 동시 생성과 상태 변경, 취소·종료 후 재예약.
- `ReservationAvailabilityIntegrationTest`: 투어·공용공간의 예약 가능 목록과 엄격 겹침 정책, 비정형 과거 이력, 같은 날짜의 투어 입력.
- `AdminTourRestorationIntegrationTest`: 실제 JWT·관리자 보안 필터·HTTP·MySQL에서 투어 복구 충돌, 정상 전이·거절 및 상태 보존.

다른 선택 검사에서 의도적으로 skip한 항목은 통과 수와 분리해 집계한다. 전체 JUnit 결과의 실패·오류는 항상 실패로 처리한다. 검사기 자체의 거짓 통과 방지 회귀는 다음과 같이 실행한다.

```bash
python3 -B -m unittest discover -s scripts/ci -p 'test_*.py' -v
```

## 프런트와 검사 활성화

프런트는 `uni-place-frontend`에서 `npm ci`, `npm run lint`, `npm test`, `npm run build`로 같은 동작을 확인한다. CI에서는 추가 JUnit reporter를 사용해 runner 임시 디렉터리에 결과를 기록한다. 별도 Playwright 브라우저 helper와 외부 AI·결제·OAuth·운영 환경은 이 workflow의 검사 범위가 아니다.

workflow를 원격에 반영하면 PR에 검사가 생성된다. 병합 필수 검사로 강제하려면 저장소 ruleset/branch protection에서 `Frontend checks`, `Backend checks`를 required status check로 지정해야 한다. 이 파일은 그 설정을 변경하지 않는다.

기존 배포 workflow는 독립적으로 실행된다. 이 검사를 추가한 것만으로 배포 workflow가 결과를 기다리지는 않는다. 배포 연계는 별도로 검토한다.
