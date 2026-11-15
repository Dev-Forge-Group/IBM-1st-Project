# 투어 예약 동시성

IBM-03은 `TourReservationService.create`, `cancel`, `changeStatus`의 쓰기를 같은 DB 잠금으로 직렬화한다. 관리자 상태 endpoint는 계속 `changeStatus`로 위임한다. 다른 방의 전역 전화 중복과 비정형 과거 예약의 방 겹침을 함께 다루며, [전화 표기 호환](tour-telephone-compatibility.md)·PIN·권한·원문·응답·페이지 계약을 유지한다.

## 잠금과 현재 상태

[V16](../uni-place-backend/src/main/resources/db/migration/V16__serialize_tour_reservation_writers.sql)은 `tour_reservation_write_guard` 테이블과 `guard_id=1`인 한 행만 추가한다. PK와 CHECK로 guard의 식별자를 고정한다. V1–V15와 기존 예약 행은 수정하지 않으며 전화 정규화·backfill·예약 유일 인덱스·버전 열을 추가하지 않는다. 실제 Flyway 스키마에 없던 엔티티의 room/time unique 선언도 제거한다.

세 writer는 입력의 순수 검증 뒤 첫 DB 동작으로 guard 행을 `SELECT ... FOR UPDATE`로 잠근다. JDBC는 JPA와 같은 DataSource의 REQUIRED 트랜잭션에 참여하므로 잠금은 가장 바깥 트랜잭션의 commit/rollback까지 유지된다. guard가 없으면 쓰기가 실패하며 런타임에서 새 guard를 만들어 계속하지 않는다. 외부 호출자의 트랜잭션이나 격리 수준을 `REQUIRES_NEW` 등으로 바꾸지 않는다.

guard만 잠그고 일반 조회를 계속하면, 호출자가 이미 연 MySQL REPEATABLE READ snapshot으로 충돌을 놓칠 수 있다. 따라서 전화·방 충돌은 최대 한 행을 가져오는 `FOR UPDATE` current read로 확인한다. 취소·상태 변경 대상도 잠금 뒤 refresh하여 같은 영속성 컨텍스트에 남은 과거 상태를 사용하지 않는다. 변경은 알림이나 반환 전에 flush한다.

- 생성과 inactive→active 복구는 전역 전화+정확한 시작/종료 중복을 먼저 검사하고 방의 엄격 overlap을 검사한다. 충돌은 각각 기존 `TOUR_RSV_409_2`, `TOUR_RSV_409_1`이다.
- `requested`·`confirmed`는 시간을 점유하고 `cancelled`·`ended`는 점유하지 않는다. 비정형 과거 간격도 `start < existingEnd && end > existingStart`를 따르며 끝점만 접하면 허용한다.
- 취소는 최신 상태가 cancelled이면 기존 응답을 반환하고 추가 알림을 보내지 않는다. ended의 사용자 취소 거절, inactive 재사용, 관리자 정상 복구·active→active 전이는 유지한다.

## 알림과 처리량

DB 관리자 알림은 예약과 같은 트랜잭션에 남는다. Tour 생성 Slack 호출은 실제 commit 이후에 실행하므로 외부 HTTP 대기가 guard를 붙잡지 않고 rollback된 예약의 Slack도 보내지 않는다. callback 오류가 이미 commit된 예약의 성공을 실패 응답으로 바꾸지 않게 처리한다. Slack은 best effort이며 outbox·내구성 있는 재전송·정확히 한 번 전달을 보장하지 않는다.

전역 guard는 기존 raw 전화의 DB collation 비교와 하이픈 호환, 다른 방의 전화 충돌, legacy 방 overlap을 하나의 순서로 보호하기 위한 선택이다. 모든 Tour 쓰기가 기다리는 처리량 비용이 있으며 DB 알림도 이 구간에 포함된다. 전화 함수 비교의 실행 계획·잠금 대기·트랜잭션 시간을 운영 규모에서 측정한 뒤 분할을 검토한다. 일반 읽기와 AI 가용 시간 조회는 guard를 잠그지 않으며, 가용 목록 자체가 이후 생성 성공을 보장하지 않는다.

## 기존 이력 확인과 전환

V16은 guard만 추가하므로 과거 충돌 때문에 유일 인덱스 생성이 실패하는 migration은 아니다. 다음 읽기 조회는 기존 활성 충돌을 업무적으로 검토하기 위한 자료다. 예약을 삭제·취소하거나 원문을 출력하지 않는다.

```sql
SELECT a.tour_id, b.tour_id, a.room_id
FROM room_reservation a
JOIN room_reservation b
  ON a.room_id = b.room_id AND a.tour_id < b.tour_id
 AND a.tour_start_at < b.tour_end_at AND a.tour_end_at > b.tour_start_at
WHERE a.tour_st IN ('requested', 'confirmed')
  AND b.tour_st IN ('requested', 'confirmed');
```

전화 중복은 동일 시작/종료 시각에서 raw DB 비교 또는 지원하는 ASCII 모바일 표기가 일치하는 쌍을 확인한다. `HEX` gate는 SQL 정규식의 문자 비교와 별개로 ASCII 숫자·하이픈만 허용한다.

```sql
WITH active AS (
  SELECT tour_id, tour_tel, tour_start_at, tour_end_at,
         CASE WHEN CHAR_LENGTH(tour_tel) <= 20
                    AND HEX(tour_tel) REGEXP '^(2D|3[0-9])+$'
                    AND REGEXP_LIKE(REPLACE(tour_tel, '-', ''), '^01[016789][0-9]{7,8}$', 'c')
              THEN REPLACE(tour_tel, '-', '') END AS canonical_tel
  FROM room_reservation
  WHERE tour_st IN ('requested', 'confirmed')
)
SELECT a.tour_id, b.tour_id, a.tour_start_at, a.tour_end_at
FROM active a
JOIN active b
  ON a.tour_id < b.tour_id
 AND a.tour_start_at = b.tour_start_at AND a.tour_end_at = b.tour_end_at
 AND (a.tour_tel = b.tour_tel
      OR (a.canonical_tel IS NOT NULL AND b.canonical_tel IS NOT NULL
          AND HEX(a.canonical_tel) = HEX(b.canonical_tel)));
```

guard를 따르지 않는 구버전 앱과 새 앱이 함께 쓰는 동안에는 전체 보장이 성립하지 않는다. V16과 신규 코드를 모든 Tour writer에 적용하고 구버전 writer를 종료한 뒤 전환을 완료한다. 새 코드가 동작하는 동안 guard를 삭제하거나 migration 이력을 되돌리지 않는다. 수동 SQL·직접 Repository 쓰기와 다른 외부 writer는 이 프로토콜을 우회하므로 별도 통제가 필요하다.

관리자 API는 expectedVersion/currentStatus를 받지 않는다. 따라서 이미 취소된 뒤 도착한 오래된 UI의 `confirmed` 의도와 정상 복구 요청을 구별하지 않는다. 잠금 뒤 실제 상태로 복구 충돌을 재검사하는 보장과 모든 오래된 UI 요청을 거절하는 보장은 다르다.

## 검증

`TourReservationConcurrencyIntegrationTest`는 실제 MySQL/Flyway에서 외부 REPEATABLE READ snapshot과 사전 로드한 엔티티를 가진 호출자를 경쟁시킨다. 같은 방 생성, 다른 방의 전화 별칭 중복, 생성·복구와 복구·복구, 취소·상태 변경, 인접/독립/비정형 이력과 inactive 재사용을 확인한다. Slack은 mock으로 rollback·commit 이후 호출·전달 실패와 잠금 해제를 검사한다. CI는 이 suite가 누락·0개·skip이면 실패한다. 기존 전화 호환·관리자 HTTP/JWT·가용 시간 회귀도 함께 유지한다. 로컬 결과와 원격 Actions·운영 배포·실제 외부 Slack 전달은 별도 증거다.
