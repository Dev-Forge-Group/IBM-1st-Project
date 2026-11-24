package org.myweb.uniplace.domain.ownership.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Rehearses a V16 backup, V17 upgrade, V16 restore and V17 replay with synthetic data. */
@Testcontainers
class V17OwnershipUpgradeIntegrationTest {

    private static final String SOURCE_DATABASE = "ownership_source";
    private static final String RESTORED_DATABASE = "ownership_restore";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName(SOURCE_DATABASE)
            .withUsername("ownership_rehearsal")
            .withPassword("ownership-rehearsal-test-only");

    @Test
    void legacyDataSurvivesUpgradeAndRestorationFromThePreUpgradeBackup() throws Exception {
        DriverManagerDataSource source = dataSource(MYSQL.getJdbcUrl());
        JdbcTemplate sourceDb = new JdbcTemplate(source);
        Flyway v16 = flyway(source, "16");
        assertThat(v16.migrate().migrationsExecuted).isEqualTo(16);
        assertThat(v16.info().current().getVersion().getVersion()).isEqualTo("16");
        assertV16Schema(sourceDb);
        seedLegacyRows(sourceDb);
        LegacyState legacy = readLegacyState(sourceDb);
        takeV16Backup();

        int checksum = upgradeAndVerify(source, legacy);

        restoreV16Backup();
        DriverManagerDataSource restored = dataSource(MYSQL.getJdbcUrl()
                .replace("/" + SOURCE_DATABASE, "/" + RESTORED_DATABASE));
        JdbcTemplate restoredDb = new JdbcTemplate(restored);
        assertThat(restoredDb.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(RESTORED_DATABASE);
        assertV16Schema(restoredDb);
        Flyway restoredV16 = flyway(restored, "16");
        assertThat(restoredV16.info().current().getVersion().getVersion()).isEqualTo("16");
        restoredV16.validate();
        assertLegacyState(restoredDb, legacy);
        assertThat(upgradeAndVerify(restored, legacy)).isEqualTo(checksum);
        assertThat(flyway(source, "17").info().current().getVersion().getVersion()).isEqualTo("17");
    }

    private static void seedLegacyRows(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO users (user_id, user_nm, user_email, user_pwd, user_birth, user_tel, user_role, user_st, delete_yn)
                VALUES
                ('v17-staff', 'Synthetic Staff', 'v17-staff@example.test', 'test-only', '2000-01-01', '000-000-0001', 'admin', 'active', 'N'),
                ('v17-member', 'Synthetic Lessor', 'v17-member@example.test', 'test-only', '2000-01-01', '000-000-0002', 'user', 'active', 'N'),
                ('v17-tenant', 'Synthetic Tenant', 'v17-tenant@example.test', 'test-only', '2000-01-01', '000-000-0003', 'tenant', 'active', 'N'),
                ('v17-inactive', 'Synthetic Inactive', 'v17-inactive@example.test', 'test-only', '2000-01-01', '000-000-0004', 'user', 'inactive', 'Y')
                """);
        jdbc.update("""
                INSERT INTO building (building_id, building_nm, building_addr, building_lessor_nm, building_lessor_tel, delete_yn)
                VALUES (170001, 'V17 rehearsal building', 'Synthetic address', 'Synthetic Lessor', '000-000-0002', 'N'),
                       (170002, 'V17 deleted building', 'Synthetic address', NULL, NULL, 'Y')
                """);
        jdbc.update("""
                INSERT INTO rooms (room_id, room_no, floor, room_size, building_id, rent_price, room_st, delete_yn)
                VALUES (170001, 101, 1, 24.00, 170001, 450000, 'contracted', 'N'),
                       (170002, 102, 1, 24.00, 170002, 450000, 'available', 'N'),
                       (170003, 103, 1, 24.00, 170001, 450000, 'available', 'Y')
                """);
        jdbc.update("""
                INSERT INTO contract (contract_id, user_id, room_id, contract_start, contract_end, deposit, rent_price,
                                      manage_fee, payment_day, contract_st, lessor_nm, lessor_tel, lessor_addr,
                                      lessor_rrn, contract_pdf_file_id)
                VALUES (170001, 'v17-tenant', 170001, '2026-01-01', '2030-12-31', 1000000, 450000,
                        50000, 10, 'active', 'Synthetic Lessor', '000-000-0002', 'Synthetic address',
                        'TEST-ONLY', 170001),
                       (170002, 'v17-tenant', 170001, '2025-01-01', '2025-12-31', 900000, 420000,
                        50000, 10, 'ended', 'Synthetic Lessor', '000-000-0002', 'Synthetic address',
                        'TEST-ONLY', 170002)
                """);
        jdbc.update("""
                INSERT INTO residents (resident_id, building_id, contract_id, user_id)
                VALUES (170001, 170001, 170001, 'v17-tenant')
                """);
        jdbc.update("""
                INSERT INTO files (file_id, file_parent_type, file_parent_id, file_path, origin_filename,
                                   rename_filename, file_size, file_type, delete_yn)
                VALUES (170001, 'CONTRACT', 170001, 'CONTRACT/170001', 'synthetic-active.pdf', 'synthetic-active.pdf', 100, '.pdf', 'N'),
                       (170002, 'CONTRACT', 170002, 'CONTRACT/170002', 'synthetic-ended.pdf', 'synthetic-ended.pdf', 100, '.pdf', 'Y')
                """);
        jdbc.update("""
                INSERT INTO room_reservation (tour_id, building_id, room_id, tour_start_at, tour_end_at,
                                              tour_nm, tour_tel, tour_st, tour_pwd)
                VALUES (170001, 170001, 170001, '2026-01-01 10:00:00', '2026-01-01 11:00:00',
                        'Synthetic tour', '000-000-0010', 'ended', '0000'),
                       (170002, 170001, 170001, '2026-01-02 10:00:00', '2026-01-02 11:00:00',
                        'Synthetic tour', '000-000-0010', 'cancelled', '0000')
                """);
    }

    private static int upgradeAndVerify(DriverManagerDataSource source, LegacyState legacy) {
        Flyway v17 = flyway(source, "17");
        assertThat(v17.migrate().migrationsExecuted).isEqualTo(1);
        v17.validate();
        var current = v17.info().current();
        assertThat(current.getVersion().getVersion()).isEqualTo("17");

        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertLegacyState(jdbc, legacy);
        assertThat(jdbc.queryForList("SELECT user_id FROM users WHERE account_kind='STAFF' ORDER BY user_id", String.class))
                .containsExactlyElementsOf(legacy.staffIds()).contains("v17-staff");
        assertThat(jdbc.queryForList("SELECT user_id FROM users WHERE account_kind='MEMBER' ORDER BY user_id", String.class))
                .containsExactlyElementsOf(legacy.memberIds()).contains("v17-member", "v17-tenant", "v17-inactive");
        assertThat(jdbc.queryForList("""
                SELECT user_id FROM account_role_grants
                WHERE role_code='CUSTOMER' AND grant_state='ACTIVE'
                ORDER BY user_id
                """, String.class)).containsExactlyElementsOf(legacy.memberIds());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_role_grants", Long.class))
                .isEqualTo((long) legacy.memberIds().size());
        for (String table : List.of("owner_profiles", "building_memberships", "room_memberships",
                "contract_owner_parties", "ownership_review_events")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).as(table).isZero();
        }
        assertThat(jdbc.queryForList("SELECT building_id FROM ownership_public_building_ids ORDER BY building_id", Integer.class))
                .containsExactlyElementsOf(legacy.publicBuildings()).contains(170001).doesNotContain(170002);
        assertThat(jdbc.queryForList("SELECT room_id FROM ownership_public_room_ids ORDER BY room_id", Integer.class))
                .containsExactlyElementsOf(legacy.publicRooms()).contains(170001).doesNotContain(170002, 170003);
        assertThat(current.getChecksum()).isNotNull();
        return current.getChecksum();
    }

    private static LegacyState readLegacyState(JdbcTemplate jdbc) {
        return new LegacyState(
                legacyRows(jdbc),
                jdbc.queryForList("SELECT user_id FROM users WHERE user_role='admin' ORDER BY user_id", String.class),
                jdbc.queryForList("SELECT user_id FROM users WHERE user_role<>'admin' ORDER BY user_id", String.class),
                jdbc.queryForList("SELECT building_id FROM building WHERE delete_yn='N' ORDER BY building_id", Integer.class),
                jdbc.queryForList("""
                        SELECT r.room_id FROM rooms r JOIN building b ON b.building_id=r.building_id
                        WHERE r.delete_yn='N' AND b.delete_yn='N' ORDER BY r.room_id
                        """, Integer.class)
        );
    }

    private static Map<String, List<Map<String, Object>>> legacyRows(JdbcTemplate jdbc) {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        rows.put("users", jdbc.queryForList("SELECT user_id, user_role, user_st, delete_yn FROM users ORDER BY user_id"));
        rows.put("building", jdbc.queryForList("SELECT * FROM building ORDER BY building_id"));
        rows.put("rooms", jdbc.queryForList("SELECT * FROM rooms ORDER BY room_id"));
        rows.put("contract", jdbc.queryForList("SELECT * FROM contract ORDER BY contract_id"));
        rows.put("residents", jdbc.queryForList("SELECT * FROM residents ORDER BY resident_id"));
        rows.put("files", jdbc.queryForList("SELECT * FROM files ORDER BY file_id"));
        rows.put("room_reservation", jdbc.queryForList("""
                SELECT tour_id, building_id, room_id, tour_start_at, tour_end_at, tour_st
                FROM room_reservation ORDER BY tour_id
                """));
        rows.put("tour_reservation_write_guard", jdbc.queryForList("SELECT guard_id FROM tour_reservation_write_guard"));
        rows.put("Flyway V1–V16 history", jdbc.queryForList("""
                SELECT installed_rank, version, description, type, script, checksum, installed_by, installed_on, success
                FROM flyway_schema_history WHERE installed_rank <= 16 ORDER BY installed_rank
                """));
        return rows;
    }

    private static void assertLegacyState(JdbcTemplate jdbc, LegacyState legacy) {
        var actual = legacyRows(jdbc);
        legacy.rows().forEach((table, rows) -> {
            var actualRows = actual.get(table);
            assertThat(actualRows.size()).as("%s row count", table).isEqualTo(rows.size());
            assertThat(actualRows.equals(rows)).as("%s contents match the V16 state", table).isTrue();
        });
    }

    private static void assertV16Schema(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='users' AND column_name='account_kind'
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name IN ('owner_profiles', 'ownership_public_room_ids')
                """, Long.class)).isZero();
    }

    private static void takeV16Backup() throws Exception {
        assertContainerCommand("V16 logical backup", """
                umask 077
                MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump --protocol=socket --user=root \
                  --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-add-drop-table \
                  ownership_source > /tmp/ownership-v16.sql
                test -s /tmp/ownership-v16.sql
                """);
    }

    private static void restoreV16Backup() throws Exception {
        assertContainerCommand("V16 restore into a separate schema", """
                MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root <<'SQL'
                CREATE DATABASE ownership_restore;
                GRANT ALL ON ownership_restore.* TO 'ownership_rehearsal'@'%';
                SQL
                MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root ownership_restore < /tmp/ownership-v16.sql
                rm /tmp/ownership-v16.sql
                """);
    }

    private static void assertContainerCommand(String description, String command) throws Exception {
        var result = MYSQL.execInContainer("sh", "-eu", "-c", command);
        assertThat(result.getExitCode()).as(description + " exit code").isZero();
    }

    private static DriverManagerDataSource dataSource(String url) {
        return new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static Flyway flyway(DriverManagerDataSource source, String version) {
        return Flyway.configure().dataSource(source).locations("classpath:db/migration").target(version).load();
    }

    private record LegacyState(Map<String, List<Map<String, Object>>> rows,
                               List<String> staffIds, List<String> memberIds,
                               List<Integer> publicBuildings, List<Integer> publicRooms) {}
}
