package org.myweb.uniplace.domain.community.api.dto.response;

import java.time.LocalDateTime;

import org.myweb.uniplace.domain.community.domain.entity.Reply;
import org.myweb.uniplace.domain.user.domain.entity.User;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReplyResponse {

    private Integer replyId;
    private Integer boardId;

    /**
     * 익명이면 null, 비익명이면 표시용 닉네임.
     */
    private String userId;

    /** 익명 댓글은 관리자에게만 실제 작성자를 제공한다. */
    private String realUserId;
    private String realUserNickname;
    private boolean mine;
    /** 게시글 안의 익명 번호. 0은 익명 게시글 작성자이며 비익명 댓글에는 제공하지 않는다. */
    private Integer anonymousId;

    private String anonymity;   // "Y" or "N"

    private String replyCtnt;

    private Integer parentId;
    private Integer replyLev;
    private Integer replySeq;

    private long likeCount;
    private boolean likedByMe;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ReplyResponse fromEntity(Reply e, long likeCount, boolean likedByMe, User author) {
        return fromEntity(e, likeCount, likedByMe, author, null, false, null);
    }

    public static ReplyResponse fromEntity(Reply e, long likeCount, boolean likedByMe, User author,
                                           String viewerId, boolean administrator, Integer anonymousId) {
        boolean isAnon = "Y".equalsIgnoreCase(e.getAnonymity());
        String nickname = (author != null && author.getUserNickname() != null && !author.getUserNickname().isBlank())
                    ? author.getUserNickname()
                    : e.getUserId();

        return ReplyResponse.builder()
                .replyId(e.getReplyId())
                .boardId(e.getBoardId())
                .userId(isAnon ? null : nickname)
                .realUserId(!isAnon || administrator ? e.getUserId() : null)
                .realUserNickname(!isAnon || administrator ? nickname : null)
                .mine(viewerId != null && viewerId.equals(e.getUserId()))
                .anonymousId(isAnon ? anonymousId : null)
                .anonymity(e.getAnonymity() != null ? e.getAnonymity() : "N")
                .replyCtnt(e.getReplyCtnt())
                .parentId(e.getParentId())
                .replyLev(e.getReplyLev())
                .replySeq(e.getReplySeq())
                .likeCount(likeCount)
                .likedByMe(likedByMe)
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    // 하위 호환
    public static ReplyResponse fromEntity(Reply e, long likeCount, boolean likedByMe) {
        return fromEntity(e, likeCount, likedByMe, null);
    }
}
