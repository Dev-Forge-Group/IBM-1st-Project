# JWT 식별자와 현재 회원 권한

IBM-06은 역할 변경이 커밋된 뒤 다음 요청에서 현재 DB 역할을 인가에 사용하도록 한다. 기존 필터는 회원의 로그인 가능 상태만 조회하고 JWT 발급 당시의 role로 principal과 권한을 만들었다. 따라서 관리자가 admin을 user로 내리거나 마지막 계약 종료로 tenant가 user가 되어도 기존 access token은 만료 전까지 이전 권한을 유지했다.

## 요청 인증 계약

서명·만료·`typ=access`·비어 있지 않은 subject와 role을 먼저 검증한다. 회원 JWT의 subject는 회원을 식별하며, role claim은 발급 당시 값이다. 서버의 현재 역할은 회원 행의 `user_role`이다.

- 회원이 존재하고 `canLogin()`이 참이며 현재 역할이 있어야 인증된다. 누락·비활성·정지·논리 삭제 회원과 역할 없는 회원은 `UNAUTHORIZED`다.
- `AuthUser.role`, principal의 authorities와 `Authentication.authorities`는 모두 현재 DB 역할에서 만든다. 관리자 경로의 `hasRole`과 principal을 읽는 도메인 로직이 같은 역할을 본다.
- 관리자 강등·tenant 해제는 이전 권한을 회수하고, 승격은 새 권한을 반영한다. 역할 claim과 DB 값이 다르다는 이유로 토큰을 폐기하거나 재로그인을 요구하지 않는다.
- 서명된 guest access token은 기존처럼 DB 회원 조회 없이 guest로 남는다. 같은 subject의 회원이 생겨도 이 토큰을 회원 권한으로 승격하지 않는다. 관리자·입주자 전용 경로는 허용하지 않는다.
- 서명·만료·타입·필수 claim이 잘못되면 기존 token 오류 계약을 따른다. 토큰 없는 요청의 공개/보호 경로와 `/auth/guest-token`의 기존 예외는 유지한다.

HTTP 응답·DTO·JWT claim 이름과 token 발급 형식은 변경하지 않는다. UI가 캐시한 역할은 서버 인가의 근거가 아니며, 브라우저가 현재 사용자 정보를 다시 조회하기 전까지 메뉴가 과거 상태로 보일 수 있다.

## 갱신과 경계

기존 refresh 서비스는 저장된 refresh token·subject·device·만료·회원 상태를 검사하고 token을 회전한 뒤 현재 DB 역할로 새 access token을 발급한다. 역할 변경만으로 refresh token을 폐기하거나 회전 정책을 바꾸지 않는다. 기존 device 불일치·재사용 거절과 응답 형식을 보존한다.

보장은 역할 변경이 커밋된 뒤 새 요청이 DB를 읽는 시점에 적용된다. 이미 인가된 작업을 중단하거나 역할 변경과 업무 실행을 원자적으로 잠그지 않는다. 오래된 외부 트랜잭션·수동 writer·다른 서비스의 JWT 직접 해석까지 같은 보장으로 확대하지 않는다. 계약 서비스가 DB 역할을 변경하는 기존 규칙은 [계약 lifecycle](contract-lifecycle.md)을 따른다.

현재 토큰에 별도 session/version을 추가하지 않는다. 로그아웃·비밀번호 변경 시 모든 access token의 즉시 폐기, refresh 재사용 처리의 트랜잭션 정책 재설계, 역할 정책 자체의 변경은 이 수정의 범위가 아니다. 추가 DB 조회는 기존 회원 상태 조회를 재사용한다.

## 검증

`JwtAuthorityIntegrationTest`는 실제 MySQL8.4·Flyway·JWT·SecurityFilterChain·HTTP를 연결한다. 관리자 역할 PATCH와 계약 비활성화가 커밋된 뒤 같은 access token을 재사용해 현재 principal과 권한, 허용·거절을 검사한다. guest, 회원 상태, token 오류와 refresh의 현재 역할·회전 계약도 검사한다. 테스트용 인증 관찰 endpoint는 test source에만 있으며 제품 API에 추가하지 않는다.

[CI 검사기](ci.md)는 이 suite의 누락·0개·skip을 실패로 처리한다. 이 회귀는 실제 브라우저, OAuth 공급자, 운영 IAM·원격 CI나 배포 성공의 증거가 아니다.
