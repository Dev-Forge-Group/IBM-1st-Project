package org.myweb.uniplace.domain.property.application;

import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.ownership.application.publicapi.OwnershipAccess;
import org.myweb.uniplace.domain.property.api.dto.request.SpaceSearchRequest;
import org.myweb.uniplace.domain.property.api.dto.response.PropertyDashboardResponse;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.domain.enums.RoomStatus;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.PropertySpecifications;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.property.repository.SpaceRepository;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PropertyDashboardService {
    private final RoomRepository roomRepository;
    private final SpaceRepository spaceRepository;
    private final BuildingRepository buildingRepository;
    private final OwnershipAccess ownershipAccess;

    public PropertyDashboardResponse getDashboard(Integer buildingId) {
        ownershipAccess.requireOwner();
        Set<Integer> manageableBuildings = ownershipAccess.manageableBuildingIds();
        List<Room> ownedRooms = roomRepository.findAllById(ownershipAccess.manageableRoomIds()).stream()
                .filter(room -> !room.isDeleted() && room.getBuilding() != null && !room.getBuilding().isDeleted())
                .toList();
        Map<Integer, Building> selectableBuildings = new LinkedHashMap<>();
        buildingRepository.findAllById(manageableBuildings).stream().filter(building -> !building.isDeleted())
                .forEach(building -> selectableBuildings.put(building.getBuildingId(), building));
        ownedRooms.forEach(room -> selectableBuildings.put(room.getBuilding().getBuildingId(), room.getBuilding()));
        if (buildingId != null && !selectableBuildings.containsKey(buildingId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        List<Room> rooms = ownedRooms.stream()
                .filter(room -> buildingId == null || buildingId.equals(room.getBuilding().getBuildingId())).toList();
        Map<RoomStatus, Integer> counts = new EnumMap<>(RoomStatus.class);
        for (RoomStatus status : RoomStatus.values()) counts.put(status, 0);
        rooms.forEach(room -> { if (room.getRoomSt() != null) counts.merge(room.getRoomSt(), 1, Integer::sum); });

        Set<Integer> spaceBuildings = manageableBuildings.stream()
                .filter(id -> buildingId == null || buildingId.equals(id)).collect(Collectors.toSet());
        int spaceCount = Math.toIntExact(spaceRepository.count(
                PropertySpecifications.spaces(spaceBuildings, new SpaceSearchRequest())));
        int total = rooms.size();
        double occupancy = total == 0 ? 0 : counts.get(RoomStatus.contracted) * 100.0 / total;
        double vacancy = 100 - occupancy;
        List<PropertyDashboardResponse.BuildingSimple> buildings = selectableBuildings.values().stream()
                .sorted(Comparator.comparing(Building::getBuildingId))
                .map(building -> PropertyDashboardResponse.BuildingSimple.builder()
                        .id(building.getBuildingId()).name(building.getBuildingNm()).build()).toList();
        return PropertyDashboardResponse.builder()
                .summary(PropertyDashboardResponse.Summary.builder().totalRooms(total).totalSpaces(spaceCount).build())
                .roomStats(PropertyDashboardResponse.RoomStats.builder().available(counts.get(RoomStatus.available))
                        .reserved(counts.get(RoomStatus.reserved)).contracted(counts.get(RoomStatus.contracted))
                        .repair(counts.get(RoomStatus.repair)).cleaning(counts.get(RoomStatus.cleaning)).build())
                .spaceCount(spaceCount).rate(PropertyDashboardResponse.Rate.builder()
                        .occupancy(occupancy).vacancy(vacancy).build()).buildings(buildings).build();
    }
}
