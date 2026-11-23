package org.myweb.uniplace.domain.ai.application.tool;

import java.util.Map;
import java.util.Set;

/**
 * What the AI's generated SQL may read, table by table, for each kind of caller. This is the contract between the
 * AI service and the database: the AI writes SQL against table names, and {@link AiSqlRewriter} replaces every table
 * it reads with what this contract allows before the query runs.
 *
 * <ul>
 *   <li>{@link Audience#PUBLIC} (query_database): open tables as stored, a few tables through a projection without
 *       personal or secret columns, and no personal tables (the caller is told to sign in).</li>
 *   <li>{@link Audience#USER} (query_my_data): the public view, and the personal tables restricted to the signed-in
 *       user's rows. The restriction is part of the table the query reads, so no condition the SQL adds or leaves out
 *       can reach other users' rows.</li>
 *   <li>{@link Audience#ADMIN} (query_database_admin): every table of the application schema, except the secret
 *       tables and the secret columns.</li>
 * </ul>
 *
 * Projections name their columns, so a migration that drops or renames one fails
 * {@code AiDataContractMySqlTest} instead of breaking the AI at run time.
 */
public final class AiDataContract {

    public enum Audience { PUBLIC, USER, ADMIN }

    /** Replaced by the signed-in user's id, as an escaped SQL string literal. */
    static final String USER_ID = "{userId}";

    /** Credentials, biometrics and login tokens: no AI query reads them. */
    static final Set<String> SECRET_TABLES = Set.of(
        "face_descriptor", "face_match_token", "refresh_tokens", "password_reset_tokens", "social_accounts"
    );

    /** Public listings without personal columns, read as stored by public and signed-in queries. */
    static final Set<String> OPEN_TABLES = Set.of(
        "rooms", "reviews", "common_space", "notice", "faq", "company_info", "product_building_stock",
        "service_goods", "product", "order_items", "banner", "affiliate"
    );

    /** Tables public and signed-in queries read without their personal or secret columns. */
    static final Map<String, String> PUBLIC_PROJECTIONS = Map.of(
        "building", "SELECT building_id, building_nm, building_addr, building_desc, land_category, build_size,"
            + " building_usage, exist_elv, parking_capacity, created_at, updated_at, delete_yn FROM building"
            + " WHERE building_id IN (SELECT building_id FROM ownership_public_building_ids)",
        "room_reservation", "SELECT tour_id, building_id, room_id, tour_start_at, tour_end_at, tour_st, created_at,"
            + " updated_at FROM room_reservation WHERE room_id IN (SELECT room_id FROM ownership_public_room_ids)",
        "space_reservations", "SELECT reservation_id, building_id, space_id, sr_start_at, sr_end_at, sr_st"
            + " FROM space_reservations WHERE building_id IN (SELECT building_id FROM ownership_public_building_ids)",
        "board", "SELECT board_id, board_title, CASE WHEN anonymity = 'Y' THEN NULL ELSE user_id END AS user_id,"
            + " board_ctnt, created_at, updated_at, read_count, code, anonymity, importance, imp_end_at, file_ck,"
            + " reply_ck FROM board",
        "reply", "SELECT reply_id, board_id, reply_ctnt, created_at, updated_at, parent_id, reply_lev, reply_seq,"
            + " anonymity FROM reply",
        "board_likes", "SELECT board_id FROM board_likes",
        "reply_likes", "SELECT reply_id FROM reply_likes",
        "orders", "SELECT order_id, order_st, order_created_at FROM orders"
    );

    /** Personal tables, restricted to the signed-in user's rows. They take precedence over the public projections. */
    static final Map<String, String> USER_SCOPES = Map.of(
        "users", "SELECT user_id, user_nm, user_nickname, user_email, user_birth, user_tel, user_role, created_at,"
            + " updated_at, last_login_at, first_sign, user_st, delete_yn FROM users WHERE user_id = " + USER_ID,
        "contract", "SELECT contract_id, user_id, room_id, contract_start, contract_end, deposit, rent_price,"
            + " manage_fee, payment_day, contract_st, sign_at, movein_at, created_at, updated_at, rent_type, lessor_nm,"
            + " lessor_tel, lessor_addr, lessor_sign_file_id, contract_pdf_file_id FROM contract WHERE user_id = "
            + USER_ID,
        "complain", "SELECT * FROM complain WHERE user_id = " + USER_ID,
        "payment", "SELECT * FROM payment WHERE user_id = " + USER_ID,
        "qna", "SELECT * FROM qna WHERE user_id = " + USER_ID,
        "orders", "SELECT * FROM orders WHERE user_id = " + USER_ID,
        "space_reservations", "SELECT * FROM space_reservations WHERE user_id = " + USER_ID,
        "monthly_charge", "SELECT * FROM monthly_charge WHERE contract_id IN"
            + " (SELECT contract_id FROM contract WHERE user_id = " + USER_ID + ")"
    );

    /** Tables administrators read without their secret columns (password hash, resident registration numbers). */
    static final Map<String, String> ADMIN_PROJECTIONS = Map.of(
        "users", "SELECT user_id, user_nm, user_nickname, user_email, user_birth, user_tel, user_role, created_at,"
            + " updated_at, last_login_at, first_sign, user_st, delete_yn FROM users",
        "contract", "SELECT contract_id, user_id, room_id, contract_start, contract_end, deposit, rent_price,"
            + " manage_fee, payment_day, contract_st, sign_at, movein_at, created_at, updated_at, rent_type, lessor_nm,"
            + " lessor_tel, lessor_addr, lessor_sign_file_id, contract_pdf_file_id FROM contract",
        "building", "SELECT building_id, building_nm, building_addr, building_desc, land_category, build_size,"
            + " building_usage, exist_elv, parking_capacity, building_lessor_nm, building_lessor_tel,"
            + " building_lessor_addr, created_at, updated_at, delete_yn FROM building",
        "room_reservation", "SELECT tour_id, building_id, room_id, tour_start_at, tour_end_at, tour_nm, tour_tel,"
            + " created_at, updated_at, tour_st FROM room_reservation"
    );

    private AiDataContract() {
    }

    /**
     * What the query reads in place of {@code table}: {@code null} to read the table as stored, otherwise a SELECT
     * statement to read as a derived table.
     *
     * @throws AiAuthRequiredException when a public caller reads a personal table
     * @throws IllegalArgumentException when the caller may not read the table at all
     */
    static String readFor(String table, Audience audience, String userId) {
        if (SECRET_TABLES.contains(table)) {
            throw new IllegalArgumentException("허용되지 않는 테이블: " + table);
        }
        return switch (audience) {
            case ADMIN -> ADMIN_PROJECTIONS.get(table);
            case USER -> {
                if (userId == null || userId.isBlank()) {
                    throw new AiAuthRequiredException();
                }
                String scope = USER_SCOPES.get(table);
                yield scope != null ? scope.replace(USER_ID, sqlString(userId)) : publicRead(table);
            }
            case PUBLIC -> {
                if (USER_SCOPES.containsKey(table) && !PUBLIC_PROJECTIONS.containsKey(table)) {
                    throw new AiAuthRequiredException();
                }
                yield publicRead(table);
            }
        };
    }

    private static String publicRead(String table) {
        // Trusted publication views preserve legacy readonly discovery and hide owner drafts in every AI read.
        String propertyScope = switch (table) {
            case "rooms", "reviews" -> "SELECT * FROM " + table
                    + " WHERE room_id IN (SELECT room_id FROM ownership_public_room_ids)";
            case "common_space", "product_building_stock" -> "SELECT * FROM " + table
                    + " WHERE building_id IN (SELECT building_id FROM ownership_public_building_ids)";
            default -> null;
        };
        if (propertyScope != null) return propertyScope;
        if (OPEN_TABLES.contains(table)) {
            return null;
        }
        String projection = PUBLIC_PROJECTIONS.get(table);
        if (projection == null) {
            throw new IllegalArgumentException("허용되지 않는 테이블: " + table);
        }
        return projection;
    }

    /** A MySQL string literal for {@code value}, with quotes and backslashes escaped. */
    static String sqlString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }
}
