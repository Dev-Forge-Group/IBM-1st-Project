package org.myweb.uniplace.domain.ownership.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.ownership.application.publicapi.AccountAccess;
import org.myweb.uniplace.domain.ownership.application.publicapi.OwnershipAccess;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.security.AuthUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Current database authorization; scalar queries join the caller's REQUIRED transaction. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnershipAccessService implements OwnershipAccess {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ELIGIBLE_OWNER = """
            u.account_kind='MEMBER' AND u.user_st='active' AND UPPER(COALESCE(u.delete_yn,'N'))<>'Y'
            AND EXISTS (SELECT 1 FROM owner_profiles op WHERE op.user_id=u.user_id AND op.status='APPROVED')
            AND EXISTS (SELECT 1 FROM account_role_grants ag WHERE ag.user_id=u.user_id
                         AND ag.role_code='OWNER' AND ag.grant_state='ACTIVE')
            """;
    private static final String LIVE_BUILDING = "UPPER(COALESCE(b.delete_yn,'N'))<>'Y'";
    private static final String LIVE_ROOM = "UPPER(COALESCE(r.delete_yn,'N'))<>'Y'";
    private static final String MANAGED_BUILDINGS = """
            SELECT b.building_id
            FROM building_memberships membership
            JOIN building b ON b.building_id=membership.building_id
            WHERE membership.user_id=? AND %s
              AND (
                membership.state='ACTIVE'
                OR (
                  membership.state='PENDING' AND membership.origin='CREATED'
                  AND NOT EXISTS (
                    SELECT 1 FROM building_memberships active_link
                    WHERE active_link.building_id=b.building_id AND active_link.state='ACTIVE'
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM ownership_review_events reviewed
                    WHERE reviewed.review_type='BUILDING' AND reviewed.resource_id=b.building_id
                      AND reviewed.target_user_id=membership.user_id
                  )
                )
              )
            """.formatted(LIVE_BUILDING);

    // A room with any active unit link no longer inherits management from its building.
    private static final String MANAGED_ROOMS = """
            SELECT r.room_id
            FROM room_memberships unit
            JOIN rooms r ON r.room_id=unit.room_id
            JOIN building b ON b.building_id=r.building_id
            WHERE unit.user_id=? AND unit.state='ACTIVE' AND %s AND %s
            UNION ALL
            SELECT r.room_id
            FROM (%s) managed_building
            JOIN rooms r ON r.building_id=managed_building.building_id
            WHERE %s AND NOT EXISTS (
              SELECT 1 FROM room_memberships active_unit
              WHERE active_unit.room_id=r.room_id AND active_unit.state='ACTIVE'
            )
            """.formatted(LIVE_ROOM, LIVE_BUILDING, MANAGED_BUILDINGS, LIVE_ROOM);

    private static final String MANAGED_CONTRACTS = """
            SELECT c.contract_id
            FROM contract_owner_parties party
            JOIN contract c ON c.contract_id=party.contract_id
            JOIN (%s) managed_room ON managed_room.room_id=c.room_id
            WHERE party.user_id=? AND c.user_id<>party.user_id
            """.formatted(MANAGED_ROOMS);

    private final JdbcTemplate jdbc;

    @Override
    public AccountAccess effectiveAccess(String userId) {
        List<AccountRow> accounts = jdbc.query("SELECT account_kind,user_role,user_st,delete_yn FROM users WHERE user_id=?",
                (rs, row) -> new AccountRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), userId);
        if (accounts.isEmpty()) return new AccountAccess("MEMBER", Set.of(), "NONE");
        AccountRow account = accounts.get(0);
        String ownerStatus = ownerStatus(userId);
        if (!"active".equalsIgnoreCase(account.status()) || "Y".equalsIgnoreCase(account.deleted())) {
            return new AccountAccess(account.kind(), Set.of(), ownerStatus);
        }
        if ("STAFF".equals(account.kind())) {
            return new AccountAccess("STAFF", "admin".equals(account.role()) ? Set.of("ADMIN") : Set.of(), "NONE");
        }
        LinkedHashSet<String> roles = new LinkedHashSet<>(Set.of("CUSTOMER", "USER"));
        if (hasActiveOwner(userId)) roles.add("OWNER");
        LocalDate today = LocalDate.now(KST);
        if (exists("""
                SELECT COUNT(*) FROM contract c JOIN rooms r ON r.room_id=c.room_id
                JOIN building b ON b.building_id=r.building_id
                JOIN residents resident ON resident.contract_id=c.contract_id AND resident.user_id=c.user_id
                                         AND resident.building_id=r.building_id
                WHERE c.user_id=? AND c.contract_st='active' AND c.contract_start<=? AND c.contract_end>=?
                  AND UPPER(COALESCE(r.delete_yn,'N'))<>'Y'
                  AND UPPER(COALESCE(b.delete_yn,'N'))<>'Y'
                """, userId, today, today)) roles.add("TENANT");
        return new AccountAccess("MEMBER", roles, ownerStatus);
    }

    @Override
    public boolean hasActiveOwner(String userId) {
        return userId != null && exists("SELECT COUNT(*) FROM users u WHERE u.user_id=? AND " + ELIGIBLE_OWNER, userId);
    }

    @Override
    public String requireMember() {
        String userId = currentIdentity();
        AccountAccess access = effectiveAccess(userId);
        if (!"MEMBER".equals(access.accountKind()) || !access.roles().contains("CUSTOMER")) forbidden();
        return userId;
    }

    @Override
    public String requireOwner() {
        String userId = requireMember();
        if (!hasActiveOwner(userId)) forbidden();
        return userId;
    }

    public String requireStaff() {
        String userId = currentIdentity();
        AccountAccess access = effectiveAccess(userId);
        if (!"STAFF".equals(access.accountKind()) || !access.roles().contains("ADMIN")) forbidden();
        return userId;
    }

    @Override
    public void requireCanManageBuilding(Integer buildingId) {
        if (!canManageBuilding(requireOwner(), buildingId)) forbidden();
    }

    @Override
    public void requireCanManageRoom(Integer roomId) {
        if (!canManageRoom(requireOwner(), roomId)) forbidden();
    }

    @Override
    public void requireCanManageContract(Integer contractId) {
        if (!canManageContract(requireOwner(), contractId)) forbidden();
    }

    @Override
    public boolean canManageBuilding(String userId, Integer buildingId) {
        return buildingId != null && hasActiveOwner(userId)
                && exists("SELECT COUNT(*) FROM (" + MANAGED_BUILDINGS + ") managed WHERE building_id=?",
                        userId, buildingId);
    }

    @Override
    public boolean canManageRoom(String userId, Integer roomId) {
        return roomId != null && hasActiveOwner(userId)
                && exists("SELECT COUNT(*) FROM (" + MANAGED_ROOMS + ") managed WHERE room_id=?",
                        userId, userId, roomId);
    }

    @Override
    public boolean canReadContract(String userId, Integer contractId) {
        if (contractId == null || userId == null) return false;
        AccountAccess access = effectiveAccess(userId);
        if (!"MEMBER".equals(access.accountKind()) || !access.roles().contains("CUSTOMER")) return false;
        if (exists("SELECT COUNT(*) FROM contract WHERE contract_id=? AND user_id=?", contractId, userId)) return true;
        return access.roles().contains("OWNER")
                && exists("SELECT COUNT(*) FROM contract_owner_parties WHERE contract_id=? AND user_id=?", contractId, userId);
    }

    @Override
    public boolean canManageContract(String userId, Integer contractId) {
        return contractId != null && hasActiveOwner(userId)
                && exists("SELECT COUNT(*) FROM (" + MANAGED_CONTRACTS + ") managed WHERE contract_id=?",
                        userId, userId, userId, contractId);
    }

    @Override
    public Set<Integer> manageableBuildingIds() {
        String userId = requireOwner();
        return Set.copyOf(ids(MANAGED_BUILDINGS, userId));
    }

    @Override
    public Set<Integer> manageableRoomIds() {
        String userId = requireOwner();
        return Set.copyOf(ids(MANAGED_ROOMS, userId, userId));
    }

    @Override
    public Set<Integer> manageableContractIds() {
        String userId = requireOwner();
        return Set.copyOf(ids(MANAGED_CONTRACTS, userId, userId, userId));
    }

    @Override
    public Set<Integer> readableContractIds() {
        String userId = requireOwner();
        return Set.copyOf(ids("SELECT contract_id FROM contract_owner_parties WHERE user_id=?", userId));
    }

    @Override
    @Transactional
    public void registerNewBuilding(Integer buildingId) {
        String userId = requireOwner();
        if (buildingId == null || !exists("SELECT COUNT(*) FROM building b WHERE b.building_id=? AND " + LIVE_BUILDING, buildingId)
                || exists("SELECT COUNT(*) FROM building_memberships WHERE building_id=?", buildingId)) badRequest();
        jdbc.update("INSERT INTO building_memberships(user_id,building_id,state,origin) VALUES(?,?,'PENDING','CREATED')", userId, buildingId);
    }

    @Override
    @Transactional
    public void registerNewRoom(Integer roomId, Integer buildingId) {
        requireCanManageBuilding(buildingId);
        if (roomId == null || !exists("SELECT COUNT(*) FROM rooms r JOIN building b ON b.building_id=r.building_id"
                + " WHERE r.room_id=? AND r.building_id=? AND " + LIVE_ROOM + " AND " + LIVE_BUILDING, roomId, buildingId)) badRequest();
        // A new room inherits its building. Only a separate reviewed unit claim creates an override.
    }

    @Override
    public Set<Integer> hiddenBuildingIds() {
        return Set.copyOf(ids("SELECT building_id FROM building WHERE building_id NOT IN (SELECT building_id FROM ownership_public_building_ids)"));
    }

    @Override
    public Set<Integer> hiddenRoomIds() {
        return Set.copyOf(ids("SELECT room_id FROM rooms WHERE room_id NOT IN (SELECT room_id FROM ownership_public_room_ids)"));
    }

    @Override
    public Set<Integer> publicBuildingIds() {
        return Set.copyOf(ids("SELECT building_id FROM ownership_public_building_ids"));
    }

    @Override
    public Set<Integer> publicRoomIds() {
        return Set.copyOf(ids("SELECT room_id FROM ownership_public_room_ids"));
    }

    @Override
    public boolean isPublicBuilding(Integer buildingId) {
        return buildingId != null && exists("SELECT COUNT(*) FROM ownership_public_building_ids WHERE building_id=?", buildingId);
    }

    @Override
    public boolean isPublicRoom(Integer roomId) {
        return roomId != null && exists("SELECT COUNT(*) FROM ownership_public_room_ids WHERE room_id=?", roomId);
    }

    @Override
    public void requirePublicBuilding(Integer buildingId) {
        if (!isPublicBuilding(buildingId)) throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
    }

    @Override
    public void requirePublicRoom(Integer roomId) {
        if (!isPublicRoom(roomId)) throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
    }

    @Override
    public String buildingStatus(Integer buildingId) {
        List<String> states = jdbc.queryForList("SELECT state FROM building_memberships WHERE building_id=? ORDER BY CASE state WHEN 'ACTIVE' THEN 0 WHEN 'PENDING' THEN 1 ELSE 2 END,membership_id DESC",
                String.class, buildingId);
        return states.isEmpty() ? "UNASSIGNED" : states.get(0);
    }

    @Override
    public String roomStatus(Integer roomId) {
        List<String> states = jdbc.queryForList("SELECT state FROM room_memberships WHERE room_id=? AND state='ACTIVE'", String.class, roomId);
        if (!states.isEmpty()) return "ACTIVE";
        List<Integer> parents = jdbc.queryForList("SELECT building_id FROM rooms WHERE room_id=?", Integer.class, roomId);
        return parents.isEmpty() ? "UNASSIGNED" : "INHERITED";
    }

    @Override
    public Set<String> eligibleOwnerIdsForBuilding(Integer buildingId) {
        if (buildingId == null) return Set.of();
        return Set.copyOf(jdbc.queryForList("SELECT bm.user_id FROM building_memberships bm JOIN users u ON u.user_id=bm.user_id"
                + " JOIN building b ON b.building_id=bm.building_id WHERE bm.building_id=? AND bm.state='ACTIVE' AND "
                + LIVE_BUILDING + " AND " + ELIGIBLE_OWNER, String.class, buildingId));
    }

    @Override
    public Set<String> eligibleOwnerIdsForRoom(Integer roomId) {
        if (!isPublicRoom(roomId)) return Set.of();
        if (exists("SELECT COUNT(*) FROM room_memberships WHERE room_id=? AND state='ACTIVE'", roomId)) {
            return Set.copyOf(jdbc.queryForList("SELECT rm.user_id FROM room_memberships rm JOIN users u ON u.user_id=rm.user_id"
                    + " WHERE rm.room_id=? AND rm.state='ACTIVE' AND " + ELIGIBLE_OWNER, String.class, roomId));
        }
        List<Integer> parents = jdbc.queryForList("SELECT building_id FROM rooms WHERE room_id=?", Integer.class, roomId);
        return parents.isEmpty() ? Set.of() : eligibleOwnerIdsForBuilding(parents.get(0));
    }

    @Override
    public void requireVerifiedRentalRoom(Integer roomId) {
        requirePublicRoom(roomId);
        if (eligibleOwnerIdsForRoom(roomId).isEmpty()) badRequest();
    }

    @Override
    public void requireVerifiedRentalBuilding(Integer buildingId) {
        requirePublicBuilding(buildingId);
        if (eligibleOwnerIdsForBuilding(buildingId).isEmpty()) badRequest();
    }

    @Override
    @Transactional
    public void recordContractOwners(Integer contractId, Integer roomId) {
        String consumer = requireMember();
        if (contractId == null || !exists("SELECT COUNT(*) FROM contract WHERE contract_id=? AND room_id=? AND user_id=?", contractId, roomId, consumer)) badRequest();
        if (exists("SELECT COUNT(*) FROM contract_owner_parties WHERE contract_id=?", contractId)) return;
        Set<String> owners = eligibleOwnerIdsForRoom(roomId);
        if (owners.isEmpty()) badRequest();
        if (owners.contains(consumer)) forbidden();
        for (String ownerId : owners) {
            jdbc.update("INSERT INTO contract_owner_parties(contract_id,user_id,grant_reason) VALUES(?,?,'Verified rental owner at contract creation')", contractId, ownerId);
        }
    }

    @Override
    public Set<String> contractOwnerIds(Integer contractId) {
        if (contractId == null) return Set.of();
        return Set.copyOf(jdbc.queryForList("SELECT user_id FROM contract_owner_parties WHERE contract_id=?", String.class, contractId));
    }

    private String ownerStatus(String userId) {
        List<String> result = jdbc.queryForList("SELECT status FROM owner_profiles WHERE user_id=?", String.class, userId);
        return result.isEmpty() ? "NONE" : result.get(0);
    }

    private String currentIdentity() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof AuthUser user) || user.getUserId() == null || user.getUserId().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if ("guest".equalsIgnoreCase(user.getRole()) || "GUEST".equals(user.getAccountKind()) || user.hasRole("GUEST")) forbidden();
        return user.getUserId();
    }

    private boolean exists(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value != null && value > 0;
    }

    private List<Integer> ids(String sql, Object... args) { return jdbc.queryForList(sql, Integer.class, args); }
    private static void forbidden() { throw new BusinessException(ErrorCode.FORBIDDEN); }
    private static void badRequest() { throw new BusinessException(ErrorCode.BAD_REQUEST); }
    private record AccountRow(String kind, String role, String status, String deleted) {}
}
