# 공용공간 예약 동시성

신규 예약은 같은 날짜의 08–10·10–12·12–14·14–16·16–18·18–20 고정 슬롯만 허용한다. `requested`와 `confirmed`가 시간을 점유하며 `cancelled`·`ended`는 점유하지 않는다. 기존 엄격 겹침 조회(`start < existingEnd && end > existingStart`)는 비정형 과거 예약과의 충돌도 검사한다.

두 repeatable-read 트랜잭션은 기존의 일반 존재 조회를 모두 통과할 수 있다. 엔티티에 선언돼 있던 `uq_space_time`은 Flyway 스키마에는 없었다. V15는 `space_reservations`에 다음 항목을 단일 ALTER로 추가한다.

- `active_slot`: 점유 상태에는 1, 나머지에는 NULL인 저장 생성 열.
- `uk_space_reservation_active_slot`: `(space_id, sr_start_at, sr_end_at, active_slot)` 유일 인덱스. 활성 동일 슬롯만 중복을 막고 비활성 이력은 여러 건 남길 수 있다.
- `row_version`: 상태 변경용 낙관적 버전. 취소 후 오래된 요청이 이전 상태를 다시 확정하는 것을 막는다.

새 입력끼리는 겹침이 곧 동일 슬롯이므로 DB 제약이 최종 중복 방지를 담당한다. 생성은 알림 전에 flush하며, 이 인덱스의 MySQL duplicate-key만 기존 `SPACE_RSV_409`로 반환한다. 다른 무결성 오류는 그대로 전파한다. 사용자 취소·관리자 확정·종료·취소도 알림 전에 상태 변경을 flush한다. 해당 예약의 버전 충돌은 HTTP 409 `SPACE_RSV_409_2`이며 새로고침 후 재시도하도록 안내한다. 응답 DTO에는 버전을 추가하지 않는다.

## 적용 전 데이터 확인

V1–V14를 수정하지 않는다. V15도 기존 예약을 취소하거나 삭제하지 않는다. 활성 동일 슬롯 중복이 이미 있으면 인덱스 생성이 실패하므로 적용 전에 업무 담당자가 해당 예약을 검토해야 한다.

```sql
SELECT space_id, sr_start_at, sr_end_at, COUNT(*) AS active_count
FROM space_reservations
WHERE sr_st IN ('requested', 'confirmed')
GROUP BY space_id, sr_start_at, sr_end_at
HAVING COUNT(*) > 1;
```

동일 슬롯 검사와 과거의 부분 겹침 검사는 구별한다. 기존 비정형 예약의 부분 겹침은 다음 읽기 조회로 확인할 수 있다. 이 변경이 과거 충돌 데이터를 정리하지는 않는다.

```sql
SELECT a.reservation_id, b.reservation_id, a.space_id
FROM space_reservations a
JOIN space_reservations b
  ON a.space_id = b.space_id AND a.reservation_id < b.reservation_id
 AND a.sr_start_at < b.sr_end_at AND a.sr_end_at > b.sr_start_at
WHERE a.sr_st IN ('requested', 'confirmed')
  AND b.sr_st IN ('requested', 'confirmed');
```

시간 변경·비정형 신규 입력·취소 복구 경로를 추가할 때는 이 보장을 다시 설계해야 한다. 현재는 생성 한 곳과 상태 변경 네 곳이며, inactive를 다시 active로 만드는 순차 전이는 허용하지 않는다. AI 추천 등 읽기 전용 경로의 기존 존재 조회와 트랜잭션 격리는 변경하지 않는다.
