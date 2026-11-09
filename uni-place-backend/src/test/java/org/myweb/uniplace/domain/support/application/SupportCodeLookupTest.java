package org.myweb.uniplace.domain.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.uniplace.domain.ai.application.moderation.BannedWordService;
import org.myweb.uniplace.domain.commoncode.application.publicapi.CommonCodeLookup;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.support.api.dto.request.ComplainCreateRequest;
import org.myweb.uniplace.domain.support.api.dto.request.ComplainSearchRequest;
import org.myweb.uniplace.domain.support.api.dto.request.QnaCreateRequest;
import org.myweb.uniplace.domain.support.api.dto.request.QnaSearchRequest;
import org.myweb.uniplace.domain.support.domain.entity.Complain;
import org.myweb.uniplace.domain.support.domain.entity.Qna;
import org.myweb.uniplace.domain.support.repository.ComplainRepository;
import org.myweb.uniplace.domain.support.repository.QnaRepository;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class SupportCodeLookupTest {
    private static final PageRequest PAGE = PageRequest.of(0, 10);
    private final ObjectMapper json = new ObjectMapper();

    @Mock private QnaRepository qnaRepository;
    @Mock private ComplainRepository complainRepository;
    @Mock private CommonCodeLookup commonCodeLookup;
    @Mock private NotificationService notificationService;
    @Mock private ComplainAiAsyncService complainAiAsyncService;
    @Mock private BannedWordService bannedWordService;
    @InjectMocks private QnaServiceImpl qnaService;
    @InjectMocks private ComplainServiceImpl complainService;

    private record CodeCase(String input, String lookup, boolean exists, String filter, String saved) {}

    static Stream<CodeCase> codes() {
        return Stream.of(
                new CodeCase(null, null, false, null, "SUP_GENERAL"),
                new CodeCase("  ", null, false, null, "SUP_GENERAL"),
                new CodeCase(" all ", null, false, null, "SUP_GENERAL"),
                new CodeCase(" general ", "SUP_GENERAL", true, "SUP_GENERAL", "SUP_GENERAL"),
                new CodeCase(" billing ", "SUP_BILLING", true, "SUP_BILLING", "SUP_BILLING"),
                new CodeCase(" custom_known ", "CUSTOM_KNOWN", true, "CUSTOM_KNOWN", "CUSTOM_KNOWN"),
                new CodeCase(" custom_missing ", "CUSTOM_MISSING", false, null, "SUP_GENERAL"),
                new CodeCase(" code_inactive ", "CODE_INACTIVE", true, "CODE_INACTIVE", "CODE_INACTIVE"));
    }

    @ParameterizedTest
    @MethodSource("codes")
    void publicSearchesPreserveCodeFilteringAndUserScope(CodeCase code) {
        if (code.lookup() != null) given(commonCodeLookup.exists(code.lookup())).willReturn(code.exists());
        given(qnaRepository.search(any(), any(), any(), any(), eq(PAGE))).willReturn(Page.empty(PAGE));
        given(qnaRepository.searchAll(any(), any(), any(), eq(PAGE))).willReturn(Page.empty(PAGE));
        given(complainRepository.search(any(), any(), any(), any(), any(), eq(PAGE))).willReturn(Page.empty(PAGE));

        QnaSearchRequest qnaRequest = request(QnaSearchRequest.class, code.input());
        ComplainSearchRequest complainRequest = request(ComplainSearchRequest.class, code.input());
        assertThat(qnaService.search(qnaRequest, PAGE, "authenticated-user", false).content()).isEmpty();
        assertThat(qnaService.searchAll(qnaRequest, PAGE).content()).isEmpty();
        assertThat(complainService.search(complainRequest, PAGE).content()).isEmpty();

        verify(qnaRepository).search("authenticated-user", code.filter(), null, null, PAGE);
        verify(qnaRepository).searchAll(code.filter(), null, null, PAGE);
        verify(complainRepository).search("requested-user", code.filter(), null, null, null, PAGE);
        if (code.lookup() == null) verifyNoInteractions(commonCodeLookup);
    }

    @ParameterizedTest
    @MethodSource("codes")
    void publicCreatesPreserveSavedCodeAndResponseFields(CodeCase code) {
        if (code.lookup() != null) given(commonCodeLookup.exists(code.lookup())).willReturn(code.exists());
        given(bannedWordService.filter(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(qnaRepository.save(any(Qna.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(complainRepository.save(any(Complain.class))).willAnswer(invocation -> invocation.getArgument(0));

        var qna = qnaService.create("authenticated-user", request(QnaCreateRequest.class, code.input()));
        var complain = complainService.create("authenticated-user", request(ComplainCreateRequest.class, code.input()));

        assertThat(qna.getCode()).isEqualTo(code.saved());
        assertThat(qna.getUserId()).isEqualTo("authenticated-user");
        assertThat(qna.getQnaTitle()).isEqualTo("Question title");
        assertThat(qna.getQnaCtnt()).isEqualTo("Question content");
        assertThat(complain.getCode()).isEqualTo(code.saved());
        assertThat(complain.getUserId()).isEqualTo("authenticated-user");
        assertThat(complain.getCompTitle()).isEqualTo("Complaint title");
        assertThat(complain.getCompCtnt()).isEqualTo("Complaint content");
        verify(complainAiAsyncService).classifyAndNotify(null, "authenticated-user", "Complaint title", "Complaint content");
        if (code.lookup() == null) verifyNoInteractions(commonCodeLookup);
    }

    @ParameterizedTest
    @CsvSource({"qna_new_category, QNA_NEW_CATEGORY", "comp_new_category, COMP_NEW_CATEGORY"})
    void existingSpecialWritePrefixesDoNotPerformAnExistenceLookup(String input, String expected) {
        given(bannedWordService.filter(any())).willAnswer(invocation -> invocation.getArgument(0));
        if (expected.startsWith("QNA_")) {
            given(qnaRepository.save(any(Qna.class))).willAnswer(invocation -> invocation.getArgument(0));
            var result = qnaService.create("authenticated-user", request(QnaCreateRequest.class, input));
            assertThat(result.getCode()).isEqualTo(expected);
        } else {
            given(complainRepository.save(any(Complain.class))).willAnswer(invocation -> invocation.getArgument(0));
            var result = complainService.create("authenticated-user", request(ComplainCreateRequest.class, input));
            assertThat(result.getCode()).isEqualTo(expected);
        }
        verifyNoInteractions(commonCodeLookup);
    }

    @Test
    void aCodeLookupFailureIsNotReplacedByAnUnfilteredReadOrADefaultWrite() {
        var failure = new DataAccessResourceFailureException("unavailable test database");
        given(commonCodeLookup.exists("CUSTOM_KNOWN")).willThrow(failure);
        given(bannedWordService.filter(any())).willAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> qnaService.search(request(QnaSearchRequest.class, "custom_known"), PAGE,
                "authenticated-user", false)).isSameAs(failure);
        assertThatThrownBy(() -> complainService.search(request(ComplainSearchRequest.class, "custom_known"), PAGE))
                .isSameAs(failure);
        assertThatThrownBy(() -> qnaService.create("authenticated-user", request(QnaCreateRequest.class, "custom_known")))
                .isSameAs(failure);
        assertThatThrownBy(() -> complainService.create("authenticated-user", request(ComplainCreateRequest.class, "custom_known")))
                .isSameAs(failure);

        verify(qnaRepository, never()).save(any());
        verify(complainRepository, never()).save(any());
        verifyNoInteractions(qnaRepository, complainRepository, notificationService, complainAiAsyncService);
    }

    private <T> T request(Class<T> type, String code) {
        Map<String, Object> input = new HashMap<>();
        input.put("code", code);
        if (type == QnaSearchRequest.class || type == ComplainSearchRequest.class) {
            input.put("userId", "requested-user");
        } else if (type == QnaCreateRequest.class) {
            input.put("qnaTitle", "Question title");
            input.put("qnaCtnt", "Question content");
        } else {
            input.put("compTitle", "Complaint title");
            input.put("compCtnt", "Complaint content");
        }
        return json.convertValue(input, type);
    }
}
