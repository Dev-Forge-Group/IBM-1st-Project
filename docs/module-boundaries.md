# 읽기 전용 모듈 계약

기존 제품과 DB를 유지하면서 다른 도메인의 Repository·Entity 직접 사용을 공개 계약으로 바꾸는 단계다. 주요 조회 계약과 소비자는 다음과 같다.

| 소유 context | 공개 계약 | 호출 context |
| --- | --- | --- |
| commoncode | `boolean exists(String canonicalCode)` | support의 `QnaServiceImpl`, `ComplainServiceImpl` |
| contract | `boolean hasActiveOrEndedContract(String userId, Integer roomId)` | review의 `ReviewServiceImpl.createReview` |

호출자는 기존 trim·대문자화·별칭·`ALL`·기본값 규칙을 그대로 적용한다. 공통코드 context는 정확한 코드가 DB에 존재하는지만 반환한다. 비활성 코드나 비활성 그룹도 존재하면 true이며, DB 오류를 false로 바꾸지 않는다. 관리자용 목록 DTO나 Entity를 계약에 포함하지 않는다.

구현은 commoncode 내부에서 Repository를 사용하고 기본 REQUIRED 전파의 읽기 전용 트랜잭션에 참여한다. support의 쓰기 트랜잭션·알림·AI 흐름을 별도 트랜잭션으로 분리하지 않는다. 기존 `QNA_*`·`COMP_*` prefix의 정규화 분기도 이 경계 변경에서 재정의하지 않는다.

두 서비스의 직접 Repository 주입을 제거하고 네 개 존재 조회를 계약으로 연결했다. 같은 공개 서비스 회귀를 추출 전·후 실행하고, 실제 MySQL/Flyway 검사로 비활성 코드·그룹의 존재 의미를 확인했다.

이 단계는 한 저장소·한 Boot 애플리케이션 안의 경계 정리다. 독립 저장소·서비스·DB를 생성하거나 기존 모듈 전체의 결합을 제거한 단계로 해석하지 않는다.

## 알림 수신자 포트

`notification.application.publicapi.NotificationRecipients`와 `NotificationRecipient(userId, deleted)`는 알림 context가 소유하고 사용자 context의 `NotificationRecipientsAdapter`가 구현한다. 알림 서비스는 사용자 Repository·Entity·역할 enum을 직접 사용하지 않는다.

사용자 context는 이미 알림 서비스를 호출한다. 포트를 알림 쪽에 두면 `notification → user`의 정적 의존을 제거하고 기존 `user → notification` 방향을 유지할 수 있다. 전체 context 그래프의 순환을 모두 제거한 단계는 아니다.

수신자가 없으면 기존처럼 `USER_NOT_FOUND`이고 삭제 플래그 Y/y인 계정만 무시한다. inactive/banned 계정도 삭제되지 않았다면 수신 대상이다. 관리자 조회는 기존 role 조회의 순서를 유지하며 정렬·중복 제거·로그인 가능 여부 필터를 추가하지 않는다. sender·target·URL과 `isRead=N`은 기존대로 전달한다.

어댑터는 기본 REQUIRED의 읽기 전용 트랜잭션을 사용한다. 호출자의 미커밋 사용자 상태·역할을 같은 트랜잭션에서 읽고, 별도 연결에서 업무·알림 쓰기가 먼저 보이지 않는 것을 실제 MySQL 검사로 확인한다. 새 연결·비동기·독립 commit이나 오류 전파 변경을 추가하지 않는다.

## 리뷰 작성의 계약 이력

`contract.application.publicapi.ReviewContractHistory`는 사용자와 방에 active 또는 ended 계약이 존재하는지만 boolean으로 반환한다. contract 내부의 `ReviewContractHistoryAdapter`가 기존 Repository query를 사용하며 Entity·ContractStatus·HTTP DTO를 공개 계약에 포함하지 않는다. 현재 query에는 날짜 조건이 없으며 일반 USER도 종료 이력으로 작성할 수 있다. 계정 역할·상태·삭제·현재 날짜 조건을 추가하지 않는다.

소비자는 `ReviewServiceImpl.createReview` 한 곳이다. 인증 → 방 존재/삭제 → 계약 이력 → 중복 → 필터/저장/파일/알림 순서를 유지한다. 이력이 없으면 기존 `REVIEW_NO_CONTRACT`, 이력이 있지만 중복이면 `REVIEW_DUPLICATE`다. query 장애를 false로 바꾸지 않는다. POST `/reviews`의 응답과 생성 필드는 그대로다.

어댑터의 readOnly 기본 REQUIRED는 기존 ReviewService의 쓰기 트랜잭션에 참여한다. 미커밋 계약 생성·상태 변경을 같은 트랜잭션에서 읽어야 하며 별도 commit·캐시·비동기를 추가하지 않는다. 공개 생성 메서드의 서비스 회귀와 실제 MySQL/Flyway `ReviewContractHistoryIntegrationTest`가 상태·사용자·방 조건, 날짜 무관 의미, 미커밋 상태의 가시성을 고정한다.

이 추출로 foreign Repository import는 63→62줄이며 foreign Entity import는 75줄로 유지된다. `review → contract`는 공개 계약 한 줄로 연결되고 `contract → review` import는 없다. 전체 context 그래프의 모든 순환을 해소했다는 뜻은 아니다.
