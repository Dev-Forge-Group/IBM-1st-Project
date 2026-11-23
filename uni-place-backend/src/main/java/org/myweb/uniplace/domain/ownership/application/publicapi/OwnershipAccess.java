package org.myweb.uniplace.domain.ownership.application.publicapi;

import java.util.Set;

/** Scalar-only boundary for account capabilities and property-specific authorization. */
public interface OwnershipAccess {
    AccountAccess effectiveAccess(String userId);
    boolean hasActiveOwner(String userId);
    String requireMember();
    String requireOwner();
    void requireCanManageBuilding(Integer buildingId);
    void requireCanManageRoom(Integer roomId);
    void requireCanManageContract(Integer contractId);
    boolean canManageBuilding(String userId, Integer buildingId);
    boolean canManageRoom(String userId, Integer roomId);
    boolean canReadContract(String userId, Integer contractId);
    boolean canManageContract(String userId, Integer contractId);
    Set<Integer> manageableBuildingIds();
    Set<Integer> manageableRoomIds();
    Set<Integer> manageableContractIds();
    Set<Integer> readableContractIds();
    void registerNewBuilding(Integer buildingId);
    void registerNewRoom(Integer roomId, Integer buildingId);
    Set<Integer> hiddenBuildingIds();
    Set<Integer> hiddenRoomIds();
    Set<Integer> publicBuildingIds();
    Set<Integer> publicRoomIds();
    boolean isPublicBuilding(Integer buildingId);
    boolean isPublicRoom(Integer roomId);
    void requirePublicBuilding(Integer buildingId);
    void requirePublicRoom(Integer roomId);
    String buildingStatus(Integer buildingId);
    String roomStatus(Integer roomId);
    Set<String> eligibleOwnerIdsForRoom(Integer roomId);
    Set<String> eligibleOwnerIdsForBuilding(Integer buildingId);
    void requireVerifiedRentalRoom(Integer roomId);
    void requireVerifiedRentalBuilding(Integer buildingId);
    void recordContractOwners(Integer contractId, Integer roomId);
    Set<String> contractOwnerIds(Integer contractId);
}
