package org.myweb.uniplace.domain.ai.application.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.domain.reservation.domain.enums.TourStatus;
import org.myweb.uniplace.domain.reservation.repository.TourReservationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * AI Tool Executor — Spring 측 tool 실행 서비스.
 *
 * [핵심 변경]
 * 기존: 각 UseCase마다 Java 코드로 DB 조회 → 하드코딩된 필터/정렬
 * 변경: LLM이 생성한 SQL을 읽기 전용 트랜잭션의 JDBC로 직접 실행
 *      → 빌딩/테이블 추가 시 코드 변경 없음
 *      → 복잡한 JOIN, 집계 쿼리도 자연어로 처리 가능
 *
 * [보안]
 * SqlValidator로 SELECT 한 문장만 허용하고 위험 키워드를 차단한다.
 * 어떤 테이블의 어떤 행·컬럼을 읽을 수 있는지는 AiDataContract가 정하고,
 * AiSqlRewriter가 실행 전에 SQL이 읽는 테이블을 계약이 허용한 파생 테이블로 바꾼다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiToolExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final AiPublicReads publicReads;
    private final TourReservationRepository tourRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public AiToolResponse execute(AiToolRequest req) {
        String tool   = req.getTool();
        Map<String, Object> args = req.getArgs() != null ? req.getArgs() : Map.of();
        String userId = req.getUserId();

        log.info("[AiToolExecutor] tool={} userId={}", tool, userId);

        try {
            return switch (tool) {
                case "query_database"              -> executePublicSql(args);
                case "query_database_admin"        -> executeAdminSql(args, userId);   // AI 내부 집계용: requiresAuth 우회, userId 필요
                case "query_my_data"               -> executePrivateSql(args, userId);
                case "get_tour_available_slots"    -> getTourSlots(args);
                case "search_community_posts"      -> rows(publicReads.searchCommunityPosts(
                                                        getString(args, "keyword"), intOr(args, "limit", 5)));
                case "popular_community_posts"     -> rows(publicReads.popularCommunityPosts(
                                                        intOr(args, "days", 7), intOr(args, "limit", 3)));
                case "list_rooms_for_embedding"    -> rows(publicReads.roomsForEmbedding());
                case "classify_complain_priority"  -> AiToolResponse.fail("관리자 API를 통해 처리됩니다.");
                default -> {
                    log.warn("[AiToolExecutor] 알 수 없는 tool: {}", tool);
                    yield AiToolResponse.fail("지원하지 않는 도구: " + tool);
                }
            };
        } catch (IllegalArgumentException e) {
            // SQL 검증 실패
            log.warn("[AiToolExecutor] SQL 검증 실패: {}", e.getMessage());
            return AiToolResponse.fail("허용되지 않는 쿼리입니다: " + e.getMessage());
        } catch (Exception e) {
            log.error("[AiToolExecutor] 실행 오류 tool={}: {}", tool, e.getMessage(), e);
            return AiToolResponse.fail("도구 실행 중 오류가 발생했습니다.");
        }
    }

    // ── 어드민 SQL 실행 (requiresAuth 우회 — ADMIN 엔드포인트 전용) ──────────

    private AiToolResponse executeAdminSql(Map<String, Object> args, String userId) {
        // AI 내부에서만 호출 가능 — userId가 있는(로그인된) 요청에서만 허용
        if (userId == null || userId.isBlank()) {
            log.warn("[AiToolExecutor] query_database_admin: userId 없음 → 차단");
            return AiToolResponse.authRequired();
        }
        if (!isAdminUser(userId)) {
            log.warn("[AiToolExecutor] query_database_admin: non-admin userId={} denied", userId);
            return AiToolResponse.authRequired();
        }

        String sql  = getString(args, "sql");
        String desc = getString(args, "description");

        if (sql == null || sql.isBlank()) {
            return AiToolResponse.fail("SQL이 전달되지 않았습니다.");
        }

        // SELECT 전용, 위험 키워드 차단 — 어드민도 DDL/DML 금지. 비밀 테이블·컬럼은 AiDataContract가 막는다.
        SqlValidator.validate(sql);
        String contracted = AiSqlRewriter.rewrite(sql, AiDataContract.Audience.ADMIN, userId);

        log.info("[AiToolExecutor] SQL실행(어드민): {}", sql.substring(0, Math.min(150, sql.length())));
        List<Map<String, Object>> rows = runNativeQuery(contracted);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", rows.size());
        if (desc != null) meta.put("description", desc);

        return AiToolResponse.ok(rows, meta);
    }

    // ── 공개 SQL 실행 ────────────────────────────────────────────────────────

    private AiToolResponse executePublicSql(Map<String, Object> args) {
        String sql  = getString(args, "sql");
        String desc = getString(args, "description");

        if (sql == null || sql.isBlank()) {
            return AiToolResponse.fail("SQL이 전달되지 않았습니다.");
        }

        // 보안 검증: 문장 검사 후, 공개 계약(개인 테이블 거절, 개인·비밀 컬럼 제외)으로 테이블을 바꿔 읽는다.
        SqlValidator.validate(sql);
        String contracted;
        try {
            contracted = AiSqlRewriter.rewrite(sql, AiDataContract.Audience.PUBLIC, null);
        } catch (AiAuthRequiredException e) {
            return AiToolResponse.authRequired();
        }

        log.info("[AiToolExecutor] SQL실행(공개): {}", sql.substring(0, Math.min(150, sql.length())));
        List<Map<String, Object>> rows = runNativeQuery(contracted);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", rows.size());
        if (desc != null) meta.put("description", desc);

        return AiToolResponse.ok(rows, meta);
    }

    // ── 개인 SQL 실행 (user_id 강제 치환) ────────────────────────────────────

    private AiToolResponse executePrivateSql(Map<String, Object> args, String userId) {
        if (userId == null || userId.isBlank()) {
            return AiToolResponse.authRequired();
        }

        String sql  = getString(args, "sql");
        String desc = getString(args, "description");

        if (sql == null || sql.isBlank()) {
            return AiToolResponse.fail("SQL이 전달되지 않았습니다.");
        }

        // 1단계: 문장 검증 (SELECT 전용, 위험 키워드)
        SqlValidator.validate(sql);

        // 2단계: 개인 테이블을 인증된 userId의 행만 담은 파생 테이블로 바꿔 읽는다.
        //         SQL의 조건(OR 1=1, user_id 누락, 조건 없는 JOIN 등)과 상관없이 타인 행에 닿지 않는다.
        String contracted = AiSqlRewriter.rewrite(sql, AiDataContract.Audience.USER, userId);

        log.info("[AiToolExecutor] SQL실행(개인 userId={}): {}",
                userId, sql.substring(0, Math.min(150, sql.length())));
        List<Map<String, Object>> rows = runNativeQuery(contracted);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", rows.size());
        if (desc != null) meta.put("description", desc);

        return AiToolResponse.ok(rows, meta);
    }

    // ── 투어 가능 시간대 (비즈니스 로직 필요 — SQL 대체 불가) ─────────────────

    private AiToolResponse getTourSlots(Map<String, Object> args) {
        Integer roomId = getInt(args, "room_id");
        List<TourStatus> inactive = List.of(TourStatus.cancelled, TourStatus.ended);

        List<Map<String, Object>> slots = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (int day = 1; day <= 7; day++) {
            LocalDate date = now.toLocalDate().plusDays(day);
            for (int hour : new int[]{10, 14, 16}) {
                LocalDateTime start = date.atTime(hour, 0);
                LocalDateTime end   = start.plusHours(1);
                boolean available   = (roomId == null) ||
                    !tourRepository.existsRoomTimeConflict(roomId, inactive, start, end);

                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put("date",       date.toString());
                slot.put("start_time", start.toString());
                slot.put("end_time",   end.toString());
                slot.put("available",  available);
                slots.add(slot);
            }
        }

        String guide = "투어 예약은 원하는 방을 선택 후 날짜/시간을 선택하면 됩니다. 투어 시간은 1시간이며 현장 확인 후 계약 진행 가능합니다.";
        return AiToolResponse.ok(slots, Map.of("guide", guide));
    }

    // ── 쿼리 실행 ────────────────────────────────────────────────────────────

    /**
     * Runs the statement on the read-only transaction's connection. JDBC is used rather than a JPA native query: a
     * failing JPA query marks the transaction for rollback, so a bad LLM statement used to end as a server error at
     * commit instead of a failed tool result. Column names come from the result set, so {@code SELECT *} and
     * expressions keep their real names.
     */
    private List<Map<String, Object>> runNativeQuery(String sql) {
        String finalSql = appendLimit(sql, 50);
        List<Map<String, Object>> rawRows;
        try {
            rawRows = jdbcTemplate.queryForList(finalSql);
        } catch (Exception e) {
            log.warn("[AiToolExecutor] 쿼리 실행 실패: {}", e.getMessage());
            throw e;
        }

        return convertRows(rawRows);
    }

    private AiToolResponse rows(List<Map<String, Object>> rawRows) {
        List<Map<String, Object>> rows = convertRows(rawRows);
        return AiToolResponse.ok(rows, Map.of("total", rows.size()));
    }

    private List<Map<String, Object>> convertRows(List<Map<String, Object>> rawRows) {
        List<Map<String, Object>> result = new ArrayList<>(rawRows.size());
        for (Map<String, Object> raw : rawRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            raw.forEach((column, value) -> row.put(column, convertValue(value)));
            result.add(row);
        }
        return result;
    }

    /** LIMIT이 없으면 추가 */
    private String appendLimit(String sql, int maxRows) {
        if (!sql.toUpperCase().contains("LIMIT")) {
            return sql.stripTrailing().replaceAll(";$", "") + " LIMIT " + maxRows;
        }
        return sql;
    }

    /** DB 반환값을 JSON-friendly 타입으로 변환 */
    private Object convertValue(Object v) {
        if (v == null) return null;
        if (v instanceof java.math.BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof java.time.LocalDateTime ldt) return ldt.toString();
        if (v instanceof java.time.LocalDate ld) return ld.toString();
        if (v instanceof byte[] b) return new String(b);
        return v;
    }

    private boolean isAdminUser(String userId) {
        return userRepository.existsByUserIdAndUserRole(userId, UserRole.admin);
    }

    // ── 슬롯 헬퍼 ────────────────────────────────────────────────────────────

    private String getString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null ? v.toString().trim() : null;
    }

    private int intOr(Map<String, Object> args, String key, int fallback) {
        Integer value = getInt(args, key);
        return value != null ? value : fallback;
    }

    private Integer getInt(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }
}
