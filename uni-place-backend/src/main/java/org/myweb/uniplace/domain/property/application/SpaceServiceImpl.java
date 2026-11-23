package org.myweb.uniplace.domain.property.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.myweb.uniplace.domain.file.api.dto.request.FileUploadRequest;
import org.myweb.uniplace.domain.file.api.dto.response.FileResponse;
import org.myweb.uniplace.domain.file.application.FileService;
import org.myweb.uniplace.domain.file.domain.enums.FileRefType;
import org.myweb.uniplace.domain.ownership.application.publicapi.OwnershipAccess;
import org.myweb.uniplace.domain.property.api.dto.request.SpaceCreateRequest;
import org.myweb.uniplace.domain.property.api.dto.request.SpaceSearchRequest;
import org.myweb.uniplace.domain.property.api.dto.request.SpaceUpdateRequest;
import org.myweb.uniplace.domain.property.api.dto.response.SpaceDetailResponse;
import org.myweb.uniplace.domain.property.api.dto.response.SpaceResponse;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.CommonSpace;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.PropertySpecifications;
import org.myweb.uniplace.domain.property.repository.SpaceRepository;
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
public class SpaceServiceImpl implements SpaceService {
    private final SpaceRepository spaceRepository;
    private final BuildingRepository buildingRepository;
    private final FileService fileService;
    private final OwnershipAccess ownershipAccess;

    private static final Set<String> IMAGE_EXTS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp");

    @Override
    @Transactional(readOnly = true)
    public SpaceDetailResponse getSpace(Integer spaceId) {
        CommonSpace space = findSpace(spaceId);
        if (space.getBuilding().isDeleted() || !ownershipAccess.isPublicBuilding(space.getBuilding().getBuildingId())) {
            throw new BusinessException(ErrorCode.SPACE_NOT_FOUND);
        }
        return SpaceDetailResponse.fromEntity(space, activeFiles(spaceId));
    }

    @Override
    @Transactional(readOnly = true)
    public SpaceDetailResponse getSpaceForOwner(Integer spaceId) {
        ownershipAccess.requireOwner();
        CommonSpace space = findSpace(spaceId);
        ownershipAccess.requireCanManageBuilding(space.getBuilding().getBuildingId());
        return ownerDetail(space);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SpaceResponse> searchPage(SpaceSearchRequest request, Pageable pageable) {
        return page(spaceRepository.findAll(
                PropertySpecifications.spaces(ownershipAccess.publicBuildingIds(), request), pageable), false);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SpaceResponse> searchOwnedPage(SpaceSearchRequest request, Pageable pageable) {
        ownershipAccess.requireOwner();
        return page(spaceRepository.findAll(
                PropertySpecifications.spaces(ownershipAccess.manageableBuildingIds(), request), pageable), true);
    }

    private Page<SpaceResponse> page(Page<CommonSpace> spaces, boolean owned) {
        List<Integer> ids = spaces.getContent().stream().map(CommonSpace::getSpaceId).toList();
        Map<Integer, List<FileResponse>> fileMap = fileService.getActiveFilesMap(FileRefType.SPACE.dbValue(), ids);
        return spaces.map(space -> {
            FileResponse image = fileMap.getOrDefault(space.getSpaceId(), List.of()).stream()
                    .filter(file -> file != null && file.getFileType() != null
                            && IMAGE_EXTS.contains(file.getFileType().toLowerCase(java.util.Locale.ROOT)))
                    .findFirst().orElse(null);
            SpaceResponse response = SpaceResponse.fromEntity(space,
                    image == null ? null : image.getFileId(), image == null ? null : image.getViewUrl());
            Integer buildingId = space.getBuilding().getBuildingId();
            return owned ? response.withOwnershipStatus(ownershipAccess.buildingStatus(buildingId))
                    .withPublicVisibility(ownershipAccess.isPublicBuilding(buildingId)) : response;
        });
    }

    @Override
    public SpaceDetailResponse createSpace(SpaceCreateRequest request) {
        ownershipAccess.requireOwner();
        Building building = resolveBuilding(request.getBuildingId(), request.getBuildingNm());
        ownershipAccess.requireCanManageBuilding(building.getBuildingId());
        CommonSpace space = spaceRepository.save(CommonSpace.builder().building(building)
                .spaceNm(request.getSpaceNm()).spaceFloor(request.getSpaceFloor())
                .spaceCapacity(request.getSpaceCapacity()).spaceOptions(request.getSpaceOptions())
                .spaceDesc(request.getSpaceDesc()).build());
        upload(space.getSpaceId(), request.getFiles());
        return ownerDetail(space);
    }

    @Override
    public SpaceDetailResponse updateSpace(Integer spaceId, SpaceUpdateRequest request) {
        ownershipAccess.requireOwner();
        CommonSpace space = findSpace(spaceId);
        ownershipAccess.requireCanManageBuilding(space.getBuilding().getBuildingId());
        if (request == null) return ownerDetail(space);

        Building target = null;
        if (request.getBuildingId() != null || (request.getBuildingNm() != null && !request.getBuildingNm().isBlank())) {
            target = resolveBuilding(request.getBuildingId(), request.getBuildingNm());
            ownershipAccess.requireCanManageBuilding(target.getBuildingId());
        }
        // Both parent grants are checked before changing metadata or touching any file.
        if (target != null) space.setBuilding(target);
        if (request.getSpaceNm() != null && !request.getSpaceNm().isBlank()) space.setSpaceNm(request.getSpaceNm());
        if (request.getSpaceFloor() != null) space.setSpaceFloor(request.getSpaceFloor());
        if (request.getSpaceCapacity() != null) space.setSpaceCapacity(request.getSpaceCapacity());
        if (request.getSpaceOptions() != null) space.setSpaceOptions(request.getSpaceOptions());
        if (request.getSpaceDesc() != null) space.setSpaceDesc(request.getSpaceDesc());
        if (request.getDeleteFileIds() != null && !request.getDeleteFileIds().isEmpty()) {
            fileService.softDeleteFilesByParent(FileRefType.SPACE.dbValue(), spaceId, request.getDeleteFileIds());
        }
        upload(spaceId, request.getFiles());
        if (request.getFileOrder() != null && !request.getFileOrder().isEmpty()) {
            fileService.updateFileOrder(FileRefType.SPACE.dbValue(), spaceId, request.getFileOrder());
        }
        return ownerDetail(spaceRepository.save(space));
    }

    @Override
    public void deleteSpace(Integer spaceId) {
        ownershipAccess.requireOwner();
        CommonSpace space = findSpace(spaceId);
        ownershipAccess.requireCanManageBuilding(space.getBuilding().getBuildingId());
        spaceRepository.delete(space);
    }

    private CommonSpace findSpace(Integer id) {
        return spaceRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));
    }

    private List<FileResponse> activeFiles(Integer id) {
        return fileService.getActiveFiles(FileRefType.SPACE.dbValue(), id);
    }

    private SpaceDetailResponse ownerDetail(CommonSpace space) {
        Integer buildingId = space.getBuilding().getBuildingId();
        return SpaceDetailResponse.fromEntity(space, activeFiles(space.getSpaceId()))
                .withOwnershipStatus(ownershipAccess.buildingStatus(buildingId))
                .withPublicVisibility(ownershipAccess.isPublicBuilding(buildingId));
    }

    private void upload(Integer id, List<org.springframework.web.multipart.MultipartFile> files) {
        if (files != null && !files.isEmpty()) {
            fileService.uploadFiles(FileUploadRequest.builder().fileParentType(FileRefType.SPACE.dbValue())
                    .fileParentId(id).files(files).build());
        }
    }

    private Building resolveBuilding(Integer id, String name) {
        if (id != null) {
            Building building = buildingRepository.findByBuildingIdAndDeleteYn(id, "N")
                    .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));
            if (name != null && !name.isBlank() && !building.getBuildingNm().equals(name)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST);
            }
            return building;
        }
        if (name == null || name.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST);
        List<Building> buildings = buildingRepository.findByBuildingNmAndDeleteYn(name, "N");
        if (buildings == null || buildings.isEmpty()) throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        if (buildings.size() != 1) throw new BusinessException(ErrorCode.BAD_REQUEST);
        return buildings.get(0);
    }
}
