package org.myweb.uniplace.domain.ownership.api.dto;

import java.time.LocalDateTime;

public record OwnerApplicationResponse(String userId, String userNm, String userEmail, String status,
                                       String applicationNote, String reviewNote, String reviewedBy,
                                       LocalDateTime createdAt, LocalDateTime reviewedAt) {}
