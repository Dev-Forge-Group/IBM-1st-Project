package org.myweb.uniplace.domain.commoncode.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.myweb.uniplace.domain.commoncode.application.publicapi.CommonCodeLookup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real existence semantics, including inactive codes/groups, rather than a mocked repository delegate. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CommonCodeLookupImpl.class)
@Testcontainers
class CommonCodeLookupIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("commoncode_boundary")
            .withUsername("commoncode_test")
            .withPassword("commoncode-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired CommonCodeLookup codes;
    @Autowired JdbcTemplate jdbc;

    @Test
    void existenceIncludesInactiveCodesAndGroupsAndDoesNotInventMissingCodes() {
        jdbc.update("INSERT INTO group_common_code (group_code, group_code_name, is_active) VALUES (?, ?, ?)",
                "BOUNDARY_TEST", "Boundary lookup group", 0);
        jdbc.update("INSERT INTO common_code (group_code, code, code_value, is_active) VALUES (?, ?, ?, ?)",
                "BOUNDARY_TEST", "LOOKUP_DISABLED", "Disabled lookup code", 0);
        jdbc.update("INSERT INTO common_code (group_code, code, code_value, is_active) VALUES (?, ?, ?, ?)",
                "BOUNDARY_TEST", "LOOKUP_ENABLED", "Enabled lookup code", 1);

        assertThat(codes.exists("LOOKUP_DISABLED")).isTrue();
        assertThat(codes.exists("LOOKUP_ENABLED")).isTrue();
        assertThat(codes.exists("LOOKUP_MISSING")).isFalse();
    }
}
