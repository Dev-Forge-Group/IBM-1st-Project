package org.myweb.uniplace.domain.ai.application.tool;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Statement-level checks on SQL the LLM generated: one SELECT statement, without keywords that write, call code or
 * touch files. Which tables and rows the statement may read is decided by {@link AiDataContract}, applied by
 * {@link AiSqlRewriter} after these checks.
 */
public final class SqlValidator {

    /** Keywords no AI statement may contain. */
    private static final Set<String> FORBIDDEN = Set.of(
        "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE",
        "CREATE", "GRANT", "REVOKE", "EXEC", "EXECUTE", "CALL", "MERGE",
        "INTO", "LOAD_FILE", "OUTFILE", "DUMPFILE", "SLEEP", "BENCHMARK"
    );

    private static final Pattern ONLY_SELECT = Pattern.compile(
        "^\\s*SELECT\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern SEMICOLON_MULTI = Pattern.compile(";.+", Pattern.DOTALL);

    private SqlValidator() {
    }

    /**
     * @throws IllegalArgumentException when the statement is not a single SELECT or contains a forbidden keyword
     */
    public static void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL이 비어있습니다.");
        }
        if (!ONLY_SELECT.matcher(sql).matches()) {
            throw new IllegalArgumentException("SELECT 쿼리만 허용됩니다.");
        }
        if (SEMICOLON_MULTI.matcher(sql.trim()).find()) {
            throw new IllegalArgumentException("여러 쿼리를 동시에 실행할 수 없습니다.");
        }
        String upper = sql.toUpperCase();
        for (String keyword : FORBIDDEN) {
            if (Pattern.compile("\\b" + keyword + "\\b").matcher(upper).find()) {
                throw new IllegalArgumentException("허용되지 않는 SQL 키워드: " + keyword);
            }
        }
    }
}
