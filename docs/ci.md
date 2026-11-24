# PR 검증

`.github/workflows/ci.yml`은 모든 pull request, `main`·`dev`·`release-validation/**` push, 수동 실행에서 아래 두 검사를 실행한다. 경로 필터로 검사가 빠지지 않으며, 같은 PR/브랜치의 오래된 실행은 취소한다.

`release-validation/**`는 배포 브랜치를 바꾸지 않고 릴리스 후보를 검사하는 경로다. CI는 읽기 전용 권한으로 실행한다. 릴리스 워크플로에서도 같은 검사를 호출하며, 검사 결과와 실제 ruleset·배포 대기 상태는 각각 확인한다.

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

- `OwnerAccountPropertyIntegrationTest`: 실제 JWT·HTTP·MySQL·Flyway에서 MEMBER/STAFF 분리, OWNER 신청·심사와 같은 토큰의 권한 반영, 자산 증빙·승인과 draft 공개 범위, 건물/개별 방 소유권, 소유주 간 CRUD·페이지/count·파일 격리, 계약 당사자 snapshot과 날짜·입주자 관계 기반 TENANT를 검사한다. 합성 Storage·문서·알림만 사용한다.
- `V17OwnershipUpgradeIntegrationTest`: 데이터가 있는 MySQL V16을 V17로 올려 기존 계정·자산·계약·입주자 이력과 소유권 부여 범위를 확인한다. V16의 합성 논리 백업을 별도 DB에 복원한 뒤 다시 V17을 적용해 같은 결과를 확인한다. [전환 준비](ownership-rollout.md).
- `CommunityPrivacyIntegrationTest`: 실제 MySQL·Flyway·JWT·HTTP에서 익명 게시글·댓글과 본인 여부를 확인하고 STAFF도 익명 작성자 신원을 받지 못하는 개인정보 계약.
- `AiDataContractMySqlTest`: 실제 MySQL에 대한 AI 데이터 조회 계약. 원래 Docker가 없으면 skip할 수 있어 별도로 실행 여부를 확인한다.
- `CommonCodeLookupIntegrationTest`: 비활성 코드·그룹까지 포함한 공통코드 존재 조회 계약.
- `NotificationRecipientsIntegrationTest`: 삭제 여부·관리자 수신자 선택과 같은 트랜잭션의 변경·미커밋 가시성.
- `ReviewContractHistoryIntegrationTest`: active·ended 계약 이력과 사용자·방 일치, 같은 트랜잭션에서의 계약 변경 조회.
- `ContractLifecycleIntegrationTest`: 실제 MySQL·Flyway에서 스케줄러 계약 교체와 계약 비활성화의 방 점유, 마지막 active 해제, 입주자·수동 상태·논리 삭제 보존을 확인한다. 저장된 legacy 역할을 덮어쓰지 않고 계약·입주자 관계로 TENANT 권한이 변하는지 검사한다.
- `JwtAuthorityIntegrationTest`: 실제 MySQL·JWT·보안 필터·HTTP에서 STAFF 권한 해제와 계약 종료·OWNER 승인/취소 뒤 기존 access token의 현재 DB 권한, STAFF의 소비자 권한 격리, generic 역할 변경으로 ADMIN/TENANT를 부여할 수 없는 경계, principal/authority 일치, guest·회원 상태·token 오류와 refresh 계약.
- `TourHttpJourneyIntegrationTest`: RANDOM_PORT 실제 HTTP·MySQL에서 공개 탐색·생성·조회·취소와 자산 범위별 OWNER 처리 및 STAFF 거절, 복구 충돌·이력·슬롯 변화·잘못된 query400 계약. 기본 CI의 브라우저 실행은 포함하지 않으며 로컬 opt-in 브라우저 검증은 별도 증거로 남긴다.
- `TourLookupPaginationIntegrationTest`: 실제 HTTP·MySQL에서 상태 필터를 페이지 전에 적용한 content/count·정렬, 전화/PIN 경계와 alias·PIN 비노출, 유효한 빈 필터·페이지 범위·기존 오류 및 취소 후 페이지 감소.
- `ContractFileAccessIntegrationTest`: 실제 JWT·HTTP·MySQL에서 CONTRACT 파일의 본인 소비자/승인된 계약 당사자 OWNER read/write, STAFF 거절과 구 관리자 API 폐쇄, 권한 취소·삭제 파일·static 우회 차단·proxy URL·private cache 및 공개 ROOM 조회를 확인한다. 기존 AI 생성 문서의 조회·변경, 섞인 삭제 요청, XLSX 생성·다운로드도 차단되는지 확인한다. AI 콜백에서 내부 토큰과 회원 JWT가 각각 필요하고, 개인 조회 결과가 본인 행으로 제한되며 캐시되지 않는지 검사한다. 파일과 AI 응답은 합성 fixture만 사용한다.
- `ContractCreateValidationIntegrationTest`: 실제 JWT·HTTP·MySQL에서 검증된 운영자가 있는 방의 계약 납부일 1–31·필수 비어 있지 않은 서명, 저장·파일·알림 이전 거절과 직접 서비스 호출, 유효한 경계값·기존 기간/충돌 오류를 검사한다. Storage·문서 생성·외부 알림은 합성 fixture로 격리한다.
- `ResidentCreationTransactionIntegrationTest`: 실제 JWT·자산/계약 당사자 범위의 OWNER HTTP·MySQL에서 수동 입주자 생성·commit·조회·순차 재요청, 매핑/입력/권한 거절을 검사한다. 같은 외부 쓰기 트랜잭션의 미커밋 계약과 등록을 읽고 독립 JDBC 연결의 비가시성·rollback을 확인한다.
- `SpaceReservationConcurrencyIntegrationTest`: 실제 MySQL의 활성 슬롯 유일성, 날짜가 유효한 계약·일치 입주자와 검증된 운영자, 동시 생성과 OWNER 상태 변경, 취소·종료 후 재예약.
- `ReservationAvailabilityIntegrationTest`: 투어·공용공간의 예약 가능 목록과 엄격 겹침 정책, 비정형 과거 이력, 같은 날짜의 투어 입력.
- `AdminTourRestorationIntegrationTest`: 실제 JWT·OWNER 보안 필터·HTTP·MySQL에서 해당 자산의 투어 복구 충돌, 정상 전이·거절 및 상태 보존.
- `TourTelephoneCompatibilityIntegrationTest`: 실제 MySQL에서 전화 표기 호환·기존 원문 보존·조회/취소/중복·자산 OWNER 복구·페이지 정렬과 count·AI 직접 조회 및 잘못된 credential의 거절.
- `TourReservationConcurrencyIntegrationTest`: 실제 MySQL에서 외부 REPEATABLE READ snapshot·오래된 관리 엔티티를 가진 생성/복구/취소 경쟁, 전화 별칭·legacy overlap·inactive 재사용, commit 이후 Slack과 DB 잠금 해제.

이 목록은 필수 검증 범위를 설명하며, 실행 결과나 최종 테스트 개수를 보증하지 않는다. 계정·소유권 API와 운영 전환 경계는 [계정·자산 소유권 계약](account-ownership.md)을 따른다.

다른 선택 검사에서 의도적으로 skip한 항목은 통과 수와 분리해 집계한다. 전체 JUnit 결과의 실패·오류는 항상 실패로 처리한다. 검사기 자체의 거짓 통과 방지 회귀는 다음과 같이 실행한다.

```bash
python3 -B -m unittest discover -s scripts/ci -p 'test_*.py' -v
```

## 프런트와 검사 활성화

프런트는 `uni-place-frontend`에서 `npm ci`, `npm run lint`, `npm test`, `npm run build`로 같은 동작을 확인한다. CI에서는 추가 JUnit reporter를 사용해 runner 임시 디렉터리에 결과를 기록한다. 별도 Playwright 브라우저 helper와 외부 AI·결제·OAuth·운영 환경은 이 workflow의 검사 범위가 아니다.

계약 날짜의 `ContractApply.dates.test.jsx`·`contractDates.test.js`는 실제 worker 시간대·offset을 확인한 뒤 한국 자정·LA의 DST·윤년·월/연말 계산과 제출 payload를 검사한다. fake Date와 시간대 설정은 검사 뒤 복원한다. 제어된 브라우저 시계와 실제 API를 연결하는 opt-in 검증은 이 기본 CI와 별도 증거다. [날짜 계약](contract-calendar-dates.md).

workflow를 원격에 반영하면 PR에 검사가 생성된다. 병합 필수 검사로 강제하려면 저장소 ruleset/branch protection에서 `Frontend checks`, `Backend checks`를 required status check로 지정해야 한다. 이 파일은 그 설정을 변경하지 않는다.

## 웹 릴리스가 기다리는 검사

백엔드 이미지, 프런트 정적 배포, 수동 프런트 이미지 워크플로의 `Verify release commit` 작업이 `ci.yml`을 재사용한다. 이 작업에는 저장소 읽기 권한만 부여한다. 프런트와 백엔드 검사 및 필수 JUnit 확인이 모두 끝나야 태그·릴리스 생성, ECR 업로드, S3 업로드·CloudFront 무효화 작업으로 넘어간다.

검사, 태그 생성, 이미지 빌드, 정적 배포는 각각 실행의 `github.sha`를 체크아웃한다. 릴리스 호출은 `refs/heads/dev`에서만 실행된다. 수동 실행도 같은 조건을 적용하며 검사 실패·취소·건너뜀 시 배포 작업은 실행되지 않는다. 기존 PR과 검증 브랜치 CI는 계속 독립적으로 실행된다. AI 이미지 승격은 별도 워크플로의 경계를 따른다.

백엔드 이미지에는 커밋 SHA 태그와 OCI revision 라벨을 기록한다. 업로드 성공 후 Actions 요약에 커밋, 릴리스 태그, 레지스트리 다이제스트를 남긴다. 전환 기록에는 이전 이미지와 대상 이미지의 다이제스트를 보관한다.

운영에서 이 조건의 동작을 확인할 때는 대상 커밋과 일치하는 GitHub Actions 결과에서 검사 실패 뒤 쓰기 작업이 건너뛰어졌는지 확인한다. 브랜치 보호, AWS 역할의 브랜치 제한, 검증한 이미지 다이제스트와 실제 실행 이미지 사이의 일치는 별도로 확인한다.
