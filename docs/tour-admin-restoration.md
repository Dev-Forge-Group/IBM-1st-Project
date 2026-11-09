# 관리자 투어 복구의 충돌 검사

관리자는 기존처럼 투어 상태를 requested·confirmed·cancelled·ended로 변경할 수 있다. cancelled/ended 이력을 requested/confirmed로 복구하면 시간을 다시 점유하므로, 상태를 바꾸기 전에 생성과 같은 전화 중복·방 겹침 검사를 수행한다.

관리자 상태 endpoint는 기존 경로·ADMIN 보안 필터·Void 성공 응답을 유지하고, 쓰기는 `TourReservationService.changeStatus`의 하나의 기본 REQUIRED 트랜잭션으로 위임한다. inactive→active에서만 전역 raw전화+정확한 시작/종료 중복을 먼저 확인하고 방의 엄격 overlap을 확인한다. inactive인 본인 행은 기존 query에서 제외된다. active→active는 자신과 충돌하지 않으며, 빈 시간의 정상 복구·인접 시간·inactive 상태 변경도 유지한다. 과거 비정형 이력에 신규 입력 validator를 다시 적용하지 않는다.

실패하면 기존 상태와 활성 행 수가 유지되며 추가 알림은 발생하지 않는다. 전화 중복은 기존 HTTP409 `TOUR_RSV_409_2`다. 방 시간 충돌은 생성·복구 모두 HTTP409 `TOUR_RSV_409_1`로 정정했다. 이전 생성 정책이 잘못 사용하던 `TOUR_RSV_400_6`은 실제 방/건물 소속 오류에만 남는다. 프런트는 이미 이 두 충돌 코드를 처리한다.

`AdminTourRestorationIntegrationTest`는 실제 JWT·SecurityFilterChain·MockMvc·MySQL/Flyway로 exact/legacy 방 충돌·다른 방의 전역 전화 중복·정상 복구·접근 거절·404·성공 응답을 검사한다. 외부 Notification/Slack은 mock한다.

이번 보장은 순차 복구의 정책 일치다. 전화번호 정규화와 동시에 실행되는 생성/복구·오래된 상태 쓰기는 IBM-02/03의 별도 작업이다. 새 DB migration·endpoint·권한 모델은 추가하지 않았다.
