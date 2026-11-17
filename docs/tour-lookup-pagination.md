# 투어 조회의 상태 필터와 페이지 문맥

IBM-08은 전체 이력에서 상태를 먼저 고른 뒤 페이지를 나눈다. 기존 화면은 서버가 반환한 현재 10건만 필터링하고 남은 항목 수로 다음 버튼을 막았다. 다른 페이지에 일치하는 예약이 있어도 0건으로 보이거나 접근하지 못했고, 정확히 10건인 마지막 페이지에서는 불필요한 다음 조회가 가능했다.

## 서버 계약

`POST /tour-reservations/lookup`의 전화·PIN body와 `PageResponse`의 `content`, 1-based `page`, `size`, `totalElements`, `totalPages`를 유지한다. 선택적인 query `status`에는 `requested`, `confirmed`, `cancelled`, `ended` enum을 사용한다. 전체 조회는 status를 생략하며 `all` 문자열을 보내지 않는다.

- 상태를 지정하면 같은 전화/PIN과 상태 조건을 content·count에 모두 적용한 뒤 정렬·페이지를 계산한다. `totalElements`와 `totalPages`는 해당 credential과 상태의 전체 결과다.
- 전화 비교는 기존 `CREDENTIAL_MATCH`와 `canonicalForCredentials`를 그대로 쓴다. 전화 원문 저장·응답, raw 비교와 제한된 alias/PIN 바이트 비교를 넓히거나 정규화하지 않는다. 응답에 PIN을 추가하지 않는다.
- 상태를 지정한 유효 credential의 0건 또는 범위 밖 페이지는 HTTP200의 빈 content와 정확한 메타데이터다. filtered total이 0이면 같은 credential의 전체 이력 존재 여부를 확인한다. 잘못된 전화/PIN은 기존 HTTP400 `COMMON_400`이다.
- status를 생략한 기존 HTTP 빈 조회·범위 밖 페이지의 400 계약은 유지한다. 기존 Repository wrapper와 AI 직접 조회의 첫 10건·빈 목록 계약도 유지한다.
- 잘못된 status enum은 [요청 파라미터 계약](tour-http-journey.md)에 따라 400이며 원래 입력이나 내부 예외를 응답에 노출하지 않는다.

새 migration·정규 전화 키·변경 이력 정리·쓰기 정책을 추가하지 않는다. [V16 투어 writer](tour-reservation-concurrency.md)와 취소/복구의 기존 상태·오류는 그대로다. 요청별 조회 시점의 결과이며 여러 페이지 요청 사이의 snapshot이나 동시 변경 의도 검출을 보장하지 않는다.

새 화면의 상태별 조회는 status query를 지원하는 backend와 함께 반영해야 한다. 구버전 서버는 이 query를 무시하므로 상태별 결과를 제공하지 않는다. 기존 status 없는 클라이언트는 새 backend에서도 기존 계약을 사용한다.

## 화면 계약

화면은 서버 content를 그대로 표시하고 항상 서버 `totalElements`를 사용한다. 다음 페이지는 `page < totalPages`, 이전은 `page > 1`로 판단한다. 현재 보이는 행 수를 페이지 이동 조건으로 쓰지 않는다.

상태 변경은 같은 조회 credential로 1페이지부터 서버에 재조회한다. 빈 상태 결과에서도 필터를 유지하여 전체로 돌아갈 수 있다. 전화/PIN 입력을 변경하면 기존 조회 문맥·결과를 무효화하고 새 조회를 요구한다. 입력 중인 값으로 과거 결과를 취소하지 않는다.

조회는 가장 최근 요청만 성공·오류·로딩 종료를 반영한다. 무효화되거나 뒤늦은 응답은 페이지 보정 등 후처리도 시작하지 않는다. 상태 전환이 겹쳐도 과거 content나 count가 새 필터를 덮지 않는다.

취소는 표시된 결과의 credential과 ID를 사용한다. 확인·취소·재조회·페이지 보정이 끝날 때까지 중복 취소와 조회 입력·필터·페이지 변경을 막는다. 성공 후 같은 필터·credential로 현재 페이지를 갱신하고, 상태 변경으로 그 페이지가 사라졌으면 `max(1,totalPages)`로 한 번 보정한다. 생성이나 취소 요청을 자동 재전송하지 않는다.

## 검증 범위

`TourLookupPaginationIntegrationTest`는 실제 MySQL·Flyway·RANDOM_PORT HTTP로 10건을 넘는 혼합 상태의 전역 count·정렬·페이지, 전화/PIN 분리·alias·원문·PIN 비노출, 빈 필터와 범위 밖 페이지·잘못된 credential/status, 취소 후 페이지 감소를 검사한다. 기존 전화 호환·AI·동시성 suite도 유지한다.

React 회귀는 mock API로 서버 페이지 메타데이터, 상태별 query·1페이지 초기화, 빈 필터, 취소 후 보정, 오래된 응답·오류·종료와 입력 변경·중복 취소를 확인한다. 실제 Chromium에서는 합성 이력 23건(대기 11·취소 12)으로 다른 페이지의 취소 이력을 조회하고, 대기 마지막 행을 취소한 뒤 페이지 복구와 대기 10·취소 13·전체 23건을 실제 API에 대조한다. 브라우저는 guest 조회 한 여정이며 운영 데이터·외부 공급자를 사용하지 않는다.

[CI 검사기](ci.md)는 이 HTTP/MySQL suite의 누락·0개·skip을 실패로 처리한다. 로컬 회귀·브라우저·전체 FE/BE·원격 CI·배포는 각각 실제 실행 증거로 구분한다.
