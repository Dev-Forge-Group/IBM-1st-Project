package org.myweb.uniplace.domain.community.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.uniplace.domain.ai.application.moderation.BannedWordService;
import org.myweb.uniplace.domain.community.api.dto.request.BoardUpdateRequest;
import org.myweb.uniplace.domain.community.domain.entity.Board;
import org.myweb.uniplace.domain.community.repository.BoardLikeRepository;
import org.myweb.uniplace.domain.community.repository.BoardRepository;
import org.myweb.uniplace.domain.community.repository.ReplyRepository;
import org.myweb.uniplace.domain.file.application.FileService;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.security.AuthUser;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class BoardServiceImplTest {

    @Mock private BoardRepository boardRepository;
    @Mock private ReplyRepository replyRepository;
    @Mock private FileService fileService;
    @Mock private BoardLikeRepository boardLikeRepository;
    @Mock private UserRepository userRepository;
    @Mock private BannedWordService bannedWordService;
    @InjectMocks private BoardServiceImpl boardService;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOARD_QUESTION", " question ", "review", "notice"})
    void ordinaryAuthorCannotMoveTheirFreePostToARestrictedBoard(String targetCode) {
        signIn("author", "user");
        Board board = board("BOARD_FREE");
        given(boardRepository.findById(7)).willReturn(Optional.of(board));
        BoardUpdateRequest request = BoardUpdateRequest.builder()
                .code(targetCode)
                .boardTitle("Changed title")
                .boardCtnt("Changed content")
                .build();
        MockMultipartFile file = new MockMultipartFile("ofile", "new.txt", "text/plain", new byte[] {1});

        assertThatThrownBy(() -> boardService.updateBoard(7, request, true, file))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThat(board.getCode()).isEqualTo("BOARD_FREE");
        assertThat(board.getBoardTitle()).isEqualTo("Original title");
        assertThat(board.getBoardCtnt()).isEqualTo("Original content");
        assertThat(board.getFileCk()).isEqualTo("Y");
        verifyNoInteractions(bannedWordService, fileService, replyRepository);
    }

    @ParameterizedTest
    @CsvSource({
            "author, tenant, question, BOARD_QUESTION",
            "moderator, ADMIN, question, BOARD_QUESTION",
            "author, user, free, BOARD_FREE"
    })
    void permittedAuthorOrAdminCanMoveAPost(String userId, String role, String targetCode, String expectedCode) {
        signIn(userId, role);
        Board board = board("BOARD_FREE".equals(expectedCode) ? "BOARD_QUESTION" : "BOARD_FREE");
        given(boardRepository.findById(7)).willReturn(Optional.of(board));

        boardService.updateBoard(7, BoardUpdateRequest.builder().code(targetCode).build(), false, null);

        assertThat(board.getCode()).isEqualTo(expectedCode);
        verify(replyRepository).countByBoardId(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOARD_QUESTION", " question "})
    void anExistingQuestionAuthorCanResubmitTheSameBoardAfterTheirRoleChanges(String targetCode) {
        signIn("author", "user");
        Board board = board("BOARD_QUESTION");
        given(boardRepository.findById(7)).willReturn(Optional.of(board));

        boardService.updateBoard(7, BoardUpdateRequest.builder().code(targetCode).build(), false, null);

        assertThat(board.getCode()).isEqualTo("BOARD_QUESTION");
    }

    @Test
    void anExistingQuestionAuthorCanEditWithoutChangingTheBoard() {
        signIn("author", "user");
        Board board = board("BOARD_QUESTION");
        given(boardRepository.findById(7)).willReturn(Optional.of(board));
        given(bannedWordService.filter("Changed title")).willReturn("Filtered title");

        boardService.updateBoard(7, BoardUpdateRequest.builder().boardTitle("Changed title").build(), false, null);

        assertThat(board.getCode()).isEqualTo("BOARD_QUESTION");
        assertThat(board.getBoardTitle()).isEqualTo("Filtered title");
    }

    @Test
    void aTenantCannotMoveSomeoneElsesPost() {
        signIn("another-tenant", "tenant");
        Board board = board("BOARD_FREE");
        given(boardRepository.findById(7)).willReturn(Optional.of(board));

        assertThatThrownBy(() -> boardService.updateBoard(7,
                BoardUpdateRequest.builder().code("question").build(), false, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThat(board.getCode()).isEqualTo("BOARD_FREE");
        verifyNoInteractions(bannedWordService, fileService, replyRepository);
    }

    private static Board board(String code) {
        return Board.builder()
                .boardId(7)
                .userId("author")
                .boardTitle("Original title")
                .boardCtnt("Original content")
                .code(code)
                .fileCk("Y")
                .build();
    }

    private static void signIn(String userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthUser(userId, role), null, List.of()));
    }
}
