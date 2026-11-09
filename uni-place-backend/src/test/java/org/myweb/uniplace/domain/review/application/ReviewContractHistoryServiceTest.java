package org.myweb.uniplace.domain.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.uniplace.domain.ai.application.moderation.BannedWordService;
import org.myweb.uniplace.domain.contract.application.publicapi.ReviewContractHistory;
import org.myweb.uniplace.domain.file.api.dto.request.FileUploadRequest;
import org.myweb.uniplace.domain.file.application.FileService;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.notification.domain.enums.TargetType;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.review.api.dto.request.ReviewCreateRequest;
import org.myweb.uniplace.domain.review.domain.entity.Review;
import org.myweb.uniplace.domain.review.repository.ReviewLikeRepository;
import org.myweb.uniplace.domain.review.repository.ReviewRepository;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.security.AuthUser;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class ReviewContractHistoryServiceTest {
    private static final String USER = "former-tenant";
    private static final int ROOM = 41;

    @Mock private ReviewRepository reviewRepository;
    @Mock private ReviewLikeRepository reviewLikeRepository;
    @Mock private RoomRepository roomRepository;
    @Mock private ReviewContractHistory reviewContractHistory;
    @Mock private FileService fileService;
    @Mock private NotificationService notificationService;
    @Mock private UserRepository userRepository;
    @Mock private BannedWordService bannedWordService;
    @InjectMocks private ReviewServiceImpl reviews;

    @BeforeEach
    void signInAsAUserWhoNoLongerHasTheTenantRole() {
        AuthUser principal = new AuthUser(USER, "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticationIsRequiredBeforeLookingUpTheRoomOrContract() {
        SecurityContextHolder.clearContext();

        assertError(() -> reviews.createReview(request(), null), ErrorCode.UNAUTHORIZED);

        verifyNoInteractions(roomRepository, reviewContractHistory, reviewRepository, fileService,
                notificationService, bannedWordService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMissingOrDeletedRoomPrecedesTheContractAndDuplicateChecks(boolean present) {
        Room room = Room.builder().roomId(ROOM).deleteYn("Y").build();
        given(roomRepository.findById(ROOM)).willReturn(present ? Optional.of(room) : Optional.empty());

        assertError(() -> reviews.createReview(request(), null), ErrorCode.ROOM_NOT_FOUND);

        verifyNoInteractions(reviewContractHistory, reviewRepository, fileService, notificationService, bannedWordService);
    }

    @Test
    void noContractHistoryRetainsTheForbiddenErrorBeforeCheckingForADuplicate() {
        availableRoom();
        given(reviewContractHistory.hasActiveOrEndedContract(USER, ROOM)).willReturn(false);

        assertError(() -> reviews.createReview(request(), null), ErrorCode.REVIEW_NO_CONTRACT);

        var order = inOrder(roomRepository, reviewContractHistory);
        order.verify(roomRepository).findById(ROOM);
        order.verify(reviewContractHistory).hasActiveOrEndedContract(USER, ROOM);
        verifyNoInteractions(reviewRepository, fileService, notificationService, bannedWordService);
    }

    @Test
    void aDuplicateIsRejectedOnlyAfterContractHistoryHasBeenConfirmed() {
        availableRoom();
        given(reviewContractHistory.hasActiveOrEndedContract(USER, ROOM)).willReturn(true);
        given(reviewRepository.existsByUserIdAndRoomId(USER, ROOM)).willReturn(true);

        assertError(() -> reviews.createReview(request(), null), ErrorCode.REVIEW_DUPLICATE);

        var order = inOrder(roomRepository, reviewContractHistory, reviewRepository);
        order.verify(roomRepository).findById(ROOM);
        order.verify(reviewContractHistory).hasActiveOrEndedContract(USER, ROOM);
        order.verify(reviewRepository).existsByUserIdAndRoomId(USER, ROOM);
        verifyNoInteractions(fileService, notificationService, bannedWordService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eligibleHistoryKeepsTheSavedFieldsAndFileAndNotificationOrder(boolean withFile) {
        availableRoom();
        given(reviewContractHistory.hasActiveOrEndedContract(USER, ROOM)).willReturn(true);
        given(bannedWordService.filter("Original title")).willReturn("Filtered title");
        given(bannedWordService.filter("Original content")).willReturn("Filtered content");
        Review saved = Review.builder().reviewId(73).build();
        given(reviewRepository.save(any(Review.class))).willReturn(saved);
        List<MultipartFile> files = withFile
                ? List.of(new MockMultipartFile("ofiles", "photo.png", "image/png", new byte[]{1})) : null;

        reviews.createReview(request(), files);

        var order = inOrder(roomRepository, reviewContractHistory, reviewRepository,
                bannedWordService, fileService, notificationService);
        order.verify(roomRepository).findById(ROOM);
        order.verify(reviewContractHistory).hasActiveOrEndedContract(USER, ROOM);
        order.verify(reviewRepository).existsByUserIdAndRoomId(USER, ROOM);
        order.verify(bannedWordService).filter("Original title");
        order.verify(bannedWordService).filter("Original content");
        ArgumentCaptor<Review> entry = ArgumentCaptor.forClass(Review.class);
        order.verify(reviewRepository).save(entry.capture());
        assertThat(entry.getValue().getUserId()).isEqualTo(USER);
        assertThat(entry.getValue().getRoomId()).isEqualTo(ROOM);
        assertThat(entry.getValue().getRating()).isEqualTo(4);
        assertThat(entry.getValue().getReviewTitle()).isEqualTo("Filtered title");
        assertThat(entry.getValue().getReviewCtnt()).isEqualTo("Filtered content");
        assertThat(entry.getValue().getCode()).isEqualTo("REV_RENT");
        assertThat(saved.getFileCk()).isEqualTo(withFile ? "Y" : "N");
        assertThat(saved.getReplyCk()).isEqualTo("N");
        if (withFile) {
            ArgumentCaptor<FileUploadRequest> upload = ArgumentCaptor.forClass(FileUploadRequest.class);
            order.verify(fileService).uploadFiles(upload.capture());
            assertThat(upload.getValue().getFileParentType()).isEqualTo("REVIEW");
            assertThat(upload.getValue().getFileParentId()).isEqualTo(73);
            assertThat(upload.getValue().getFiles()).isSameAs(files);
        } else {
            verifyNoInteractions(fileService);
        }
        order.verify(notificationService).notifyAdmins("RVW_NEW",
                "새 리뷰가 등록되었습니다. (roomId=41, 별점=4★)", USER, TargetType.review, 73, "/admin/reviews");
        verifyNoInteractions(userRepository, reviewLikeRepository);
    }

    @Test
    void aContractLookupFailurePropagatesWithoutSavingOrProducingSideEffects() {
        availableRoom();
        var failure = new DataAccessResourceFailureException("contract lookup unavailable");
        given(reviewContractHistory.hasActiveOrEndedContract(USER, ROOM)).willThrow(failure);

        assertThatThrownBy(() -> reviews.createReview(request(), null)).isSameAs(failure);

        verifyNoInteractions(reviewRepository, fileService, notificationService, bannedWordService);
    }

    private void availableRoom() {
        given(roomRepository.findById(ROOM)).willReturn(Optional.of(Room.builder().roomId(ROOM).deleteYn("N").build()));
    }

    private static ReviewCreateRequest request() {
        return ReviewCreateRequest.builder().roomId(ROOM).rating(4).reviewTitle("Original title")
                .reviewCtnt("Original content").code("REV_RENT").build();
    }

    private static void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
    }
}
