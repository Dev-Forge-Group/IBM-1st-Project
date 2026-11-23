package org.myweb.uniplace.domain.ai.application.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.myweb.uniplace.domain.ai.application.tool.AiDataContract.Audience;

class AiSqlRewriterTest {

    private static String publicSql(String sql) {
        return AiSqlRewriter.rewrite(sql, Audience.PUBLIC, null);
    }

    private static String userSql(String sql) {
        return AiSqlRewriter.rewrite(sql, Audience.USER, "user-1");
    }

    private static String adminSql(String sql) {
        return AiSqlRewriter.rewrite(sql, Audience.ADMIN, "admin-1");
    }

    @Nested
    @DisplayName("공개 조회(query_database)")
    class PublicQueries {

        @Test
        void scopesRoomDiscoveryToPubliclyVisibleAssetsWhilePreservingCallerConditions() {
            String sql = "SELECT r.room_no, r.rent_price FROM rooms r WHERE r.delete_yn='N' ORDER BY r.rent_price LIMIT 5";
            assertThat(publicSql(sql)).isEqualTo("SELECT r.room_no, r.rent_price FROM (SELECT * FROM rooms"
                + " WHERE room_id IN (SELECT room_id FROM ownership_public_room_ids)) r"
                + " WHERE r.delete_yn='N' ORDER BY r.rent_price LIMIT 5");
        }

        @Test
        void refusesPersonalTables() {
            for (String table : new String[] {"users", "qna", "contract", "complain", "payment", "monthly_charge"}) {
                assertThatThrownBy(() -> publicSql("SELECT * FROM " + table))
                    .as(table).isInstanceOf(AiAuthRequiredException.class);
            }
        }

        @Test
        void readsBuildingWithoutLessorColumns() {
            String rewritten = publicSql("SELECT * FROM building b WHERE b.delete_yn='N'");
            assertThat(rewritten).startsWith("SELECT * FROM (SELECT building_id,").contains(") b WHERE");
            assertThat(rewritten).doesNotContain("lessor");
        }

        @Test
        void readsReservationsWithoutVisitorDetails() {
            assertThat(publicSql("SELECT tour_nm, tour_tel FROM room_reservation"))
                .doesNotContain("tour_tel FROM room_reservation")
                .contains("FROM room_reservation WHERE room_id IN (SELECT room_id FROM ownership_public_room_ids)) `room_reservation`");
            assertThat(publicSql("SELECT sr.sr_start_at FROM space_reservations sr"))
                .doesNotContain("user_id").doesNotContain("sr_no_people");
        }

        @Test
        void hidesAuthorsOfAnonymousPosts() {
            assertThat(publicSql("SELECT board_title, user_id FROM board"))
                .contains("CASE WHEN anonymity = 'Y' THEN NULL ELSE user_id END AS user_id");
        }

        @Test
        void popularGoodsQueryReadsOrdersWithoutUsers() {
            String rewritten = publicSql("SELECT p.prod_nm, SUM(oi.order_quantity) as total_qty FROM order_items oi"
                + " JOIN product p ON oi.prod_id=p.prod_id JOIN orders o ON oi.order_id=o.order_id"
                + " WHERE o.order_st='paid' GROUP BY p.prod_id, p.prod_nm ORDER BY total_qty DESC LIMIT 10");
            assertThat(rewritten).contains("JOIN (SELECT order_id, order_st, order_created_at FROM orders) o ON");
        }

        @Test
        void refusesTablesOutsideTheContract() {
            assertThatThrownBy(() -> publicSql("SELECT * FROM cart_items")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("계약을 비켜 가는 SQL")
    class Bypasses {

        @Test
        void backtickedTableName() {
            assertThatThrownBy(() -> publicSql("SELECT user_pwd FROM `users`")).isInstanceOf(AiAuthRequiredException.class);
        }

        @Test
        void tableAfterACommaInTheFromList() {
            assertThatThrownBy(() -> publicSql("SELECT u.user_email FROM building b, users u"))
                .isInstanceOf(AiAuthRequiredException.class);
        }

        @Test
        void tableAfterADerivedTableInTheFromList() {
            assertThatThrownBy(() -> publicSql("SELECT u.user_email FROM (SELECT 1 AS one) x, users u"))
                .isInstanceOf(AiAuthRequiredException.class);
        }

        @Test
        void tableInASubquery() {
            assertThatThrownBy(() -> publicSql("SELECT room_no FROM rooms WHERE room_id IN (SELECT room_id FROM contract)"))
                .isInstanceOf(AiAuthRequiredException.class);
            assertThatThrownBy(() -> publicSql("SELECT (SELECT user_pwd FROM users LIMIT 1) AS p FROM rooms"))
                .isInstanceOf(AiAuthRequiredException.class);
        }

        @Test
        void commentsAreRefused() {
            assertThatThrownBy(() -> publicSql("SELECT * FROM/**/users")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> publicSql("SELECT * FROM rooms -- users")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> publicSql("SELECT * FROM rooms # users")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void otherSchemasAreRefused() {
            assertThatThrownBy(() -> adminSql("SELECT * FROM mysql.user")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> adminSql("SELECT * FROM information_schema . tables"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void secretTablesAreRefusedEvenToAdministrators() {
            for (String table : AiDataContract.SECRET_TABLES) {
                assertThatThrownBy(() -> adminSql("SELECT * FROM " + table)).as(table)
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Nested
    @DisplayName("로그인 조회(query_my_data)")
    class UserQueries {

        @Test
        void personalTablesOnlyHoldTheSignedInUsersRows() {
            String rewritten = userSql("SELECT comp_title FROM complain WHERE user_id='someone-else' OR 1=1");
            assertThat(rewritten).isEqualTo(
                "SELECT comp_title FROM (SELECT * FROM complain WHERE user_id = 'user-1') `complain`"
                    + " WHERE user_id='someone-else' OR 1=1");
        }

        @Test
        void everyPersonalTableOfAJoinIsRestricted() {
            String rewritten = userSql("SELECT p.total_price FROM contract c LEFT JOIN payment p ON 1=1");
            assertThat(rewritten)
                .contains("FROM contract WHERE user_id = 'user-1') c")
                .contains("(SELECT * FROM payment WHERE user_id = 'user-1') p");
        }

        @Test
        void monthlyChargesFollowTheUsersContracts() {
            assertThat(userSql("SELECT price FROM monthly_charge"))
                .contains("contract_id IN (SELECT contract_id FROM contract WHERE user_id = 'user-1')");
        }

        @Test
        void ownProfileWithoutThePasswordHash() {
            String rewritten = userSql("SELECT u.user_email FROM users AS u");
            assertThat(rewritten).contains("FROM users WHERE user_id = 'user-1') AS u").doesNotContain("user_pwd");
        }

        @Test
        void unaliasedTableKeepsItsNameForQualifiedColumns() {
            assertThat(userSql("SELECT orders.order_st FROM orders"))
                .isEqualTo("SELECT orders.order_st FROM (SELECT * FROM orders WHERE user_id = 'user-1') `orders`");
        }

        @Test
        void userIdIsEscaped() {
            String rewritten = AiSqlRewriter.rewrite("SELECT * FROM qna", Audience.USER, "a'b\\c");
            assertThat(rewritten).contains("user_id = 'a''b\\\\c'");
        }

        @Test
        void signedOutCallerIsAskedToSignIn() {
            assertThatThrownBy(() -> AiSqlRewriter.rewrite("SELECT * FROM rooms", Audience.USER, null))
                .isInstanceOf(AiAuthRequiredException.class);
        }

        @Test
        void publicTablesStayPublic() {
            assertThat(userSql("SELECT * FROM building")).doesNotContain("building_lessor");
        }
    }

    @Nested
    @DisplayName("관리자 조회(query_database_admin)")
    class AdminQueries {

        @Test
        void readsAnyTableOfTheSchemaWithoutSecretColumns() {
            assertThat(adminSql("SELECT * FROM users")).contains("FROM users) `users`").doesNotContain("user_pwd");
            assertThat(adminSql("SELECT * FROM contract c")).doesNotContain("lessor_rrn");
            assertThat(adminSql("SELECT * FROM building")).doesNotContain("building_lessor_rrn");
            assertThat(adminSql("SELECT * FROM room_reservation")).doesNotContain("tour_pwd");
            String other = "SELECT COUNT(*) FROM maintenance_ticket";
            assertThat(adminSql(other)).isEqualTo(other);
        }
    }

    @Nested
    @DisplayName("테이블이 아닌 FROM")
    class NotTables {

        @Test
        void fromInsideFunctionArguments() {
            String sql = "SELECT EXTRACT(YEAR FROM created_at) AS y, TRIM(BOTH ' ' FROM notice_title) AS t FROM notice";
            assertThat(publicSql(sql)).isEqualTo(sql);
        }

        @Test
        void fromInsideStringLiterals() {
            String sql = "SELECT notice_title FROM notice WHERE notice_ctnt LIKE '%from users, contract%'";
            assertThat(publicSql(sql)).isEqualTo(sql);
        }

        @Test
        void unionReadsEachSideThroughTheContract() {
            assertThatThrownBy(() -> publicSql("SELECT notice_title FROM notice UNION SELECT user_email FROM users"))
                .isInstanceOf(AiAuthRequiredException.class);
        }
    }
}
