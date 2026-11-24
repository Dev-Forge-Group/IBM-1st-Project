# 계정과 자산 소유권 계약

UniPlace는 한 MEMBER 계정에서 소비자와 부동산 소유주 활동을 함께 허용하고, 계정을 관리하는 STAFF 로그인은 분리한다. 권한은 현재 DB 상태로 매 요청 계산한다. JWT의 과거 역할 문자열, 요청 본문의 사용자 ID, 임대인 이름·전화번호는 권한의 근거가 아니다.

## 계정과 역할

| 구분 | 유효 권한과 경계 |
| --- | --- |
| MEMBER | 기본 CUSTOMER, 기존 API 호환용 USER. 소유주가 되어도 소비자 권한을 유지한다. |
| OWNER | MEMBER의 소유주 신청이 APPROVED이고 OWNER grant가 ACTIVE일 때 추가된다. 실제 자산 접근에는 별도의 membership이 필요하다. |
| TENANT | 활성 계약의 시작일·종료일에 한국 날짜가 포함되고, 계약 사용자·계약 ID·건물이 일치하는 입주자와 유효한 자산이 있을 때 파생된다. 저장된 `user_role=tenant`만으로 얻지 못한다. |
| STAFF | 계정 관리용 ADMIN. 소비자·소유주·입주자 업무 권한을 상속하지 않는다. 비공개 계약 파일이나 익명 커뮤니티 작성자를 전역 열람하지 못한다. |

OWNER 승인·취소 및 계약 관계 변경은 기존 access token의 다음 요청부터 반영한다. STAFF의 ADMIN 권한을 해제해도 MEMBER 소비자가 되지 않는다. 일반 역할 변경 API로 MEMBER를 ADMIN으로 전환하거나 TENANT를 직접 부여할 수 없다. STAFF가 소비자·소유주 활동을 하려면 별도 MEMBER 계정을 사용한다.

## 신청, 자산 검증과 공개

1. MEMBER가 소유주 신청 사유를 제출한다. STAFF는 심사 사유를 남겨 승인 또는 거절한다.
2. 승인된 OWNER가 건물을 등록하면 작성자에게 CREATED/PENDING membership을 만든다. 작성자는 자기 draft를 관리할 수 있지만 공개 목록·상세·파일·AI 조회에서는 숨긴다.
3. OWNER가 자산 권리를 입증하는 `proofReference`를 제출하고 STAFF가 별도 심사한다. 소유주 자격 승인만으로 자산을 공개하지 않는다.
4. 기존 건물·방에 대한 CLAIMED/PENDING 신청은 관리권을 주거나 기존 공개 자산을 숨기지 않는다. 실제 권리 검토 후 ACTIVE로 승인한다. 승인 후에도 소유주 자격이 활성 상태여야 한다.

CREATED 자산의 첫 심사 전까지만 초안 편집을 허용한다. 거절·회수 뒤 증빙을 다시 제출해도 재승인 전까지 편집권과 공개 상태가 돌아오지 않는다.

건물 소유권은 기본적으로 소속 방에 적용한다. 별도로 승인된 개별 방 membership은 해당 방의 운영 범위를 정하며 건물 전체의 관리권이나 다른 소유주의 개인정보를 주지 않는다. 방 이동은 기존 자산과 대상 건물 권한을 검증한다. 목록의 필터·페이지·count에도 같은 소유권 범위를 적용한다.

이전 자산은 소유주가 지정되지 않은 공개 조회 전용 상태로 남는다. 기존 임대인 문자열로 계정이나 OWNER membership을 자동 연결하지 않는다. 새 투어·임대 계약·공용공간 예약에는 검증된 자산 운영자가 필요하다. 공개 방 상세의 `verifiedRentalOwner`가 false이면 방 상세와 직접 계약 신청 화면에서 신청 가능 조건을 안내한다.

## 계약, 입주자와 파일

계약의 `userId`는 소비자다. 계약 생성 시 검증된 자산 소유주를 `contract_owner_parties`에 별도로 기록한다. 이후 자산 소유주가 바뀌어도 과거 계약 당사자를 자동 교체하거나 계약 이력을 새 소유주에게 공개하지 않는다.

OWNER도 다른 소유주의 방에는 소비자로 신청할 수 있다. 자기 방에 대한 계약 신청과 동일 계정의 양쪽 계약 관리는 거절한다.

소비자는 본인 계약을 읽는다. OWNER의 계약 열람에는 현재 유효한 OWNER 자격과 기록된 계약 당사자 관계가 필요하며, 변경·입주자 관리에는 현재 자산 관리권도 필요하다. STAFF의 전역 업무 우회 권한은 없다. 계약 활성화·종료는 입주자와 방 점유를 갱신하지만 사용자 저장 역할을 USER/TENANT로 덮어쓰지 않는다.

CONTRACT·BUILDING·ROOM·SPACE 파일 URL은 `/files/{id}/view|download` proxy를 사용한다. 읽기와 쓰기마다 실제 자산·계약 권한을 확인하며 여러 파일 변경은 모든 대상을 검증한 뒤 실행한다. 공개 자산 파일은 공개 조회할 수 있고 draft 파일은 허용된 OWNER만 접근한다. `/files/admin/**`와 해당 유형의 `/static-files/**` 직접 경로는 폐쇄한다.

## API와 화면 경로

아래 API 경로는 서버 context path `/api`를 제외한 값이다. 화면 경로와 API 경로를 섞지 않는다.

| 목적 | API |
| --- | --- |
| 본인 소유주 신청 조회·제출 | `GET/POST /users/me/owner-application`, 제출 `{applicationNote}` |
| 본인 자산 증빙 조회·제출 | `GET/POST /users/me/asset-claims`, 제출 `{resourceType, resourceId, proofReference}` |
| STAFF 소유주 심사 | `GET /admin/owner-applications?status=...`, `POST /admin/owner-applications/{userId}/approve|reject`, `{reviewNote}` |
| STAFF 자산 심사 | `GET /admin/asset-claims?status=...`, `POST /admin/asset-claims/{BUILDING\|ROOM}/{membershipId}/approve|reject`, `{reviewNote}` |
| STAFF 계정 관리 | `/admin/users/**` |
| OWNER 자산 업무 | `/owner/buildings`, `/owner/rooms`, `/owner/spaces`, `/owner/property-dashboard` |
| OWNER 계약·입주자 업무 | `/owner/contracts`, `/owner/residents` |
| OWNER 예약 업무 | `/owner/tour-reservations`, `/owner/space-reservations` |

소비자용 기존 공개 탐색과 본인 계약·예약 API를 유지한다. 구 `/admin/buildings|rooms|spaces|contracts|residents|property-dashboard|tour-reservations|space-reservations` 업무 API는 닫고 OWNER API로 이동한다. OWNER 예약 화면과 알림 링크는 `/owner/reservations/tours`, `/owner/reservations/spaces`이며 API URL과 다르다.

새 계약 알림은 `/owner/contracts?contractId=...`에서 해당 계약을 연다. 목록의 계약 번호 필터에도 계약 당사자 범위가 적용된다.

## V17과 기존 데이터 전환

`V17__separate_member_staff_and_property_ownership.sql`은 `users.account_kind`를 추가하고 기존 admin 계정을 STAFF로 분류한다. MEMBER의 기본 소비자 grant, 소유주 신청·역할 grant·건물/방 membership·계약 당사자·심사 이력 테이블과 공통 공개 범위 view를 추가한다. 적용된 V1–V16은 수정하지 않는다.

기존 자산·계약에는 OWNER와 계약 당사자를 자동 backfill하지 않는다. 담당자가 실제 자산 증빙과 계정 신원을 확인한 뒤 신청·심사 절차로 연결한다. 과거 계약 접근 관계를 전환할 때는 자산 소유권과 별도로 계약 당사자 증빙·근거를 검토해야 한다. 현재 자산 승인 API가 과거 계약 당사자를 자동 생성하지 않는다.

이 문서는 구현 계약이다. 운영 DB에 V17을 적용했거나 운영 소유권 전환을 완료했다는 의미가 아니다. 운영 적용에는 대상 DB·백업/복구·배포 순서·기존 데이터 검토를 확정한 별도 실행이 필요하다. 테스트 fixture의 직접 SQL grant는 합성 검증 전용이며 운영 전환 절차가 아니다.

## 외부 AI 인증 전달 경계

Spring의 `POST /api/v1/ai/tools/execute`는 설정된 `X-Internal-Token`으로 내부 호출자를 확인한다. 이 토큰은 사용자의 신원이나 OWNER·ADMIN 권한을 대신하지 않는다. 공개 도구는 공개 자산 view로 제한하며 `query_database_admin` 업무 전체 조회 도구는 거절한다.

`query_my_data`는 현재 SecurityContext의 유효한 MEMBER를 요구한다. 본문 `userId`가 있으면 인증된 사용자와 같아야 한다. 임의 `userId`·`userSegment`·ADMIN 문자열만 전달해서 개인 데이터를 읽을 수 없다. 외부 AI의 개인 조회 callback에는 내부 토큰과 함께 검증 가능한 사용자 인증을 전달해야 한다. 현재 구현에서 가능한 경로는 유효한 사용자 `Authorization: Bearer ...`가 Spring JWT 필터를 통과하는 것이다. 별도 위임 토큰 방식은 구현된 것으로 간주하지 않는다.

현재 Spring `AiGatewayImpl`은 외부 AI로 사용자 Bearer를 자동 전달하지 않고, 별도 `uniplace-ai`의 tool client도 내부 토큰만 보내는 상태다. 따라서 이 두 구간의 인증 전달을 별도로 구현·검증하기 전에는 개인 도구 callback이 `AUTH_REQUIRED`(401)로 거절된다. 공개 도구의 성공이나 SQL projection 단위 검증이 개인 AI 여정의 통과를 뜻하지 않는다. 토큰을 prompt·도구 인자·로그에 넣어 전달하는 방식은 사용하지 않는다.

### AI 생성 문서의 제공 중단

AI 발주서와 결제 요약 XLSX는 생성 요청자와 파일을 연결할 수 있을 때까지 제공을 중단한다. 기존 `AI_DOCUMENT` 행에는 검증 가능한 요청자 정보가 없으며, 외부 AI의 기존 업로드 호출은 내부 토큰만 보낸다. 건물 번호, 파일명, AI 응답의 파일 번호로 문서 소유자를 추정하지 않는다.

Spring은 해당 XLSX 생성 요청, 기존 파일명 다운로드 proxy, 내부 업로드, 공통 파일 조회·변경 및 로컬 정적 파일 경로를 차단한다. 기존 파일과 메타데이터는 보존한다. 일반 결제 상태 요약과 계약 파일은 각자의 권한 정책을 유지한다.

재개하려면 인증된 생성 요청과 결과 파일을 연결해 같은 사용자에게만 읽기를 허용하고, 외부 AI 원본 다운로드와 기존 스토리지 URL에도 같은 접근 경계를 검증해야 한다. 이 변경은 IBM-1st-Project의 앱 경계에 적용된다. 외부 서비스와 운영 스토리지의 노출 상태는 별도로 확인해야 한다.

필수 회귀 범위와 실제 실행 여부 확인 방법은 [PR 검증](ci.md)을 따른다.
