package org.myweb.uniplace.domain.community.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.uniplace.domain.community.domain.entity.Board;
import org.myweb.uniplace.domain.community.domain.entity.Reply;
import org.myweb.uniplace.domain.community.repository.BoardRepository;
import org.myweb.uniplace.domain.community.repository.ReplyRepository;
import org.myweb.uniplace.domain.notification.domain.entity.Notification;
import org.myweb.uniplace.domain.notification.domain.enums.TargetType;
import org.myweb.uniplace.domain.notification.repository.NotificationRepository;
import org.myweb.uniplace.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises real migrations, repository predicates, JWT authentication, viewer policy and HTTP JSON together. */
@SpringBootTest(properties = {
        "server.servlet.context-path=/api",
        "jwt.secret=community-privacy-test-secret-with-at-least-32-bytes",
        "jwt.access-exp=3600000",
        "jwt.refresh-exp=86400000",
        "spring.mail.host=localhost",
        "spring.mail.port=2525",
        "spring.mail.username=local",
        "spring.mail.password=local",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=WARN",
        "payment.reconcile.enabled=false",
        "ai.fastapi.base-url=http://127.0.0.1:9",
        "slack.webhook-url="
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers
@Transactional
class CommunityPrivacyIntegrationTest {

    private static final String AUTHOR = "privacy-author";
    private static final String OTHER = "privacy-other";
    private static final String THIRD = "privacy-third";
    private static final String ADMIN = "privacy-admin";
    private static final String AUTHOR_NICKNAME = "Privacy author";
    private static final String OTHER_NICKNAME = "Privacy other";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("community_privacy")
            .withUsername("privacy_test")
            .withPassword("privacy-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired BoardRepository boards;
    @Autowired ReplyRepository replies;
    @Autowired NotificationRepository notifications;
    @Autowired JwtProvider jwt;

    private Board anonymousBoard;
    private Board anotherBoard;
    private Board namedBoard;
    private Reply authorReply;
    private Reply otherReply;
    private Reply thirdChild;
    private Reply otherChild;
    private Reply namedReply;
    private Reply otherOnAnotherBoard;
    private Reply authorOnNamedBoard;

    @BeforeEach
    void seedPrivacyFixture() {
        for (String id : List.of(AUTHOR, OTHER, THIRD, ADMIN)) {
            String nickname = switch (id) {
                case AUTHOR -> AUTHOR_NICKNAME;
                case OTHER -> OTHER_NICKNAME;
                case THIRD -> "Privacy third";
                default -> "Privacy administrator";
            };
            jdbc.update("""
                INSERT INTO users (user_id, user_nm, user_email, user_pwd, user_birth, user_tel,
                                   user_nickname, user_role, user_st, delete_yn, account_kind)
                VALUES (?, ?, ?, 'test-only-hash', '2000-01-01', '010-0000-0000', ?, ?, 'active', 'N', ?)
                """, id, nickname, id + "@example.test", nickname, ADMIN.equals(id) ? "admin" : "user", ADMIN.equals(id) ? "STAFF" : "MEMBER");
        }
        anonymousBoard = board("Privacy anonymous board", "Y");
        // Make the anonymous board a weekly-top candidate as well as a normal/category list entry.
        anonymousBoard.setReadCount(1_000_000);
        boards.saveAndFlush(anonymousBoard);
        anotherBoard = board("Privacy another anonymous board", "Y");
        namedBoard = board("Privacy named board", "N");

        authorReply = reply(anonymousBoard, AUTHOR, "Y", null);
        otherReply = reply(anonymousBoard, OTHER, "Y", null);
        thirdChild = reply(anonymousBoard, THIRD, "Y", otherReply.getReplyId());
        otherChild = reply(anonymousBoard, OTHER, "Y", otherReply.getReplyId());
        namedReply = reply(anonymousBoard, OTHER, "N", null);
        reply(anotherBoard, THIRD, "Y", null);
        otherOnAnotherBoard = reply(anotherBoard, OTHER, "Y", null);
        authorOnNamedBoard = reply(namedBoard, AUTHOR, "Y", null);
    }

    @Test
    void publicDetailCategoryWeeklyAndTitleReadsDoNotIdentifyAnAnonymousAuthor() throws Exception {
        JsonNode detail = read(detail(anonymousBoard), null);
        anonymousBoard(detail, false);
        for (String path : List.of("/boards?size=100", "/boards?boardType=FREE&size=100",
                "/boards/search?searchType=title&keyword=Privacy&size=100")) {
            JsonNode content = read(path, null).path("content");
            anonymousBoard(byId(content, "boardId", anonymousBoard.getBoardId()), false);
        }
    }

    @Test
    void theOwnerGetsOwnershipWithoutActualIdentityAndCanFindTheirAnonymousPosts() throws Exception {
        anonymousBoard(read(detail(anonymousBoard), AUTHOR), true);
        for (String path : List.of("/boards/me?size=100", "/boards/me?boardType=FREE&size=100")) {
            JsonNode content = read(path, AUTHOR).path("content");
            anonymousBoard(byId(content, "boardId", anonymousBoard.getBoardId()), true);
            anonymousBoard(byId(content, "boardId", anotherBoard.getBoardId()), true);
        }
    }

    @Test
    void staffAccountManagementDoesNotRevealAnAnonymousBoardAuthor() throws Exception {
        anonymousBoard(read(detail(anonymousBoard), OTHER), false);
        JsonNode detail = read(detail(anonymousBoard), ADMIN);
        anonymousBoard(detail, false);
        JsonNode listEntry = byId(read("/boards?boardType=FREE&size=100", ADMIN).path("content"),
                "boardId", anonymousBoard.getBoardId());
        anonymousBoard(listEntry, false);
    }

    @Test
    void authorSearchCannotBeUsedToLinkAnonymousPostsEvenByTheirOwnerOrAnAdmin() throws Exception {
        for (String viewer : new String[] {null, AUTHOR, ADMIN}) {
            for (String type : List.of("userId", "nickname", "userNickname")) {
                String keyword = "userId".equals(type) ? AUTHOR : "Privacy";
                JsonNode content = read("/boards/search?searchType=" + type + "&keyword=" + keyword
                        + "&size=100", viewer).path("content");
                assertThat(content.size()).isEqualTo(1);
                assertThat(content.get(0).path("boardId").asInt()).isEqualTo(namedBoard.getBoardId());
            }
        }
    }

    @Test
    void rootAndChildRepliesShareBoardScopedAnonymousNumbersWithoutExposingIds() throws Exception {
        JsonNode roots = read(rootReplies(anonymousBoard), null);
        anonymousReply(byId(roots, "replyId", authorReply.getReplyId()), 0, false);
        anonymousReply(byId(roots, "replyId", otherReply.getReplyId()), 1, false);
        JsonNode children = read(childReplies(anonymousBoard, otherReply), null);
        anonymousReply(byId(children, "replyId", thirdChild.getReplyId()), 2, false);
        anonymousReply(byId(children, "replyId", otherChild.getReplyId()), 1, false);
        JsonNode visible = byId(roots, "replyId", namedReply.getReplyId());
        assertThat(visible.path("userId").asText()).isEqualTo(OTHER_NICKNAME);
        assertThat(visible.path("realUserId").asText()).isEqualTo(OTHER);
        assertThat(visible.hasNonNull("anonymousId")).isFalse();
    }

    @Test
    void mineWorksForAnonymousRootsChildrenAndThePersonalReplyPage() throws Exception {
        anonymousReply(byId(read(rootReplies(anonymousBoard), OTHER), "replyId", otherReply.getReplyId()), 1, true);
        anonymousReply(byId(read(childReplies(anonymousBoard, otherReply), OTHER), "replyId", otherChild.getReplyId()),
                1, true);
        JsonNode mine = read("/replies/me?size=100", OTHER).path("content");
        anonymousReply(byId(mine, "replyId", otherReply.getReplyId()), 1, true);
        anonymousReply(byId(mine, "replyId", otherChild.getReplyId()), 1, true);
        anonymousReply(byId(mine, "replyId", otherOnAnotherBoard.getReplyId()), 2, true);
    }

    @Test
    void staffCannotIdentifyAnonymousRepliesOnEitherRootOrChildRoutes() throws Exception {
        JsonNode root = byId(read(rootReplies(anonymousBoard), ADMIN), "replyId", otherReply.getReplyId());
        anonymousReply(root, 1, false);
        JsonNode child = byId(read(childReplies(anonymousBoard, otherReply), ADMIN), "replyId", thirdChild.getReplyId());
        anonymousReply(child, 2, false);
    }

    @Test
    void numberingIsScopedToTheBoardAndDoesNotRevealAnAnonymousReplyOnANamedPost() throws Exception {
        anonymousReply(byId(read(rootReplies(anotherBoard), null), "replyId", otherOnAnotherBoard.getReplyId()),
                2, false);
        anonymousReply(byId(read(rootReplies(namedBoard), null), "replyId", authorOnNamedBoard.getReplyId()),
                1, false);
        JsonNode named = read(detail(namedBoard), null);
        assertThat(named.path("userId").asText()).isEqualTo(AUTHOR_NICKNAME);
        assertThat(named.path("realUserId").asText()).isEqualTo(AUTHOR);
    }

    @Test
    void laterAnonymousRepliesDoNotRenumberTheExistingConversation() throws Exception {
        Reply later = reply(anonymousBoard, ADMIN, "Y", otherReply.getReplyId());
        JsonNode roots = read(rootReplies(anonymousBoard), null);
        anonymousReply(byId(roots, "replyId", otherReply.getReplyId()), 1, false);
        JsonNode children = read(childReplies(anonymousBoard, otherReply), null);
        anonymousReply(byId(children, "replyId", thirdChild.getReplyId()), 2, false);
        anonymousReply(byId(children, "replyId", later.getReplyId()), 3, false);
    }

    @Test
    void clientFlagsCannotGrantOwnershipOrAdministratorIdentityAccess() throws Exception {
        anonymousBoard(read(detail(anonymousBoard) + "&mine=true&administrator=true&viewerId=" + AUTHOR, OTHER), false);
        JsonNode roots = read(rootReplies(anonymousBoard) + "?mine=true&administrator=true&viewerId=" + OTHER, THIRD);
        anonymousReply(byId(roots, "replyId", otherReply.getReplyId()), 1, false);
    }

    @Test
    void redactedOwnershipMetadataDoesNotRelaxServerEditAndDeleteChecks() throws Exception {
        mvc.perform(as(put(api("/boards/" + anonymousBoard.getBoardId())).contextPath("/api")
                .param("anonymity", "Y"), OTHER)).andExpect(status().isForbidden());
        mvc.perform(as(put(api("/boards/" + anonymousBoard.getBoardId())).contextPath("/api")
                .param("anonymity", "Y"), AUTHOR)).andExpect(status().isOk());
        mvc.perform(as(delete(api("/replies/" + otherChild.getReplyId())).contextPath("/api"), THIRD))
                .andExpect(status().isForbidden());
        assertThat(replies.existsById(otherChild.getReplyId())).isTrue();
        mvc.perform(as(delete(api("/replies/" + otherChild.getReplyId())).contextPath("/api"), OTHER))
                .andExpect(status().isOk());
        assertThat(replies.existsById(otherChild.getReplyId())).isFalse();
    }

    @Test
    void historicalReplyNotificationsCannotRevealAWriterAfterAnonymityChanges() throws Exception {
        // The stored notification predates anonymisation and keeps its original sender for internal use.
        Notification notification = notifications.saveAndFlush(Notification.builder()
                .receiverId(AUTHOR).senderId(OTHER).code("BRD_REPLY").message("A reply was added")
                .target(TargetType.reply).targetId(namedReply.getReplyId())
                .urlPath("/community/boards/" + anonymousBoard.getBoardId()).isRead("N").build());
        namedReply.setAnonymity("Y");
        replies.saveAndFlush(namedReply);
        for (String path : List.of("/notifications?size=100", "/notifications/unread?size=100")) {
            JsonNode content = read(path, AUTHOR).path("notifications").path("content");
            JsonNode result = byId(content, "notificationId", notification.getNotificationId());
            assertThat(result.hasNonNull("senderId")).isFalse();
            assertThat(result.path("message").asText()).isEqualTo("A reply was added");
            assertThat(result.path("urlPath").asText()).isEqualTo("/community/boards/" + anonymousBoard.getBoardId());
        }
        assertThat(notifications.findById(notification.getNotificationId()).orElseThrow().getSenderId()).isEqualTo(OTHER);
    }

    private JsonNode read(String path, String viewer) throws Exception {
        MvcResult result = mvc.perform(as(get(api(path)).contextPath("/api"), viewer))
                .andExpect(status().isOk()).andReturn();
        String cache = result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL);
        assertThat(cache).contains("private", "no-store").doesNotContain("public");
        JsonNode envelope = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(envelope.path("success").asBoolean()).isTrue();
        return envelope.path("data");
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String viewer) {
        if (viewer != null) request.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + jwt.createAccessToken(viewer, ADMIN.equals(viewer) ? "admin" : "user"));
        return request;
    }

    private static void anonymousBoard(JsonNode board, boolean mine) {
        assertThat(board.path("userId").asText()).isEqualTo("익명(글쓴이)");
        assertThat(board.hasNonNull("realUserId")).isFalse();
        assertThat(board.hasNonNull("realUserNickname")).isFalse();
        assertMine(board, mine);
    }

    private static void anonymousReply(JsonNode reply, int number, boolean mine) {
        assertThat(reply.hasNonNull("userId")).isFalse();
        assertThat(reply.hasNonNull("realUserId")).isFalse();
        assertThat(reply.hasNonNull("realUserNickname")).isFalse();
        assertThat(reply.hasNonNull("anonymousId")).isTrue();
        assertThat(reply.path("anonymousId").asInt()).isEqualTo(number);
        assertMine(reply, mine);
    }

    private static void assertMine(JsonNode result, boolean expected) {
        assertThat(result.has("mine")).isTrue();
        assertThat(result.path("mine").asBoolean()).isEqualTo(expected);
    }

    private static JsonNode byId(JsonNode rows, String field, int id) {
        for (JsonNode row : rows) if (row.path(field).asInt() == id) return row;
        throw new AssertionError("Expected fixture row " + id + " in " + field);
    }

    private Board board(String title, String anonymity) {
        return boards.saveAndFlush(Board.builder().boardTitle(title).boardCtnt("Privacy fixture")
                .userId(AUTHOR).code("BOARD_FREE").anonymity(anonymity).build());
    }

    private Reply reply(Board board, String author, String anonymity, Integer parentId) {
        return replies.saveAndFlush(Reply.builder().boardId(board.getBoardId()).userId(author)
                .replyCtnt("Privacy reply").anonymity(anonymity).parentId(parentId).build());
    }

    private static String detail(Board board) { return "/boards/" + board.getBoardId() + "?increaseReadCount=false"; }
    private static String rootReplies(Board board) { return "/boards/" + board.getBoardId() + "/replies"; }
    private static String childReplies(Board board, Reply parent) {
        return rootReplies(board) + "/" + parent.getReplyId() + "/children";
    }
    private static String api(String path) { return "/api" + path; }
}
