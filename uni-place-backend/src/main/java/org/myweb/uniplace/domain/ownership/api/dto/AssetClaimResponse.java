package org.myweb.uniplace.domain.ownership.api.dto;

import java.time.LocalDateTime;

public record AssetClaimResponse(Integer membershipId, String resourceType, Integer resourceId,
                                 String resourceName, String userId, String userNm, String status,
                                 String origin, String proofReference, String reviewNote, String reviewedBy,
                                 LocalDateTime createdAt, LocalDateTime reviewedAt, LocalDateTime updatedAt) {}
