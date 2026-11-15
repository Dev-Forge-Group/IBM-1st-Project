# 투어 전화 표기와 기존 예약 호환

2026-11-16 KST. 전화번호의 하이픈 유무 때문에 같은 전화·PIN으로 과거 예약을 조회·취소하지 못하거나 다른 방에 같은 시간의 예약을 만들 수 있던 경로를 맞춘다. 관리자 복구와 Java AI의 `MyTourUseCase` 직접 조회도 같은 Repository 계약을 사용한다.

## 비교 범위

- 길이20 이내의 ASCII 숫자·하이픈으로만 된 입력에서 하이픈을 지운 값이 기존 UI의 `01[016789][0-9]{7,8}` 규칙에 맞으면 같은 국내 모바일 번호로 비교한다. 비표준 위치·반복 하이픈도 이전 UI가 허용한 표현이다.
- 저장된 `tourTel`과 응답은 원문 그대로다. 새 예약의 저장 형식을 바꾸거나 과거 데이터를 rewrite하지 않는다. 날짜·상태·방 겹침·알림·응답의 오류 코드도 기존 계약을 따른다.
- 공백·tab·다른 dash·문자·국제번호·Unicode 숫자를 제거하거나 국가번호를 합치지 않는다. 이들 과거 값은 기존 raw 조회를 유지하며 새 표기 alias는 만들지 않는다.
- 새 전화 alias는 입력 PIN이 ASCII 숫자4자리일 때만 사용한다. 저장 PIN과 입력 PIN도 바이트 기준으로 같아야 한다. 공개 DTO 검증을 거치지 않는 AI 직접 Repository 소비도 같은 제한을 받는다. PIN을 trim·정규화하지 않는다.

기존 raw 전화·PIN 비교는 DB collation을 포함해 그대로 유지한다. 따라서 이번 수선이 과거 raw 비교의 모든 Unicode 동등성까지 제거한 것은 아니다. 새 alias 분기의 전화·PIN에는 `HEX` 비교를 사용해 case/accent-insensitive collation으로 credential 접근이 넓어지지 않게 한다. 지원하지 않는 과거 문자열에 대해 전화 표현을 합치는 동작을 새로 추가하지 않는다.

## 기존 소비 계약

기존 Repository 메서드 이름을 default wrapper로 유지해 서비스와 AI의 직접 호출을 함께 처리한다. JPQL을 유지하므로 `tourId`, `tourStartAt` 등의 entity 속성 정렬, 페이지 count, 상태 enum 바인딩도 그대로다. lookup은 취소·완료 이력을 계속 포함하며, AI는 결과가 없으면 기존처럼 빈 `items`를 전달한다. HTTP lookup의 빈 결과 오류와 AI의 빈 목록 계약을 합치지 않는다.

생성과 inactive→active 관리자 복구의 전화 중복은 같은 시작·종료 시각의 active 이력에 적용한다. 다른 방도 포함하며 기존409_2를 사용한다. 방의 엄격 overlap, inactive 재사용, ended 취소 거절은 바뀌지 않는다.

## 검증과 남은 범위

`TourTelephoneCompatibilityIntegrationTest`는 실제 MySQL·Flyway·JWT/MockMvc에서 양방향 조회·취소, 원문 보존, 비표준 하이픈 과거 이력, 다른 방 중복과 관리자 복구, PIN·Unicode 경계, inactive 이력, camelCase 정렬·페이지 count, 실제 `MyTourUseCase`의 첫10개·빈 목록을 검사한다. 외부 AI·알림·Slack은 mock하며 credential을 테스트 출력에 남기지 않는다. `TourTelephoneTest`는 지원 표현과 unsupported 입력·직접 소비 PIN 경계를 검사한다. CI는 이 MySQL suite가 누락·0개·skip이면 실패한다.

이 변경은 순차 요청의 표기 호환이다. 전화 유일성 DB 제약이나 모든 Tour writer의 동시성 보장을 추가하지 않았다. 함수 비교는 원시 전화 인덱스를 그대로 활용하기 어려우므로 운영 규모의 실행 계획과 조회 비용을 별도로 확인해야 한다. 정규 키의 backfill·인덱스·경쟁 제어는 IBM-03에서 다루며, 새 DB migration은 없다.
