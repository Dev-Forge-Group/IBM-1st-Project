package org.myweb.uniplace.domain.ownership.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.ownership.api.dto.AssetClaimResponse;
import org.myweb.uniplace.domain.ownership.api.dto.OwnerApplicationResponse;
import org.myweb.uniplace.domain.ownership.api.dto.OwnershipRequests;
import org.myweb.uniplace.domain.ownership.application.OwnershipReviewService;
import org.myweb.uniplace.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class StaffOwnershipController {
    private final OwnershipReviewService reviews;

    @GetMapping("/owner-applications")
    public ApiResponse<List<OwnerApplicationResponse>> applications(@RequestParam(required = false) String status) {
        return ApiResponse.ok(reviews.applications(status));
    }

    @PostMapping("/owner-applications/{userId}/approve")
    public ApiResponse<OwnerApplicationResponse> approveApplication(@PathVariable String userId,
            @Valid @RequestBody OwnershipRequests.Review request) {
        return ApiResponse.ok(reviews.reviewApplication(userId, true, request.reviewNote()));
    }

    @PostMapping("/owner-applications/{userId}/reject")
    public ApiResponse<OwnerApplicationResponse> rejectApplication(@PathVariable String userId,
            @Valid @RequestBody OwnershipRequests.Review request) {
        return ApiResponse.ok(reviews.reviewApplication(userId, false, request.reviewNote()));
    }

    @GetMapping("/asset-claims")
    public ApiResponse<List<AssetClaimResponse>> claims(@RequestParam(required = false) String status) {
        return ApiResponse.ok(reviews.claims(status));
    }

    @PostMapping("/asset-claims/{resourceType}/{membershipId}/approve")
    public ApiResponse<AssetClaimResponse> approveClaim(@PathVariable String resourceType,
            @PathVariable Integer membershipId, @Valid @RequestBody OwnershipRequests.Review request) {
        return ApiResponse.ok(reviews.reviewClaim(resourceType, membershipId, true, request.reviewNote()));
    }

    @PostMapping("/asset-claims/{resourceType}/{membershipId}/reject")
    public ApiResponse<AssetClaimResponse> rejectClaim(@PathVariable String resourceType,
            @PathVariable Integer membershipId, @Valid @RequestBody OwnershipRequests.Review request) {
        return ApiResponse.ok(reviews.reviewClaim(resourceType, membershipId, false, request.reviewNote()));
    }
}
