package org.myweb.uniplace.domain.ai.application.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.myweb.uniplace.domain.ai.application.tool.AiDataContract.Audience;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Checks {@link AiDataContract} against the schema the Flyway migrations build on MySQL: every table and projection
 * the contract names can be read, and the rewritten SQL keeps personal and secret data out of reach. A migration that
 * drops or renames a column the contract reads fails here. Skipped where Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class AiDataContractMySqlTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("uniplace")
        .withUsername("uniplace")
        .withPassword("uniplace-test");

    private static final String OTHER_USER = "contract-test-other";
    private static String user;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            ResultSet owner = statement.executeQuery(
                "SELECT user_id FROM contract GROUP BY user_id ORDER BY COUNT(*) DESC, user_id LIMIT 1");
            assertThat(owner.next()).as("sample data has contracts").isTrue();
            user = owner.getString(1);
            // A second user with personal rows of every kind, which the first user must never see.
            statement.executeUpdate("INSERT INTO users (user_id, user_nm, user_email, user_pwd, user_birth, user_tel)"
                + " VALUES ('" + OTHER_USER + "', 'other', 'other@example.com', 'secret-hash', '2000-01-01', '010-0000-0000')");
            // Both users get a complaint and a question. Their category code only has to exist in common_code.
            for (String rowOwner : List.of(user, OTHER_USER)) {
                statement.executeUpdate("INSERT INTO complain (comp_title, user_id, comp_ctnt, code)"
                    + " SELECT 'complaint', '" + rowOwner + "', 'private', code FROM common_code LIMIT 1");
                statement.executeUpdate("INSERT INTO qna (qna_title, user_id, qna_ctnt, code)"
                    + " SELECT 'question', '" + rowOwner + "', 'private', code FROM common_code LIMIT 1");
            }
        }
    }

    @Test
    void everyTableAndProjectionOfTheContractCanBeRead() throws SQLException {
        List<String> reads = new ArrayList<>();
        AiDataContract.OPEN_TABLES.forEach(table -> reads.add("SELECT * FROM " + table));
        for (Map<String, String> projections : List.of(AiDataContract.PUBLIC_PROJECTIONS, AiDataContract.ADMIN_PROJECTIONS)) {
            projections.values().forEach(projection -> reads.add("SELECT * FROM (" + projection + ") t"));
        }
        AiDataContract.USER_SCOPES.values().forEach(scope -> reads.add(
            "SELECT * FROM (" + scope.replace(AiDataContract.USER_ID, AiDataContract.sqlString(user)) + ") t"));
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String read : reads) {
                statement.executeQuery(read + " LIMIT 0").close();
            }
        }
    }

    @Test
    void signedInUserReadsOnlyTheirOwnRowsWhateverTheConditions() throws SQLException {
        assertThat(userIds(Audience.USER, "SELECT c.user_id FROM complain c WHERE c.user_id = '" + OTHER_USER + "' OR 1=1"))
            .containsOnly(user);
        assertThat(userIds(Audience.USER, "SELECT q.user_id FROM contract c, qna q")).containsOnly(user);
        assertThat(userIds(Audience.USER, "SELECT u.user_id FROM users u")).containsExactly(user);
    }

    @Test
    void publicAndAdministratorReadsLeaveOutSecretColumns() throws SQLException {
        assertThat(columns(Audience.PUBLIC, "SELECT * FROM building"))
            .doesNotContain("building_lessor_rrn", "building_lessor_tel", "building_lessor_addr", "building_lessor_nm");
        assertThat(columns(Audience.PUBLIC, "SELECT * FROM room_reservation"))
            .doesNotContain("tour_nm", "tour_tel", "tour_pwd");
        assertThat(columns(Audience.ADMIN, "SELECT * FROM users")).contains("user_email").doesNotContain("user_pwd");
        assertThat(columns(Audience.ADMIN, "SELECT * FROM contract")).doesNotContain("lessor_rrn");
    }

    @Test
    void anonymousPostsDoNotNameTheirAuthor() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO board (board_title, user_id, board_ctnt, anonymity, code)"
                + " SELECT 'anonymous post', '" + user + "', 'hello', 'Y', code FROM common_code LIMIT 1");
        }
        assertThat(userIds(Audience.PUBLIC, "SELECT b.user_id FROM board b WHERE b.board_title = 'anonymous post'"))
            .containsOnly((String) null);
    }

    @Test
    void communityPostsAreSearchedWithTheKeywordAsText() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO board (board_title, user_id, board_ctnt, code, read_count)"
                + " SELECT 'Laundry room 100% open', '" + user + "', 'washer_dryer notes', code, 1000000 FROM common_code LIMIT 1");
        }
        AiPublicReads reads = publicReads();

        assertThat(reads.searchCommunityPosts("100%", 5)).extracting(row -> row.get("board_title"))
            .containsExactly("Laundry room 100% open");
        assertThat(reads.searchCommunityPosts("washer_dryer", 5)).hasSize(1);
        assertThat(reads.searchCommunityPosts("washer%dryer", 5)).as("% is matched as text").isEmpty();
        assertThat(reads.searchCommunityPosts("' OR 1=1 --", 5)).as("the keyword is a bound value").isEmpty();
        assertThat(reads.searchCommunityPosts("", 3)).as("no keyword: newest posts").hasSize(3)
            .first().satisfies(row -> assertThat(row).containsOnlyKeys("board_id", "board_title", "board_ctnt", "created_at"));
        assertThat(reads.searchCommunityPosts("", 500)).hasSizeLessThanOrEqualTo(AiPublicReads.MAX_POSTS);
        assertThat(reads.popularCommunityPosts(7, 3)).as("most read first").first()
            .satisfies(row -> assertThat(row.get("board_title")).isEqualTo("Laundry room 100% open"));
    }

    @Test
    void roomsForTheEmbeddingIndexCarryTheirBuildingWithoutLessorDetails() {
        List<Map<String, Object>> rooms = publicReads().roomsForEmbedding();
        assertThat(rooms).isNotEmpty();
        assertThat(rooms.get(0)).containsKeys("room_id", "room_desc", "building_nm", "building_addr")
            .doesNotContainKeys("building_lessor_nm", "building_lessor_rrn");
    }

    private static AiPublicReads publicReads() {
        return new AiPublicReads(new JdbcTemplate(
            new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())));
    }

    private static List<String> userIds(Audience audience, String sql) throws SQLException {
        String rewritten = AiSqlRewriter.rewrite(sql, audience, user);
        List<String> ids = new ArrayList<>();
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(rewritten)) {
            while (rows.next()) {
                ids.add(rows.getString(1));
            }
        }
        assertThat(ids).as("query returns rows: " + rewritten).isNotEmpty();
        return ids;
    }

    private static Set<String> columns(Audience audience, String sql) throws SQLException {
        String rewritten = AiSqlRewriter.rewrite(sql, audience, user);
        Set<String> names = new HashSet<>();
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(rewritten + " LIMIT 0")) {
            ResultSetMetaData meta = rows.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                names.add(meta.getColumnLabel(i));
            }
        }
        return names;
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
