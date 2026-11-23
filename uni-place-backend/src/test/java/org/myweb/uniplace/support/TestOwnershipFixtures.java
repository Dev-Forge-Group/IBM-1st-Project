package org.myweb.uniplace.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic ownership setup for integration tests; production authorization still runs normally. */
public final class TestOwnershipFixtures {
    private TestOwnershipFixtures() {}

    /** A new, synthetic member operator for booking fixtures that do not use UserRepository. */
    public static void rentalOperator(JdbcTemplate jdbc, String userId, int buildingId) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id=?", Integer.class, userId);
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("Synthetic rental operator must use a new member identity");
        }
        jdbc.update("""
                INSERT INTO users(user_id,user_nm,user_email,user_pwd,user_birth,user_tel,user_nickname,
                                  user_role,account_kind,user_st,delete_yn,first_sign)
                VALUES(?,'Synthetic rental operator',?,'synthetic-not-a-login-hash','2000-01-01',
                       '010-0000-0000',?,'user','MEMBER','active','N','N')
                """, userId, userId + "@example.test", userId);
        buildingOwner(jdbc, userId, buildingId);
    }

    /** Explicitly change a legacy fixture persona; never use this to test a staff identity. */
    public static void memberOwner(JdbcTemplate jdbc, String userId) {
        jdbc.update("UPDATE users SET account_kind='MEMBER', user_role='user' WHERE user_id=?", userId);
        approveOwner(jdbc, userId);
    }

    public static void approveOwner(JdbcTemplate jdbc, String userId) {
        Integer members = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE user_id=? AND account_kind='MEMBER' AND user_role<>'admin'",
                Integer.class, userId);
        if (members == null || members != 1) {
            throw new IllegalArgumentException("Ownership fixture requires an explicit member identity");
        }
        jdbc.update("""
                INSERT INTO owner_profiles(user_id,status,application_note,review_note)
                VALUES(?,'APPROVED','Synthetic ownership fixture','Synthetic approval')
                ON DUPLICATE KEY UPDATE status='APPROVED',review_note='Synthetic approval'
                """, userId);
        jdbc.update("""
                INSERT INTO account_role_grants(user_id,role_code,grant_state,grant_reason)
                VALUES(?,'OWNER','ACTIVE','Synthetic ownership fixture')
                ON DUPLICATE KEY UPDATE grant_state='ACTIVE',grant_reason='Synthetic ownership fixture'
                """, userId);
    }

    public static void buildingOwner(JdbcTemplate jdbc, String userId, int buildingId) {
        approveOwner(jdbc, userId);
        jdbc.update("""
                INSERT INTO building_memberships(user_id,building_id,state,origin,proof_reference,review_note)
                VALUES(?,?,'ACTIVE','CLAIMED','synthetic-proof','Synthetic approval')
                ON DUPLICATE KEY UPDATE state='ACTIVE',proof_reference='synthetic-proof'
                """, userId, buildingId);
    }

    public static void roomOwner(JdbcTemplate jdbc, String userId, int roomId) {
        approveOwner(jdbc, userId);
        jdbc.update("""
                INSERT INTO room_memberships(user_id,room_id,state,origin,proof_reference,review_note)
                VALUES(?,?,'ACTIVE','CLAIMED','synthetic-proof','Synthetic approval')
                ON DUPLICATE KEY UPDATE state='ACTIVE',proof_reference='synthetic-proof'
                """, userId, roomId);
    }

    public static void contractParty(JdbcTemplate jdbc, String userId, int contractId) {
        jdbc.update("""
                INSERT INTO contract_owner_parties(contract_id,user_id,grant_reason)
                VALUES(?,?,'Synthetic explicit contract-party fixture')
                ON DUPLICATE KEY UPDATE grant_reason='Synthetic explicit contract-party fixture'
                """, contractId, userId);
    }

    public static void grantRentalOwner(JdbcTemplate jdbc, String userId, int buildingId,
            int roomId, Integer contractId) {
        Integer matches = jdbc.queryForObject("SELECT COUNT(*) FROM rooms WHERE room_id=? AND building_id=?",
                Integer.class, roomId, buildingId);
        if (matches == null || matches != 1) throw new IllegalArgumentException("Synthetic room/building mismatch");
        buildingOwner(jdbc, userId, buildingId);
        if (contractId != null) contractParty(jdbc, userId, contractId);
    }

    public static void createdDraftBuilding(JdbcTemplate jdbc, String userId, int buildingId) {
        approveOwner(jdbc, userId);
        jdbc.update("""
                INSERT INTO building_memberships(user_id,building_id,state,origin)
                VALUES(?,?,'PENDING','CREATED')
                ON DUPLICATE KEY UPDATE state='PENDING',origin='CREATED'
                """, userId, buildingId);
    }
}
