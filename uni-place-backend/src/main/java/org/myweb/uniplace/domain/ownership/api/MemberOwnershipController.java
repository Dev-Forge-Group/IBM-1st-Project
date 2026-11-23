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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/users/me")
public class MemberOwnershipController {
    private final OwnershipReviewService reviews;

    @GetMapping("/owner-application")
    public ApiResponse<OwnerApplicationResponse> myApplication() {
        return ApiResponse.ok(reviews.myApplication());
    }

    @PostMapping("/owner-application")
    public ApiResponse<OwnerApplicationResponse> apply(@Valid @RequestBody OwnershipRequests.OwnerApplication request) {
        return ApiResponse.ok(reviews.apply(request.applicationNote()));
    }

    @GetMapping("/asset-claims")
    public ApiResponse<List<AssetClaimResponse>> myClaims() {
        return ApiResponse.ok(reviews.myClaims());
    }

    @PostMapping("/asset-claims")
    public ApiResponse<AssetClaimResponse> claim(@Valid @RequestBody OwnershipRequests.AssetClaim request) {
        return ApiResponse.ok(reviews.claim(request.resourceType(), request.resourceId(), request.proofReference()));
    }
}
