package org.myweb.uniplace.domain.community.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.myweb.uniplace.domain.community.api.dto.response.BoardResponse;
import org.myweb.uniplace.domain.community.api.dto.response.ReplyResponse;
import org.myweb.uniplace.domain.community.domain.entity.Board;
import org.myweb.uniplace.domain.community.domain.entity.Reply;
import org.myweb.uniplace.domain.user.domain.entity.User;

class CommunityResponsePrivacyTest {
    private static final String AUTHOR = "private-account-id";
    private static final User USER = User.builder().userId(AUTHOR).userNickname("Private nickname").build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyBoardMappersAlsoRedactAnonymousIdentity() throws Exception {
        Board board = Board.builder().boardId(1).userId(AUTHOR).anonymity("Y").build();
        var response = BoardResponse.fromEntity(board, 0, false, USER);
        String json = mapper.writeValueAsString(response);

        assertThat(json).doesNotContain(AUTHOR, USER.getUserNickname());
        assertThat(response.isMine()).isFalse();
        assertThat(response.getUserId()).isEqualTo("익명(글쓴이)");
    }

    @Test
    void legacyReplyMappersAlsoRedactAnonymousIdentity() throws Exception {
        Reply reply = Reply.builder().replyId(1).boardId(1).userId(AUTHOR).anonymity("Y").build();
        var response = ReplyResponse.fromEntity(reply, 0, false, USER);

        assertThat(mapper.writeValueAsString(response)).doesNotContain(AUTHOR, USER.getUserNickname());
        assertThat(response.isMine()).isFalse();
        assertThat(response.getAnonymousId()).isNull();
    }

    @Test
    void evenTheAuthorUsesOwnershipInsteadOfAnAnonymousIdentity() throws Exception {
        Board board = Board.builder().boardId(1).userId(AUTHOR).anonymity("Y").build();
        Reply reply = Reply.builder().replyId(1).boardId(1).userId(AUTHOR).anonymity("Y").build();
        var boardResponse = BoardResponse.fromEntity(board, 0, false, USER, AUTHOR, false);
        var replyResponse = ReplyResponse.fromEntity(reply, 0, false, USER, AUTHOR, false, 0);

        assertThat(boardResponse.isMine()).isTrue();
        assertThat(replyResponse.isMine()).isTrue();
        assertThat(replyResponse.getAnonymousId()).isZero();
        assertThat(mapper.writeValueAsString(boardResponse)).doesNotContain(AUTHOR, USER.getUserNickname());
        assertThat(mapper.writeValueAsString(replyResponse)).doesNotContain(AUTHOR, USER.getUserNickname());
    }

    @Test
    void namedResponsesNeverCarryAnAnonymousAuthorNumber() {
        Reply reply = Reply.builder().replyId(1).boardId(1).userId(AUTHOR).anonymity("N").build();
        var response = ReplyResponse.fromEntity(reply, 0, false, USER, "viewer", false, 7);

        assertThat(response.getAnonymousId()).isNull();
        assertThat(response.getUserId()).isEqualTo(USER.getUserNickname());
        assertThat(response.getRealUserId()).isEqualTo(AUTHOR);
        assertThat(response.isMine()).isFalse();
    }
}
