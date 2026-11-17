# 계약 문서·서명 파일의 접근 권한

IBM-09는 `CONTRACT` 파일을 해당 계약 소유 회원과 현재 관리자에게만 제공한다. 기존 파일 API는 계약 목록·메타데이터·view/download에도 소유권 검사가 없었고, `/files/admin` 경로가 공개 파일 GET 규칙에 포함되어 삭제 파일까지 읽을 수 있었다. 화면의 비밀번호 확인만으로 서버 접근이 보호되지는 않았다.

## 서버 경계

| 경로/동작 | 계약 파일 정책 |
| --- | --- |
| 일반 목록·메타데이터·view/download | 무인증 401, guest·다른 회원 403, 소유 회원 또는 현재 admin 허용. 일반 경로는 삭제 파일을 계속 제외한다. |
| `/files/admin` 및 하위 경로 | 모든 HTTP method에 ADMIN 규칙을 먼저 적용한다. 관리자 조회 서비스도 관리자 권한을 확인하며 삭제 파일의 기존 관리 기능은 유지한다. |
| CONTRACT upload·parent delete·bulk 대상·순서 변경 | 실제 계약 대상의 권한을 storage I/O·변경 전에 확인한다. batch에 다른 소유자의 계약 파일이 섞이면 부분 변경하지 않는다. |
| `/static-files/CONTRACT` 및 하위 경로 | 소유자·관리자도 원시 정적 경로로 우회하지 못한다. 인증 파일 API를 사용한다. 대소문자·decoded prefix·query를 포함한 차단을 합성 파일로 확인한다. |
| 파일 DTO·계약 DTO의 URL | CONTRACT는 storage의 직접 URL을 생성하지 않고 파일 API proxy를 반환한다. 관리자 URL도 관리자 파일 API다. |

`FileRefType`의 canonical `CONTRACT` parent 관계가 권한 대상이다. 저장된 타입의 trim·대소문자 표현도 같은 보호 대상으로 판단한다. 소유 여부는 계약 데이터의 사용자 연결로 확인하며, 확인할 수 없는 연결은 일반 회원에게 제공하지 않는다. 현재 관리자 판단은 JWT 필터가 현재 DB 역할로 만든 인증을 사용한다. 비활성·정지·논리 삭제 회원과 만료/잘못된 token은 기존 인증 계약을 따른다.

`FileService`의 일반·bulk 조회와 변경에서 같은 정책을 사용한다. 공개 ROOM/BUILDING 등 다른 파일의 기존 소비를 일괄 비공개로 바꾸지 않으며, 다른 도메인의 generic 파일 정리도 기존 동작을 유지한다. 계약 파일 응답은 `no-store`이며 허용되지 않은 요청은 private metadata/bytes를 반환하거나 storage read/store/delete를 실행하지 않는다.

소유권 포트는 file context가 소유하고 contract context의 adapter가 구현한다. 기본 REQUIRED readOnly는 호출자의 쓰기 트랜잭션에 참여하므로 계약 생성 직후 미커밋 계약의 서명 생성도 소유자를 확인할 수 있다. 새 연결·캐시·독립 commit이나 query 실패를 false로 숨기는 동작을 추가하지 않는다.

## 화면 소비

내 계약 문서의 미리보기·저장과 관리자 목록/상세의 다운로드는 `fileId`로 고정된 API에 인증 요청을 보내 Blob을 사용한다. 외부 URL·raw storage URL·token query fallback을 사용하지 않는다. 공개 방 이미지의 기존 URL 소비는 유지한다.

401은 기존 shared refresh 절차를 사용하며, JSON/HTML 응답을 문서 Blob으로 표시하지 않는다. 오류·로딩·재시도를 구분하고, 문서 변경·닫기·unmount 이후의 늦은 응답을 화면에 반영하지 않는다. object URL과 임시 다운로드 anchor는 사용 종료 시 정리한다. 비밀번호 확인 UI는 추가 사용자 확인이며 서버의 객체 인가를 대체하지 않는다. 재로그인으로 받은 token은 기존 token store에 저장해 뒤이은 인증 요청과 세션 갱신이 같은 결과를 사용한다.

## 검증과 한계

`ContractFileAccessIntegrationTest`는 실제 JWT·보안 필터·RANDOM_PORT HTTP·MySQL/Flyway·실제 FileService를 사용한다. Storage는 합성 PNG와 sentinel로 대체하고 정적 우회 검사는 별도 `/tmp` 디렉터리의 합성 파일만 사용한다. actor별 일반·관리자 read/write, 삭제 파일, private URL proxy, public ROOM 유지, bulk·미커밋 소유권과 storage 미호출을 검사한다. 실제 고객 파일·S3 객체를 조회하지 않는다.

React 회귀는 인증 Blob 요청과 private preview/download, 두 관리자 다운로드 위치, 늦은 응답과 object URL 정리, 공개 이미지 유지·token 갱신을 검사한다. 로컬 브라우저 결과는 별도 합성 사용자·문서의 실제 API와 화면에 한정한다. [필수 CI](ci.md)는 해당 MySQL suite의 누락·0개·skip을 실패로 처리하며 기본 CI가 브라우저 실행까지 했다고 주장하지 않는다.

이 수선은 애플리케이션의 canonical CONTRACT 파일 경계다. 기존에 전달된 직접 storage URL·사본·브라우저에 이미 읽힌 bytes를 회수하지 않는다. 운영 bucket/ACL/CDN의 비공개 설정과 과거 URL·cache 회수는 확인·변경하지 않았으며 운영 보장은 별도 검증이 필요하다. 잘못 분류된 과거 파일의 재분류나 다른 파일 도메인의 권한 재설계, 비밀번호 재확인 ticket·freshness 정책은 포함하지 않는다.
