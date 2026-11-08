package org.myweb.uniplace.domain.ai.application.tool;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.ai.application.tool.AiDataContract.Audience;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed public reads the AI service needs besides its generated SQL: community posts for RAG and the room list for
 * the room embedding index. They used to open their own MySQL connection from Python; they now run here with bound
 * parameters and through {@link AiDataContract}'s public view, like every other AI read.
 */
@Component
@RequiredArgsConstructor
public class AiPublicReads {

    static final int MAX_POSTS = 10;
    static final int MAX_DAYS = 90;

    private final JdbcTemplate jdbcTemplate;

    /** Posts whose title or body contains {@code keyword}, newest first; without a keyword, the newest posts. */
    public List<Map<String, Object>> searchCommunityPosts(String keyword, int limit) {
        String pattern = "%" + escapeLike(keyword == null ? "" : keyword.trim()) + "%";
        return read("SELECT board_id, board_title, board_ctnt, created_at FROM board"
            + " WHERE board_title LIKE ? OR board_ctnt LIKE ? ORDER BY created_at DESC, board_id DESC LIMIT ?",
            pattern, pattern, clamp(limit, MAX_POSTS));
    }

    /** The most read posts written in the last {@code days} days. */
    public List<Map<String, Object>> popularCommunityPosts(int days, int limit) {
        return read("SELECT board_id, board_title, read_count FROM board"
            + " WHERE created_at >= DATE_SUB(NOW(), INTERVAL ? DAY) ORDER BY read_count DESC, board_id DESC LIMIT ?",
            clamp(days, MAX_DAYS), clamp(limit, MAX_POSTS));
    }

    /** Every room on offer with its building, as the room embedding index describes them. */
    public List<Map<String, Object>> roomsForEmbedding() {
        return read("SELECT r.room_id, r.room_no, r.floor, r.room_size, r.room_type, r.pet_allowed_yn, r.rent_price,"
            + " r.manage_fee, r.deposit, r.rent_min, r.sun_direction, r.room_options, r.room_desc, r.room_st,"
            + " b.building_nm, b.building_addr FROM rooms r JOIN building b ON r.building_id = b.building_id"
            + " WHERE r.delete_yn = 'N' AND b.delete_yn = 'N' ORDER BY r.room_id");
    }

    private List<Map<String, Object>> read(String sql, Object... parameters) {
        return jdbcTemplate.queryForList(AiSqlRewriter.rewrite(sql, Audience.PUBLIC, null), parameters);
    }

    private static int clamp(int value, int max) {
        return Math.max(1, Math.min(value, max));
    }

    /** {@code keyword} as literal text inside a LIKE pattern. */
    static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
