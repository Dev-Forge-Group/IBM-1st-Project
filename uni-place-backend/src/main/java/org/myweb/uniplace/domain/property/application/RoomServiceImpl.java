package org.myweb.uniplace.domain.property.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.myweb.uniplace.domain.file.api.dto.request.FileUploadRequest;
import org.myweb.uniplace.domain.file.api.dto.response.FileResponse;
import org.myweb.uniplace.domain.file.application.FileService;
import org.myweb.uniplace.domain.file.domain.enums.FileRefType;
import org.myweb.uniplace.domain.property.api.dto.request.RoomCreateRequest;
import org.myweb.uniplace.domain.property.api.dto.request.RoomSearchRequest;
import org.myweb.uniplace.domain.property.api.dto.request.RoomUpdateRequest;
import org.myweb.uniplace.domain.property.api.dto.response.RoomDetailResponse;
import org.myweb.uniplace.domain.property.api.dto.response.RoomSummaryResponse;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.domain.enums.RoomStatus;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.property.repository.PropertySpecifications;
import org.myweb.uniplace.domain.ownership.application.publicapi.OwnershipAccess;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class RoomServiceImpl implements RoomService {

    private final RoomRepository roomRepository;
    private final BuildingRepository buildingRepository;
    private final FileService fileService;
    private final OwnershipAccess ownershipAccess;

    private static final Set<String> IMAGE_EXTS =
            Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp");

    private boolean isImageExt(String ext) {
        if (ext == null) return false;
        return IMAGE_EXTS.contains(ext.toLowerCase());
    }

    @Override
    @Transactional(readOnly = true)
    public RoomDetailResponse getRoom(Integer roomId) {
        ownershipAccess.requirePublicRoom(roomId);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        if (room.isDeleted()) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }

        List<FileResponse> files =
                fileService.getActiveFiles(FileRefType.ROOM.dbValue(), roomId);

        return RoomDetailResponse.fromPublicEntity(room, files).toBuilder()
                .verifiedRentalOwner(!ownershipAccess.eligibleOwnerIdsForRoom(roomId).isEmpty()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public RoomDetailResponse getRoomForAdmin(Integer roomId) {
        ownershipAccess.requireCanManageRoom(roomId);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        List<FileResponse> files =
                fileService.getActiveFiles(FileRefType.ROOM.dbValue(), roomId);

        return ownerResponse(room, files);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RoomSummaryResponse> searchPage(RoomSearchRequest request, Pageable pageable) {
        return mapRooms(roomRepository.findAll(PropertySpecifications.rooms(
                ownershipAccess.publicRoomIds(), request), pageable), false);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RoomSummaryResponse> searchOwnedPage(RoomSearchRequest request, Pageable pageable) {
        ownershipAccess.requireOwner();
        return mapRooms(roomRepository.findAll(PropertySpecifications.rooms(
                ownershipAccess.manageableRoomIds(), request), pageable), true);
    }

    private Page<RoomSummaryResponse> mapRooms(Page<Room> page, boolean owner) {
        List<Integer> ids = page.getContent().stream().map(Room::getRoomId).toList();
        Map<Integer, List<FileResponse>> filesMap = fileService.getActiveFilesMap(FileRefType.ROOM.dbValue(), ids);
        return page.map(room -> {
            FileResponse image = filesMap.getOrDefault(room.getRoomId(), List.of()).stream()
                    .filter(file -> file != null && isImageExt(file.getFileType())).findFirst().orElse(null);
            RoomSummaryResponse response = RoomSummaryResponse.fromEntity(room,
                    image == null ? null : image.getFileId(), image == null ? null : image.getViewUrl());
            return owner ? response.withOwnershipStatus(ownershipAccess.roomStatus(room.getRoomId()))
                    .withPublicVisibility(ownershipAccess.isPublicRoom(room.getRoomId())) : response;
        });
    }

    private RoomDetailResponse ownerResponse(Room room, List<FileResponse> files) {
        String ownerId = ownershipAccess.requireOwner();
        Building building = room.getBuilding();
        boolean buildingManagementAllowed = building != null
                && ownershipAccess.canManageBuilding(ownerId, building.getBuildingId());
        RoomDetailResponse response = buildingManagementAllowed
                ? RoomDetailResponse.fromEntity(room, files) : RoomDetailResponse.fromPublicEntity(room, files);
        return response.withOwnershipStatus(ownershipAccess.roomStatus(room.getRoomId()))
                .withPublicVisibility(ownershipAccess.isPublicRoom(room.getRoomId()))
                .withBuildingManagement(buildingManagementAllowed);
    }

    @Override
    public RoomDetailResponse createRoom(RoomCreateRequest request) {
        ownershipAccess.requireOwner();
        Building building = resolveBuilding(request.getBuildingId(), request.getBuildingNm());
        ownershipAccess.requireCanManageBuilding(building.getBuildingId());

        Room room = Room.builder()
                .building(building)
                .roomNo(request.getRoomNo())
                .floor(request.getFloor())
                .roomSize(request.getRoomSize())
                .roomType(request.getRoomType())
                .petAllowedYn(request.getPetAllowedYn())
                .deposit(request.getDeposit())
                .rentPrice(request.getRentPrice())
                .manageFee(request.getManageFee())
                .rentType(request.getRentType())
                .roomSt(request.getRoomSt())
                .roomOptions(request.getRoomOptions())
                .roomCapacity(request.getRoomCapacity())
                .rentMin(request.getRentMin())
                .sunDirection(request.getSunDirection())
                .roomDesc(request.getRoomDesc())
                .build();

        Room saved = roomRepository.save(room);
        ownershipAccess.registerNewRoom(saved.getRoomId(), building.getBuildingId());

        if (request.getFiles() != null && !request.getFiles().isEmpty()) {
            fileService.uploadFiles(FileUploadRequest.builder()
                    .fileParentType(FileRefType.ROOM.dbValue())
                    .fileParentId(saved.getRoomId())
                    .files(request.getFiles())
                    .build());
        }

        List<FileResponse> files =
                fileService.getActiveFiles(FileRefType.ROOM.dbValue(), saved.getRoomId());

        return ownerResponse(saved, files);
    }

    @Override
    public RoomDetailResponse updateRoom(Integer roomId, RoomUpdateRequest request) {
        ownershipAccess.requireCanManageRoom(roomId);
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        if (room.isDeleted()) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }

        if (request.getBuildingId() != null || (request.getBuildingNm() != null && !request.getBuildingNm().isBlank())) {
            Building target = resolveBuilding(request.getBuildingId(), request.getBuildingNm());
            if (!target.getBuildingId().equals(room.getBuilding().getBuildingId())) {
                ownershipAccess.requireCanManageBuilding(room.getBuilding().getBuildingId());
                ownershipAccess.requireCanManageBuilding(target.getBuildingId());
                room.setBuilding(target);
            }
        }

        if (request.getRoomNo() != null)       room.setRoomNo(request.getRoomNo());
        if (request.getFloor() != null)         room.setFloor(request.getFloor());
        if (request.getRoomSize() != null)      room.setRoomSize(request.getRoomSize());
        if (request.getRoomType() != null)      room.setRoomType(request.getRoomType());
        if (request.getPetAllowedYn() != null)  room.setPetAllowedYn(request.getPetAllowedYn());

        if (request.getDeposit() != null)       room.setDeposit(request.getDeposit());
        if (request.getRentPrice() != null)     room.setRentPrice(request.getRentPrice());
        if (request.getManageFee() != null)     room.setManageFee(request.getManageFee());

        if (request.getRentType() != null)      room.setRentType(request.getRentType());
        if (request.getRoomSt() != null)        room.setRoomSt(request.getRoomSt());

        if (request.getRoomOptions() != null)   room.setRoomOptions(request.getRoomOptions());
        if (request.getRoomCapacity() != null)  room.setRoomCapacity(request.getRoomCapacity());
        if (request.getRentMin() != null)       room.setRentMin(request.getRentMin());

        if (request.getSunDirection() != null)  room.setSunDirection(request.getSunDirection());
        if (request.getRoomDesc() != null)      room.setRoomDesc(request.getRoomDesc());

        if (request.getDeleteFileIds() != null && !request.getDeleteFileIds().isEmpty()) {
            fileService.softDeleteFilesByParent(
                    FileRefType.ROOM.dbValue(),
                    roomId,
                    request.getDeleteFileIds()
            );
        }

        if (request.getFiles() != null && !request.getFiles().isEmpty()) {
            fileService.uploadFiles(FileUploadRequest.builder()
                    .fileParentType(FileRefType.ROOM.dbValue())
                    .fileParentId(roomId)
                    .files(request.getFiles())
                    .build());
        }

        if (request.getFileOrder() != null && !request.getFileOrder().isEmpty()) {
            fileService.updateFileOrder(FileRefType.ROOM.dbValue(), roomId, request.getFileOrder());
        }

        List<FileResponse> files =
                fileService.getActiveFiles(FileRefType.ROOM.dbValue(), roomId);

        return ownerResponse(room, files);
    }

    @Override
    public void changeRoomStatus(Integer roomId, RoomStatus roomStatus) {
        ownershipAccess.requireCanManageRoom(roomId);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        if (room.isDeleted()) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }

        room.setRoomSt(roomStatus);
    }

    @Override
    public void deleteRoom(Integer roomId) {
        ownershipAccess.requireCanManageRoom(roomId);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        if (room.isDeleted()) {
            return;
        }

        room.softDelete();
        roomRepository.save(room);
    }

    private Building resolveBuilding(Integer buildingId, String buildingNm) {
        if (buildingId != null) {
            Building building = buildingRepository.findByBuildingIdAndDeleteYn(buildingId, "N")
                    .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));
            if (buildingNm != null && !buildingNm.isBlank() && !building.getBuildingNm().equals(buildingNm)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST);
            }
            return building;
        }
        if (buildingNm == null || buildingNm.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST);
        List<Building> buildings = buildingRepository.findByBuildingNmAndDeleteYn(buildingNm, "N");
        if (buildings == null || buildings.isEmpty()) throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        if (buildings.size() > 1) throw new BusinessException(ErrorCode.BAD_REQUEST);
        return buildings.get(0);
    }
}
