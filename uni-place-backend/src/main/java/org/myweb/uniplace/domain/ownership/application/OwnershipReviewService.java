package org.myweb.uniplace.domain.ownership.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.ownership.api.dto.AssetClaimResponse;
import org.myweb.uniplace.domain.ownership.api.dto.OwnerApplicationResponse;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account eligibility and property title are reviewed separately, against current database identities. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnershipReviewService {
    private static final Set<String> PROFILE_STATES = Set.of("PENDING", "APPROVED", "REJECTED");
    private static final Set<String> CLAIM_STATES = Set.of("PENDING", "ACTIVE", "REJECTED", "REVOKED");
    private static final String PROFILE_SELECT = """
            SELECT u.user_id,u.user_nm,u.user_email,COALESCE(p.status,'NONE') status,
                   p.application_note,p.review_note,p.reviewed_by,p.created_at,p.reviewed_at
            FROM users u LEFT JOIN owner_profiles p ON p.user_id=u.user_id
            """;

    private final JdbcTemplate jdbc;
    private final OwnershipAccessService access;

    public OwnerApplicationResponse myApplication() {
        return application(access.requireMember());
    }

    @Transactional
    public OwnerApplicationResponse apply(String applicationNote) {
        String userId = lockMember();
        String note = text(applicationNote, 2000);
        List<String> states = jdbc.queryForList("SELECT status FROM owner_profiles WHERE user_id=? FOR UPDATE", String.class, userId);
        if (states.isEmpty()) {
            jdbc.update("INSERT INTO owner_profiles(user_id,status,application_note) VALUES(?,'PENDING',?)", userId, note);
        } else if (!"APPROVED".equals(states.get(0))) {
            jdbc.update("""
                    UPDATE owner_profiles SET status='PENDING',application_note=?,review_note=NULL,
                           reviewed_by=NULL,reviewed_at=NULL WHERE user_id=?
                    """, note, userId);
        }
        // An already approved owner cannot accidentally revoke their own grant by submitting again.
        return application(userId);
    }

    public List<OwnerApplicationResponse> applications(String status) {
        access.requireStaff();
        String filter = filter(status, PROFILE_STATES);
        return jdbc.query(PROFILE_SELECT + " WHERE p.user_id IS NOT NULL"
                        + (filter == null ? "" : " AND p.status=?") + " ORDER BY p.created_at,u.user_id",
                OwnershipReviewService::applicationRow, filter == null ? new Object[0] : new Object[]{filter});
    }

    @Transactional
    public OwnerApplicationResponse reviewApplication(String userId, boolean approved, String reviewNote) {
        String reviewer = lockStaff();
        String note = text(reviewNote, 2000);
        requireTargetMember(userId, approved);
        List<String> profile = jdbc.queryForList("SELECT status FROM owner_profiles WHERE user_id=? FOR UPDATE", String.class, userId);
        if (profile.isEmpty()) badRequest();
        String state = approved ? "APPROVED" : "REJECTED";
        jdbc.update("""
                UPDATE owner_profiles SET status=?,review_note=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP(6)
                WHERE user_id=?
                """, state, note, reviewer, userId);
        jdbc.update("""
                INSERT INTO account_role_grants(user_id,role_code,grant_state,granted_by,grant_reason)
                VALUES(?,'OWNER',?,?,?)
                ON DUPLICATE KEY UPDATE grant_state=VALUES(grant_state),granted_by=VALUES(granted_by),grant_reason=VALUES(grant_reason)
                """, userId, approved ? "ACTIVE" : "REVOKED", reviewer, note);
        audit(reviewer, userId, "OWNER_PROFILE", null, state, note);
        return application(userId);
    }

    public List<AssetClaimResponse> myClaims() {
        return findClaims("m.user_id=?", access.requireMember());
    }

    public List<AssetClaimResponse> claims(String status) {
        access.requireStaff();
        String filter = filter(status, CLAIM_STATES);
        return filter == null ? findClaims("1=1") : findClaims("m.state=?", filter);
    }

    @Transactional
    public AssetClaimResponse claim(String resourceType, Integer resourceId, String proofReference) {
        String userId = lockOwner();
        Resource resource = resource(resourceType);
        positive(resourceId);
        String proof = text(proofReference, 1000);
        lockLiveAsset(resource, resourceId);
        List<Membership> existing = membershipByAsset(resource, userId, resourceId);
        // Knowing a private draft ID is not proof of title or permission to discover it.
        if (existing.isEmpty() && !(resource == Resource.BUILDING
                ? access.isPublicBuilding(resourceId) : access.isPublicRoom(resourceId))) {
            throw new BusinessException(resource.notFound);
        }
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO " + resource.memberships + "(user_id," + resource.idColumn
                    + ",state,origin,proof_reference) VALUES(?,?,'PENDING','CLAIMED',?)", userId, resourceId, proof);
        } else if (!"ACTIVE".equals(existing.get(0).state())) {
            // Preserve CREATED so proof submission cannot publish an unreviewed new draft as legacy data.
            jdbc.update("UPDATE " + resource.memberships + " SET state='PENDING',proof_reference=?,review_note=NULL,"
                    + "reviewed_by=NULL,reviewed_at=NULL WHERE membership_id=?", proof, existing.get(0).id());
        }
        return claimResponse(resource, membershipByAsset(resource, userId, resourceId).get(0).id());
    }

    @Transactional
    public AssetClaimResponse reviewClaim(String resourceType, Integer membershipId, boolean approved, String reviewNote) {
        String reviewer = lockStaff();
        Resource resource = resource(resourceType);
        positive(membershipId);
        String note = text(reviewNote, 2000);
        // Read only the key first; all decisions use the subsequently locked membership.
        List<Membership> keys = jdbc.query("SELECT membership_id,user_id," + resource.idColumn
                        + ",state,proof_reference FROM " + resource.memberships + " WHERE membership_id=?",
                (rs, row) -> membershipRow(rs), membershipId);
        if (keys.isEmpty()) badRequest();
        Membership key = keys.get(0);
        requireTargetMember(key.userId(), approved);
        if (approved) lockOwnerEligibility(key.userId());
        lockLiveAsset(resource, key.resourceId());
        Membership membership = jdbc.query("SELECT membership_id,user_id," + resource.idColumn
                        + ",state,proof_reference FROM " + resource.memberships + " WHERE membership_id=? FOR UPDATE",
                (rs, row) -> membershipRow(rs), membershipId).get(0);
        if (approved) {
            text(membership.proof(), 1000);
            // No implicit transfer or joint-title operation is hidden in a simple approve endpoint.
            List<Integer> competing = jdbc.queryForList("SELECT membership_id FROM " + resource.memberships
                    + " WHERE " + resource.idColumn + "=? AND state='ACTIVE' AND user_id<>? FOR UPDATE",
                    Integer.class, membership.resourceId(), membership.userId());
            if (!competing.isEmpty()) badRequest();
        }
        String state = approved ? "ACTIVE" : "ACTIVE".equals(membership.state()) ? "REVOKED" : "REJECTED";
        jdbc.update("UPDATE " + resource.memberships
                + " SET state=?,review_note=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP(6) WHERE membership_id=?",
                state, note, reviewer, membershipId);
        audit(reviewer, membership.userId(), resource.name(), membership.resourceId(), state, note);
        return claimResponse(resource, membershipId);
    }

    private OwnerApplicationResponse application(String userId) {
        List<OwnerApplicationResponse> result = jdbc.query(PROFILE_SELECT + " WHERE u.user_id=?",
                OwnershipReviewService::applicationRow, userId);
        if (result.isEmpty()) throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        return result.get(0);
    }

    private List<AssetClaimResponse> findClaims(String where, Object... args) {
        List<AssetClaimResponse> result = new ArrayList<>();
        for (Resource resource : Resource.values()) {
            result.addAll(jdbc.query(claimSelect(resource) + " WHERE " + where + " ORDER BY m.created_at,m.membership_id",
                    (rs, row) -> claimRow(rs, resource), args));
        }
        result.sort(java.util.Comparator.comparing(AssetClaimResponse::createdAt)
                .thenComparing(AssetClaimResponse::resourceType).thenComparing(AssetClaimResponse::membershipId));
        return List.copyOf(result);
    }

    private AssetClaimResponse claimResponse(Resource resource, Integer membershipId) {
        return jdbc.query(claimSelect(resource) + " WHERE m.membership_id=?",
                (rs, row) -> claimRow(rs, resource), membershipId).get(0);
    }

    private static String claimSelect(Resource resource) {
        String name = resource == Resource.BUILDING ? "a.building_nm" : "CONCAT(b.building_nm,' ',a.room_no,'호')";
        return "SELECT m.membership_id,m." + resource.idColumn + " resource_id," + name
                + " resource_name,m.user_id,u.user_nm,m.state,m.origin,m.proof_reference,m.review_note,m.reviewed_by,m.created_at,m.reviewed_at"
                + " FROM " + resource.memberships + " m JOIN users u ON u.user_id=m.user_id JOIN "
                + resource.table + " a ON a." + resource.idColumn + "=m." + resource.idColumn
                + (resource == Resource.ROOM ? " JOIN building b ON b.building_id=a.building_id" : "");
    }

    private List<Membership> membershipByAsset(Resource resource, String userId, Integer resourceId) {
        return jdbc.query("SELECT membership_id,user_id," + resource.idColumn + ",state,proof_reference FROM "
                        + resource.memberships + " WHERE user_id=? AND " + resource.idColumn + "=? FOR UPDATE",
                (rs, row) -> membershipRow(rs), userId, resourceId);
    }

    private String lockMember() {
        String userId = access.requireMember();
        requireTargetMember(userId, true);
        return userId;
    }

    private String lockOwner() {
        String userId = lockMember();
        lockOwnerEligibility(userId);
        return userId;
    }

    private String lockStaff() {
        String userId = access.requireStaff();
        List<Boolean> accounts = jdbc.query("SELECT account_kind,user_role,user_st,delete_yn FROM users WHERE user_id=? FOR UPDATE",
                (rs, row) -> "STAFF".equals(rs.getString(1)) && "admin".equals(rs.getString(2))
                        && "active".equals(rs.getString(3)) && !"Y".equalsIgnoreCase(rs.getString(4)), userId);
        if (accounts.isEmpty() || !accounts.get(0)) forbidden();
        return userId;
    }

    private void requireTargetMember(String userId, boolean activeRequired) {
        List<Boolean> accounts = jdbc.query("SELECT account_kind,user_st,delete_yn FROM users WHERE user_id=? FOR UPDATE",
                (rs, row) -> "MEMBER".equals(rs.getString(1)) && (!activeRequired
                        || ("active".equals(rs.getString(2)) && !"Y".equalsIgnoreCase(rs.getString(3)))), userId);
        if (accounts.isEmpty()) throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        if (!accounts.get(0)) forbidden();
    }

    private void lockOwnerEligibility(String userId) {
        List<String> profiles = jdbc.queryForList("SELECT status FROM owner_profiles WHERE user_id=? FOR UPDATE", String.class, userId);
        List<String> grants = jdbc.queryForList("SELECT grant_state FROM account_role_grants WHERE user_id=? AND role_code='OWNER' FOR UPDATE", String.class, userId);
        if (profiles.isEmpty() || !"APPROVED".equals(profiles.get(0)) || grants.isEmpty() || !"ACTIVE".equals(grants.get(0))) forbidden();
    }

    private void lockLiveAsset(Resource resource, Integer id) {
        // Every approval on the same asset takes this lock before examining competing memberships.
        List<Integer> live = jdbc.queryForList("SELECT " + resource.idColumn + " FROM " + resource.table
                + " WHERE " + resource.idColumn + "=? AND UPPER(COALESCE(delete_yn,'N'))<>'Y' FOR UPDATE", Integer.class, id);
        if (live.isEmpty()) throw new BusinessException(resource.notFound);
        if (resource == Resource.ROOM) {
            List<Integer> buildings = jdbc.queryForList("SELECT b.building_id FROM building b JOIN rooms r ON r.building_id=b.building_id"
                    + " WHERE r.room_id=? AND UPPER(COALESCE(b.delete_yn,'N'))<>'Y'", Integer.class, id);
            if (buildings.isEmpty()) throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
    }

    private void audit(String actor, String userId, String type, Integer resourceId, String decision, String note) {
        jdbc.update("INSERT INTO ownership_review_events(actor_id,target_user_id,review_type,resource_id,decision,review_note) VALUES(?,?,?,?,?,?)",
                actor, userId, type, resourceId, decision, note);
    }

    private static OwnerApplicationResponse applicationRow(ResultSet rs, int row) throws SQLException {
        return new OwnerApplicationResponse(rs.getString("user_id"), rs.getString("user_nm"), rs.getString("user_email"),
                rs.getString("status"), rs.getString("application_note"), rs.getString("review_note"), rs.getString("reviewed_by"),
                time(rs, "created_at"), time(rs, "reviewed_at"));
    }

    private static AssetClaimResponse claimRow(ResultSet rs, Resource resource) throws SQLException {
        return new AssetClaimResponse(rs.getInt("membership_id"), resource.name(), rs.getInt("resource_id"),
                rs.getString("resource_name"), rs.getString("user_id"), rs.getString("user_nm"), rs.getString("state"),
                rs.getString("origin"), rs.getString("proof_reference"), rs.getString("review_note"), rs.getString("reviewed_by"),
                time(rs, "created_at"), time(rs, "reviewed_at"));
    }

    private static Membership membershipRow(ResultSet rs) throws SQLException {
        return new Membership(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5));
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Resource resource(String value) {
        if (value != null) {
            try { return Resource.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { /* Invalid client input. */ }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST);
    }

    private static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) badRequest();
        return value.trim();
    }

    private static String filter(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) badRequest();
        return normalized;
    }

    private static void positive(Integer id) { if (id == null || id <= 0) badRequest(); }
    private static void badRequest() { throw new BusinessException(ErrorCode.BAD_REQUEST); }
    private static void forbidden() { throw new BusinessException(ErrorCode.FORBIDDEN); }
    private record Membership(Integer id, String userId, Integer resourceId, String state, String proof) {}

    private enum Resource {
        BUILDING("building", "building_memberships", "building_id", ErrorCode.BUILDING_NOT_FOUND),
        ROOM("rooms", "room_memberships", "room_id", ErrorCode.ROOM_NOT_FOUND);

        private final String table;
        private final String memberships;
        private final String idColumn;
        private final ErrorCode notFound;

        Resource(String table, String memberships, String idColumn, ErrorCode notFound) {
            this.table = table;
            this.memberships = memberships;
            this.idColumn = idColumn;
            this.notFound = notFound;
        }
    }
}
