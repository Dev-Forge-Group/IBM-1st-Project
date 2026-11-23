// 경로: org/myweb/uniplace/domain/property/api/admin/AdminRoomController.java
package org.myweb.uniplace.domain.property.api.admin;

import org.myweb.uniplace.domain.property.api.dto.request.RoomCreateRequest;
import org.myweb.uniplace.domain.property.api.dto.request.RoomSearchRequest;
import org.myweb.uniplace.domain.property.api.dto.request.RoomStatusUpdateRequest;
import org.myweb.uniplace.domain.property.api.dto.request.RoomUpdateRequest;
import org.myweb.uniplace.domain.property.api.dto.response.RoomDetailResponse;
import org.myweb.uniplace.domain.property.api.dto.response.RoomSummaryResponse;
import org.myweb.uniplace.domain.property.application.RoomService;
import org.myweb.uniplace.global.response.ApiResponse;
import org.myweb.uniplace.global.response.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/owner/rooms")
public class AdminRoomController {

    private final RoomService roomService;

    @GetMapping
    public ApiResponse<PageResponse<RoomSummaryResponse>> list(
            @ModelAttribute RoomSearchRequest request,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sort", defaultValue = "roomId") String sort,
            @RequestParam(name = "direct", defaultValue = "DESC") String direct) {
        Sort.Direction direction = "ASC".equalsIgnoreCase(direct) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return ApiResponse.ok(PageResponse.of(roomService.searchOwnedPage(request,
                PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 100), Sort.by(direction, sort)))));
    }

    @GetMapping("/{roomId}")
    public ApiResponse<RoomDetailResponse> detail(@PathVariable("roomId") Integer roomId) {
        return ApiResponse.ok(roomService.getRoomForAdmin(roomId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RoomDetailResponse> create(@Validated @ModelAttribute RoomCreateRequest request) {
        return ApiResponse.ok(roomService.createRoom(request));
    }

    @PutMapping(value = "/{roomId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RoomDetailResponse> update(
            @PathVariable("roomId") Integer roomId,
            @Validated @ModelAttribute RoomUpdateRequest request
    ) {
        return ApiResponse.ok(roomService.updateRoom(roomId, request));
    }

    @PutMapping("/{roomId}/status")
    public ApiResponse<Void> changeStatus(
            @PathVariable("roomId") Integer roomId,
            @Validated @RequestBody RoomStatusUpdateRequest request
    ) {
        roomService.changeRoomStatus(roomId, request.getRoomStatus());
        return ApiResponse.ok();
    }

    // ✅ 신규: Room soft delete
    @DeleteMapping("/{roomId}")
    public ApiResponse<Void> delete(@PathVariable("roomId") Integer roomId) {
        roomService.deleteRoom(roomId);
        return ApiResponse.ok();
    }
}
