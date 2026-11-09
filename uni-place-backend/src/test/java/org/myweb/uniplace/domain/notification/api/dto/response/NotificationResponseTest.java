package org.myweb.uniplace.domain.notification.api.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.uniplace.domain.notification.domain.entity.Notification;
import org.myweb.uniplace.domain.notification.domain.enums.TargetType;

class NotificationResponseTest {

    @ParameterizedTest
    @ValueSource(strings = {"BRD_REPLY", "BRD_REREPLY", "brd_reply", "brd_rereply"})
    void communityReplyAlertsDoNotIdentifyTheirSenderEvenForPreviouslyStoredAlerts(String code) throws Exception {
        Notification notification = notification(code);

        NotificationResponse response = NotificationResponse.from(notification);
        JsonNode json = new ObjectMapper().valueToTree(response);

        assertThat(response.getSenderId()).isNull();
        assertThat(json.get("senderId").isNull()).isTrue();
        assertThat(json.toString()).doesNotContain("private-reply-author");
        assertThat(response.getNotificationId()).isEqualTo(12);
        assertThat(response.getReceiverId()).isEqualTo("recipient");
        assertThat(response.getTarget()).isEqualTo("reply");
        assertThat(response.getTargetId()).isEqualTo(34);
        assertThat(response.getUrlPath()).isEqualTo("/community/boards/7");
        assertThat(response.getMessage()).isEqualTo("작성하신 게시글에 댓글이 달렸습니다.");
        assertThat(notification.getSenderId()).isEqualTo("private-reply-author");
    }

    @ParameterizedTest
    @ValueSource(strings = {"BRD_LIKE", "RPL_LIKE", "QNA_ANSWERED", "PAY_OK"})
    void otherNotificationTypesKeepTheirSender(String code) {
        NotificationResponse response = NotificationResponse.from(notification(code));

        assertThat(response.getSenderId()).isEqualTo("private-reply-author");
        assertThat(response.getCode()).isEqualTo(code);
    }

    private static Notification notification(String code) {
        return Notification.builder()
                .notificationId(12)
                .receiverId("recipient")
                .code(code)
                .senderId("private-reply-author")
                .message("작성하신 게시글에 댓글이 달렸습니다.")
                .target(TargetType.reply)
                .targetId(34)
                .urlPath("/community/boards/7")
                .isRead("N")
                .createdAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();
    }
}
