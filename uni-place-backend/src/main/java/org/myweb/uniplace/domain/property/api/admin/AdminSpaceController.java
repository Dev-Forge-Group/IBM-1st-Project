// 경로: org/myweb/uniplace/domain/property/api/admin/AdminSpaceController.java
package org.myweb.uniplace.domain.property.api.admin;

import org.myweb.uniplace.domain.property.api.dto.request.SpaceCreateRequest;
import org.myweb.uniplace.domain.property.api.dto.request.SpaceSearchRequest;
import org.myweb.uniplace.domain.property.api.dto.response.SpaceResponse;
import org.myweb.uniplace.global.response.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.myweb.uniplace.domain.property.api.dto.request.SpaceUpdateRequest;
import org.myweb.uniplace.domain.property.api.dto.response.SpaceDetailResponse;
import org.myweb.uniplace.domain.property.application.SpaceService;
import org.myweb.uniplace.global.response.ApiResponse;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/owner/spaces")
public class AdminSpaceController {

    private final SpaceService spaceService;

    @GetMapping
    public ApiResponse<PageResponse<SpaceResponse>> list(
            @ModelAttribute SpaceSearchRequest request,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sort", defaultValue = "spaceId") String sort,
            @RequestParam(name = "direct", defaultValue = "DESC") String direct) {
        int selectedPage = Math.max(1, page);
        int selectedSize = Math.min(100, Math.max(1, size));
        Sort.Direction direction = "ASC".equalsIgnoreCase(direct) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return ApiResponse.ok(PageResponse.of(spaceService.searchOwnedPage(request,
                PageRequest.of(selectedPage - 1, selectedSize, direction, sort))));
    }

    @GetMapping("/{spaceId}")
    public ApiResponse<SpaceDetailResponse> detail(
            @PathVariable("spaceId") Integer spaceId
    ) {
        // 소유 관계를 확인한 소유주 상세
        return ApiResponse.ok(spaceService.getSpaceForOwner(spaceId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SpaceDetailResponse> create(
            @Validated @ModelAttribute SpaceCreateRequest request
    ) {
        return ApiResponse.ok(spaceService.createSpace(request));
    }

    @PutMapping(value = "/{spaceId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SpaceDetailResponse> update(
            @PathVariable("spaceId") Integer spaceId,
            @Validated @ModelAttribute SpaceUpdateRequest request
    ) {
        return ApiResponse.ok(spaceService.updateSpace(spaceId, request));
    }

    @DeleteMapping("/{spaceId}")
    public ApiResponse<Void> delete(
            @PathVariable("spaceId") Integer spaceId
    ) {
        spaceService.deleteSpace(spaceId);
        return ApiResponse.ok();
    }
}