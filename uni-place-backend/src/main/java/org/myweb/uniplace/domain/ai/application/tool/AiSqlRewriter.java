package org.myweb.uniplace.domain.ai.application.tool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.myweb.uniplace.domain.ai.application.tool.AiDataContract.Audience;

/**
 * Applies {@link AiDataContract} to one SELECT statement: finds every table the statement reads (after FROM, after a
 * comma in a FROM list, after JOIN, at any depth of subqueries) and replaces it with the derived table the contract
 * allows, keeping the table's alias, or its name as the alias. Everything else in the statement is kept as written.
 *
 * <p>Comments are refused because they can hide a table from a reader of the statement, and so are tables of another
 * schema. Run {@link SqlValidator#validate(String)} first for the statement-level checks.
 */
public final class AiSqlRewriter {

    /** Functions whose arguments use FROM without naming a table, e.g. EXTRACT(YEAR FROM paid_at). */
    private static final Set<String> FUNCTIONS_WITH_FROM = Set.of(
        "EXTRACT", "TRIM", "SUBSTRING", "SUBSTR", "POSITION", "OVERLAY"
    );

    /** Words after which a FROM list ends at the same parenthesis depth. */
    private static final Set<String> FROM_LIST_END = Set.of(
        "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "UNION", "WINDOW", "FOR", "INTO", "EXCEPT", "INTERSECT"
    );

    /** Words that follow a table reference without being its alias. */
    private static final Set<String> NOT_ALIAS = Set.of(
        "WHERE", "JOIN", "STRAIGHT_JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "NATURAL", "ON", "USING", "GROUP",
        "ORDER", "HAVING", "LIMIT", "UNION", "WINDOW", "FOR", "LOCK", "PARTITION", "USE", "IGNORE", "FORCE", "INTO",
        "EXCEPT", "INTERSECT"
    );

    private static final int MAX_DEPTH = 64;

    private enum Kind { SPACE, WORD, QUOTED_NAME, STRING, SYMBOL }

    private record Token(Kind kind, String text) {

        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }

        boolean is(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }

        boolean isName() {
            return kind == Kind.WORD || kind == Kind.QUOTED_NAME;
        }

        /** The name without backticks, in lower case as the schema spells table names. */
        String name() {
            String raw = kind == Kind.QUOTED_NAME ? text.substring(1, text.length() - 1).replace("``", "`") : text;
            return raw.toLowerCase(Locale.ROOT);
        }
    }

    private AiSqlRewriter() {
    }

    /**
     * The statement with each table read as {@link AiDataContract} allows for {@code audience}.
     *
     * @throws AiAuthRequiredException when a public caller reads a personal table
     * @throws IllegalArgumentException when the statement reads a table the caller may not read, or cannot be read
     */
    public static String rewrite(String sql, Audience audience, String userId) {
        List<Token> tokens = tokenize(sql);
        StringBuilder out = new StringBuilder(sql.length() + 64);
        Deque<String> openedBy = new ArrayDeque<>();
        boolean[] fromList = new boolean[MAX_DEPTH + 1];
        int depth = 0;
        String previousWord = "";

        int i = 0;
        while (i < tokens.size()) {
            Token token = tokens.get(i);
            if (token.is("(")) {
                if (++depth > MAX_DEPTH) {
                    throw new IllegalArgumentException("괄호가 너무 깊습니다.");
                }
                openedBy.push(previousWord);
                fromList[depth] = false;
                out.append(token.text());
                previousWord = "";
                i++;
                continue;
            }
            if (token.is(")")) {
                fromList[depth] = false;
                if (depth > 0) {
                    depth--;
                    openedBy.pop();
                }
                out.append(token.text());
                previousWord = "";
                i++;
                continue;
            }
            if (token.is(",") && fromList[depth]) {
                out.append(token.text());
                i = readTable(tokens, i + 1, out, audience, userId);
                continue;
            }
            if (token.kind() == Kind.WORD) {
                String word = token.upper();
                boolean functionArgument = !openedBy.isEmpty() && FUNCTIONS_WITH_FROM.contains(openedBy.peek());
                if (word.equals("FROM") && !functionArgument) {
                    out.append(token.text());
                    fromList[depth] = true;
                    i = readTable(tokens, i + 1, out, audience, userId);
                    previousWord = "";
                    continue;
                }
                if (word.equals("JOIN") || word.equals("STRAIGHT_JOIN")) {
                    out.append(token.text());
                    i = readTable(tokens, i + 1, out, audience, userId);
                    previousWord = "";
                    continue;
                }
                if (FROM_LIST_END.contains(word)) {
                    fromList[depth] = false;
                }
                previousWord = word;
            } else if (token.kind() != Kind.SPACE) {
                previousWord = "";
            }
            out.append(token.text());
            i++;
        }
        return out.toString();
    }

    /**
     * Writes the table reference starting at {@code start} as the contract allows and returns the index of the first
     * token after the table name. A parenthesis (a derived table) is left to the caller, which reads into it.
     */
    private static int readTable(List<Token> tokens, int start, StringBuilder out, Audience audience, String userId) {
        int i = start;
        while (i < tokens.size() && tokens.get(i).kind() == Kind.SPACE) {
            out.append(tokens.get(i).text());
            i++;
        }
        if (i >= tokens.size() || !tokens.get(i).isName()) {
            return i;
        }
        Token table = tokens.get(i);
        int next = skipSpace(tokens, i + 1);
        if (next < tokens.size() && tokens.get(next).is(".")) {
            throw new IllegalArgumentException("다른 스키마의 테이블은 조회할 수 없습니다: " + table.text());
        }
        String name = table.name();
        String replacement = AiDataContract.readFor(name, audience, userId);
        if (replacement == null) {
            out.append(table.text());
            return i + 1;
        }
        out.append('(').append(replacement).append(')');
        if (!hasAlias(tokens, next)) {
            out.append(" `").append(name).append('`');
        }
        return i + 1;
    }

    private static boolean hasAlias(List<Token> tokens, int index) {
        if (index >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(index);
        if (token.kind() == Kind.QUOTED_NAME) {
            return true;
        }
        return token.kind() == Kind.WORD && (token.upper().equals("AS") || !NOT_ALIAS.contains(token.upper()));
    }

    private static int skipSpace(List<Token> tokens, int index) {
        int i = index;
        while (i < tokens.size() && tokens.get(i).kind() == Kind.SPACE) {
            i++;
        }
        return i;
    }

    private static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            int start = i;
            if (Character.isWhitespace(c)) {
                while (i < n && Character.isWhitespace(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.SPACE, sql.substring(start, i)));
            } else if (c == '\'' || c == '"') {
                i = endOfString(sql, i, c);
                tokens.add(new Token(Kind.STRING, sql.substring(start, i)));
            } else if (c == '`') {
                i = endOfQuotedName(sql, i);
                tokens.add(new Token(Kind.QUOTED_NAME, sql.substring(start, i)));
            } else if (c == '#' || (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-')
                || (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*')) {
                throw new IllegalArgumentException("SQL 주석은 허용되지 않습니다.");
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
                    i++;
                }
                tokens.add(new Token(Kind.WORD, sql.substring(start, i)));
            } else {
                i++;
                tokens.add(new Token(Kind.SYMBOL, sql.substring(start, i)));
            }
        }
        return tokens;
    }

    /** The index after the string literal opened at {@code start}; MySQL doubles the quote or escapes it with \. */
    private static int endOfString(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        throw new IllegalArgumentException("닫히지 않은 문자열이 있습니다.");
    }

    private static int endOfQuotedName(String sql, int start) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == '`') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '`') {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        throw new IllegalArgumentException("닫히지 않은 이름이 있습니다.");
    }
}
