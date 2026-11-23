package org.myweb.uniplace.domain.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.uniplace.domain.ai.application.moderation.BannedWordService;
import org.myweb.uniplace.domain.ownership.application.OwnershipAccessService;
import org.myweb.uniplace.domain.contract.domain.entity.Contract;
import org.myweb.uniplace.domain.contract.domain.enums.ContractStatus;
import org.myweb.uniplace.domain.contract.repository.ContractRepository;
import org.myweb.uniplace.domain.file.application.FileService;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.review.api.dto.request.ReviewCreateRequest;
import org.myweb.uniplace.domain.review.application.ReviewServiceImpl;
import org.myweb.uniplace.domain.review.domain.entity.Review;
import org.myweb.uniplace.domain.review.repository.ReviewRepository;
import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.security.AuthUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real contract predicates and caller transaction visibility through the public review creation path. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OwnershipAccessService.class, ReviewServiceImpl.class, ReviewContractHistoryAdapter.class})
@Testcontainers
class ReviewContractHistoryIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("review_contract_history")
            .withUsername("review_history_test")
            .withPassword("review-history-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired UserRepository users;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired ContractRepository contracts;
    @Autowired ReviewRepository reviews;
    @Autowired ReviewServiceImpl service;
    @MockitoBean FileService files;
    @MockitoBean NotificationService notifications;
    @MockitoBean BannedWordService bannedWords;

    private Building building;
    private int nextRoomNo;

    @BeforeEach
    void prepareBuildingAndContentFilter() {
        building = buildings.saveAndFlush(Building.builder()
                .buildingNm("Review contract boundary").buildingAddr("Test fixture address").build());
        given(bannedWords.filter(anyString())).willAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeAndEndedHistoryAllowsARegularUserRegardlessOfContractDates() {
        User author = user("history-allowed");
        Room futureRoom = room();
        Room pastRoom = room();
        contract(author, futureRoom, ContractStatus.active, LocalDate.of(2100, 1, 1));
        contract(author, pastRoom, ContractStatus.ended, LocalDate.of(2000, 1, 1));

        createReview(author, futureRoom);
        createReview(author, pastRoom);
        reviews.flush();

        assertThat(reviews.findAll().stream().filter(review -> author.getUserId().equals(review.getUserId())))
                .extracting(Review::getRoomId).containsExactlyInAnyOrder(futureRoom.getRoomId(), pastRoom.getRoomId());
        assertThat(reviews.findAll().stream().filter(review -> author.getUserId().equals(review.getUserId())))
                .allSatisfy(review -> {
                    assertThat(review.getRating()).isEqualTo(4);
                    assertThat(review.getReviewTitle()).isEqualTo("Contract boundary");
                    assertThat(review.getReviewCtnt()).isEqualTo("Review body");
                    assertThat(review.getFileCk()).isEqualTo("N");
                    assertThat(review.getReplyCk()).isEqualTo("N");
                });
    }

    @Test
    void otherStatusesAndAnotherUsersOrRoomsHistoryRetainTheNoContractError() {
        List<User> rejectedAuthors = new ArrayList<>();
        List<Room> rejectedRooms = new ArrayList<>();
        for (ContractStatus status : List.of(ContractStatus.requested, ContractStatus.approved, ContractStatus.cancelled)) {
            User author = user("history-" + status);
            Room target = room();
            contract(author, target, status, LocalDate.of(2000, 1, 1));
            rejectedAuthors.add(author);
            rejectedRooms.add(target);
        }
        User scopedAuthor = user("history-scoped");
        Room targetRoom = room();
        contract(scopedAuthor, room(), ContractStatus.active, LocalDate.of(2000, 1, 1));
        contract(user("history-other"), targetRoom, ContractStatus.ended, LocalDate.of(2000, 1, 1));
        rejectedAuthors.add(scopedAuthor);
        rejectedRooms.add(targetRoom);

        for (int i = 0; i < rejectedAuthors.size(); i++) {
            User author = rejectedAuthors.get(i);
            Room target = rejectedRooms.get(i);
            assertThatThrownBy(() -> createReview(author, target))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.REVIEW_NO_CONTRACT));
            assertThat(reviews.existsByUserIdAndRoomId(author.getUserId(), target.getRoomId())).isFalse();
        }
    }

    @Test
    void uncommittedContractStatusChangesAndTheReviewShareTheCallerTransaction() throws Exception {
        User author = user("history-pending");
        Room target = room();
        Contract pending = contract(author, target, ContractStatus.requested, LocalDate.of(2000, 1, 1));
        // The caller has not flushed or committed this transition before entering the service.
        pending.setContractSt(ContractStatus.ended);

        createReview(author, target);
        reviews.flush();

        assertThat(reviews.existsByUserIdAndRoomId(author.getUserId(), target.getRoomId())).isTrue();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var contractCount = connection.prepareStatement("SELECT COUNT(*) FROM contract WHERE contract_id = ?");
             var reviewCount = connection.prepareStatement("SELECT COUNT(*) FROM reviews WHERE user_id = ? AND room_id = ?")) {
            contractCount.setInt(1, pending.getContractId());
            try (var rows = contractCount.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).as("the caller contract remains uncommitted").isZero();
            }
            reviewCount.setString(1, author.getUserId());
            reviewCount.setInt(2, target.getRoomId());
            try (var rows = reviewCount.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).as("review creation does not commit independently").isZero();
            }
        }
    }

    private void createReview(User author, Room target) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthUser(author.getUserId(), "USER"), null, List.of()));
        service.createReview(ReviewCreateRequest.builder().roomId(target.getRoomId()).rating(4)
                .reviewTitle("Contract boundary").reviewCtnt("Review body").code("BOUNDARY_REVIEW").build(), List.of());
    }

    private User user(String id) {
        return users.saveAndFlush(User.builder().userId(id).userNm(id).userEmail(id + "@example.test")
                .userPwd("test-only-hash").userBirth(LocalDate.of(2000, 1, 1)).userTel("010-0000-0000")
                .userNickname(id).userRole(UserRole.user).build());
    }

    private Room room() {
        return rooms.saveAndFlush(Room.builder().building(building).roomNo(++nextRoomNo).floor(1)
                .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000")).build());
    }

    private Contract contract(User author, Room target, ContractStatus status, LocalDate start) {
        return contracts.saveAndFlush(Contract.builder().user(author).room(target).contractSt(status)
                .contractStart(start).contractEnd(start.plusMonths(6)).rentPrice(new BigDecimal("500000"))
                .lessorNm("Test lessor").lessorTel("010-1111-1111").lessorAddr("Fixture address")
                .lessorRrn("test-only").build());
    }
}
