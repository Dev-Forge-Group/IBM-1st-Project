// 경로: org/myweb/uniplace/domain/property/application/BuildingServiceImpl.java
package org.myweb.uniplace.domain.property.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.myweb.uniplace.domain.file.api.dto.request.FileUploadRequest;
import org.myweb.uniplace.domain.file.api.dto.response.FileResponse;
import org.myweb.uniplace.domain.file.application.FileService;
import org.myweb.uniplace.domain.file.domain.enums.FileRefType;
import org.myweb.uniplace.domain.property.api.dto.request.BuildingCreateRequest;
import org.myweb.uniplace.domain.property.api.dto.request.BuildingUpdateRequest;
import org.myweb.uniplace.domain.property.api.dto.response.BuildingDetailResponse;
import org.myweb.uniplace.domain.property.api.dto.response.BuildingSummaryResponse;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.PropertySpecifications;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
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
public class BuildingServiceImpl implements BuildingService {

    private final BuildingRepository buildingRepository;
    private final RoomRepository roomRepository;
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
    public BuildingDetailResponse getBuilding(Integer buildingId) {
        ownershipAccess.requirePublicBuilding(buildingId);
        Building building = buildingRepository.findByBuildingIdAndDeleteYn(buildingId, "N")
                .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));
        List<FileResponse> files =
                fileService.getActiveFiles(FileRefType.BUILDING.dbValue(), buildingId);
        return BuildingDetailResponse.fromPublicEntity(building, files);
    }

    @Override
    @Transactional(readOnly = true)
    public BuildingDetailResponse getBuildingForAdmin(Integer buildingId) {
        ownershipAccess.requireCanManageBuilding(buildingId);
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));
        List<FileResponse> files =
                fileService.getActiveFiles(FileRefType.BUILDING.dbValue(), buildingId);
        return ownerResponse(building, files);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BuildingSummaryResponse> searchPage(Pageable pageable) {
        return mapBuildings(buildingRepository.findAll(PropertySpecifications.buildings(
                ownershipAccess.publicBuildingIds(), null, null, null), pageable), false);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BuildingSummaryResponse> searchOwnedPage(Pageable pageable) {
        ownershipAccess.requireOwner();
        return mapBuildings(buildingRepository.findAll(PropertySpecifications.buildings(
                ownershipAccess.manageableBuildingIds(), null, null, null), pageable), true);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BuildingSummaryResponse> searchPageWithFilters(String existElv, Integer minParking,
            String keyword, Pageable pageable) {
        return mapBuildings(buildingRepository.findAll(PropertySpecifications.buildings(
                ownershipAccess.publicBuildingIds(), existElv, minParking, keyword), pageable), false);
    }

    private Page<BuildingSummaryResponse> mapBuildings(Page<Building> page, boolean owner) {
        List<Integer> ids = page.getContent().stream().map(Building::getBuildingId).toList();
        Map<Integer, List<FileResponse>> filesMap = fileService.getActiveFilesMap(FileRefType.BUILDING.dbValue(), ids);
        return page.map(building -> {
            FileResponse image = filesMap.getOrDefault(building.getBuildingId(), List.of()).stream()
                    .filter(file -> file != null && isImageExt(file.getFileType())).findFirst().orElse(null);
            BuildingSummaryResponse response = BuildingSummaryResponse.fromEntity(building,
                    image == null ? null : image.getFileId(), image == null ? null : image.getViewUrl());
            return owner ? response.withOwnershipStatus(ownershipAccess.buildingStatus(building.getBuildingId()))
                    .withPublicVisibility(ownershipAccess.isPublicBuilding(building.getBuildingId())) : response;
        });
    }

    private BuildingDetailResponse ownerResponse(Building building, List<FileResponse> files) {
        return BuildingDetailResponse.fromEntity(building, files)
                .withOwnershipStatus(ownershipAccess.buildingStatus(building.getBuildingId()))
                .withPublicVisibility(ownershipAccess.isPublicBuilding(building.getBuildingId()));
    }

    @Override
    public BuildingDetailResponse createBuilding(BuildingCreateRequest request) {
        ownershipAccess.requireOwner();
        List<Building> existing = buildingRepository.findByBuildingNmAndDeleteYn(request.getBuildingNm(), "N");
        if (existing != null && !existing.isEmpty()) {
            throw new BusinessException(ErrorCode.BUILDING_DUPLICATE);
        }

        Building building = Building.builder()
                .buildingNm(request.getBuildingNm())
                .buildingAddr(request.getBuildingAddr())
                .buildingDesc(request.getBuildingDesc())
                .landCategory(request.getLandCategory())
                .buildSize(request.getBuildSize())
                .buildingUsage(request.getBuildingUsage())
                .existElv(request.getExistElv())
                .parkingCapacity(request.getParkingCapacity())
                .buildingLessorNm(request.getBuildingLessorNm())
                .buildingLessorTel(request.getBuildingLessorTel())
                .buildingLessorAddr(request.getBuildingLessorAddr())
                .buildingLessorRrn(request.getBuildingLessorRrn())
                .build();

        Building saved = buildingRepository.save(building);
        ownershipAccess.registerNewBuilding(saved.getBuildingId());

        if (request.getFiles() != null && !request.getFiles().isEmpty()) {
            fileService.uploadFiles(FileUploadRequest.builder()
                    .fileParentType(FileRefType.BUILDING.dbValue())
                    .fileParentId(saved.getBuildingId())
                    .files(request.getFiles())
                    .build());
        }

        List<FileResponse> files =
                fileService.getActiveFiles(FileRefType.BUILDING.dbValue(), saved.getBuildingId());
        return ownerResponse(saved, files);
    }

    @Override
    public BuildingDetailResponse updateBuilding(Integer buildingId, BuildingUpdateRequest request) {
        ownershipAccess.requireCanManageBuilding(buildingId);
        Building building = buildingRepository.findByBuildingIdAndDeleteYn(buildingId, "N")
                .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));

        if (request == null) {
            List<FileResponse> files =
                    fileService.getActiveFiles(FileRefType.BUILDING.dbValue(), buildingId);
            return ownerResponse(building, files);
        }

        String newNm = request.getBuildingNm();
        if (newNm != null && !newNm.isBlank() && !newNm.equals(building.getBuildingNm())) {
            List<Building> existing = buildingRepository.findByBuildingNmAndDeleteYn(newNm, "N");
            if (existing != null && !existing.isEmpty()) {
                throw new BusinessException(ErrorCode.BUILDING_DUPLICATE);
            }
        }

        building.update(
                request.getBuildingNm(),
                request.getBuildingAddr(),
                request.getBuildingDesc(),
                request.getLandCategory(),
                request.getBuildSize(),
                request.getBuildingUsage(),
                request.getExistElv(),
                request.getParkingCapacity(),
                request.getBuildingLessorNm(),
                request.getBuildingLessorTel(),
                request.getBuildingLessorAddr(),
                request.getBuildingLessorRrn()
        );

        if (request.getDeleteFileIds() != null && !request.getDeleteFileIds().isEmpty()) {
            fileService.softDeleteFilesByParent(
                    FileRefType.BUILDING.dbValue(),
                    buildingId,
                    request.getDeleteFileIds()
            );
        }

        if (request.getFiles() != null && !request.getFiles().isEmpty()) {
            fileService.uploadFiles(FileUploadRequest.builder()
                    .fileParentType(FileRefType.BUILDING.dbValue())
                    .fileParentId(buildingId)
                    .files(request.getFiles())
                    .build());
        }

        if (request.getFileOrder() != null && !request.getFileOrder().isEmpty()) {
            fileService.updateFileOrder(FileRefType.BUILDING.dbValue(), buildingId, request.getFileOrder());
        }

        Building saved = buildingRepository.save(building);
        List<FileResponse> files =
                fileService.getActiveFiles(FileRefType.BUILDING.dbValue(), buildingId);
        return ownerResponse(saved, files);
    }

    @Override
    public void deleteBuilding(Integer buildingId) {
        ownershipAccess.requireCanManageBuilding(buildingId);
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));
        if (building.isDeleted()) return;
        List<Room> rooms = roomRepository.findAllByBuilding_BuildingIdAndDeleteYn(buildingId, "N");
        // Check every unit before changing anything, including a separately owned unit.
        rooms.forEach(room -> ownershipAccess.requireCanManageRoom(room.getRoomId()));
        rooms.forEach(Room::softDelete);
        building.softDelete();
        buildingRepository.save(building);
    }
}
