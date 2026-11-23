-- Account identity is independent of additive business capabilities.
ALTER TABLE users ADD COLUMN account_kind VARCHAR(20) NOT NULL DEFAULT 'MEMBER';
UPDATE users SET account_kind = 'STAFF' WHERE user_role = 'admin';
ALTER TABLE users ADD CONSTRAINT ck_users_account_kind CHECK (account_kind IN ('MEMBER', 'STAFF'));

CREATE TABLE account_role_grants (
  user_id VARCHAR(50) NOT NULL,
  role_code VARCHAR(20) NOT NULL,
  grant_state VARCHAR(20) NOT NULL,
  granted_by VARCHAR(50),
  grant_reason VARCHAR(2000),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_id, role_code),
  CONSTRAINT fk_account_grant_user FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT fk_account_grant_reviewer FOREIGN KEY (granted_by) REFERENCES users(user_id),
  CONSTRAINT ck_account_grant_role CHECK (role_code IN ('CUSTOMER', 'OWNER')),
  CONSTRAINT ck_account_grant_state CHECK (grant_state IN ('ACTIVE', 'REVOKED'))
) ENGINE=InnoDB;

INSERT INTO account_role_grants (user_id, role_code, grant_state, grant_reason)
SELECT user_id, 'CUSTOMER', 'ACTIVE', 'Existing member account'
FROM users WHERE account_kind = 'MEMBER';

CREATE TABLE owner_profiles (
  user_id VARCHAR(50) NOT NULL PRIMARY KEY,
  status VARCHAR(20) NOT NULL,
  application_note VARCHAR(2000) NOT NULL,
  review_note VARCHAR(2000),
  reviewed_by VARCHAR(50),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  reviewed_at DATETIME(6),
  CONSTRAINT fk_owner_profile_user FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT fk_owner_profile_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(user_id),
  CONSTRAINT ck_owner_profile_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
) ENGINE=InnoDB;

CREATE TABLE building_memberships (
  membership_id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(50) NOT NULL,
  building_id INT NOT NULL,
  state VARCHAR(20) NOT NULL,
  origin VARCHAR(20) NOT NULL,
  proof_reference VARCHAR(1000),
  review_note VARCHAR(2000),
  reviewed_by VARCHAR(50),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  reviewed_at DATETIME(6),
  UNIQUE KEY uq_building_membership (user_id, building_id),
  KEY ix_building_membership_scope (building_id, state),
  CONSTRAINT fk_building_membership_user FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT fk_building_membership_asset FOREIGN KEY (building_id) REFERENCES building(building_id),
  CONSTRAINT fk_building_membership_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(user_id),
  CONSTRAINT ck_building_membership_state CHECK (state IN ('PENDING', 'ACTIVE', 'REJECTED', 'REVOKED')),
  CONSTRAINT ck_building_membership_origin CHECK (origin IN ('CREATED', 'CLAIMED'))
) ENGINE=InnoDB;

CREATE TABLE room_memberships (
  membership_id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(50) NOT NULL,
  room_id INT NOT NULL,
  state VARCHAR(20) NOT NULL,
  origin VARCHAR(20) NOT NULL,
  proof_reference VARCHAR(1000),
  review_note VARCHAR(2000),
  reviewed_by VARCHAR(50),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  reviewed_at DATETIME(6),
  UNIQUE KEY uq_room_membership (user_id, room_id),
  KEY ix_room_membership_scope (room_id, state),
  CONSTRAINT fk_room_membership_user FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT fk_room_membership_asset FOREIGN KEY (room_id) REFERENCES rooms(room_id),
  CONSTRAINT fk_room_membership_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(user_id),
  CONSTRAINT ck_room_membership_state CHECK (state IN ('PENDING', 'ACTIVE', 'REJECTED', 'REVOKED')),
  CONSTRAINT ck_room_membership_origin CHECK (origin IN ('CREATED', 'CLAIMED'))
) ENGINE=InnoDB;

-- Existing lessor strings do not establish a verified account or a contract party.
CREATE TABLE contract_owner_parties (
  contract_id INT NOT NULL,
  user_id VARCHAR(50) NOT NULL,
  granted_by VARCHAR(50),
  grant_reason VARCHAR(2000),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (contract_id, user_id),
  CONSTRAINT fk_contract_owner_party_contract FOREIGN KEY (contract_id) REFERENCES contract(contract_id),
  CONSTRAINT fk_contract_owner_party_user FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT fk_contract_owner_party_reviewer FOREIGN KEY (granted_by) REFERENCES users(user_id)
) ENGINE=InnoDB;

CREATE TABLE ownership_review_events (
  event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor_id VARCHAR(50) NOT NULL,
  target_user_id VARCHAR(50) NOT NULL,
  review_type VARCHAR(30) NOT NULL,
  resource_id INT,
  decision VARCHAR(20) NOT NULL,
  review_note VARCHAR(2000) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY ix_ownership_review_scope (review_type, resource_id, target_user_id),
  CONSTRAINT fk_ownership_review_actor FOREIGN KEY (actor_id) REFERENCES users(user_id),
  CONSTRAINT fk_ownership_review_target FOREIGN KEY (target_user_id) REFERENCES users(user_id)
) ENGINE=InnoDB;

-- Canonical publication scopes are shared by property APIs and public AI projections.
CREATE VIEW ownership_public_building_ids AS
SELECT b.building_id
FROM building b
WHERE UPPER(COALESCE(b.delete_yn, 'N')) <> 'Y'
  AND (
    NOT EXISTS (SELECT 1 FROM building_memberships created
                WHERE created.building_id=b.building_id AND created.origin='CREATED')
    OR EXISTS (
      SELECT 1 FROM building_memberships bm JOIN users u ON u.user_id=bm.user_id
      WHERE bm.building_id=b.building_id AND bm.state='ACTIVE'
        AND u.account_kind='MEMBER' AND u.user_st='active' AND UPPER(COALESCE(u.delete_yn,'N'))<>'Y'
        AND EXISTS (SELECT 1 FROM owner_profiles op WHERE op.user_id=u.user_id AND op.status='APPROVED')
        AND EXISTS (SELECT 1 FROM account_role_grants ag WHERE ag.user_id=u.user_id
                    AND ag.role_code='OWNER' AND ag.grant_state='ACTIVE')
    )
  );

CREATE VIEW ownership_public_room_ids AS
SELECT r.room_id
FROM rooms r
WHERE UPPER(COALESCE(r.delete_yn, 'N')) <> 'Y'
  AND r.building_id IN (SELECT building_id FROM ownership_public_building_ids)
  AND (
    NOT EXISTS (SELECT 1 FROM room_memberships explicit_unit
                WHERE explicit_unit.room_id=r.room_id AND explicit_unit.state='ACTIVE')
    OR EXISTS (
      SELECT 1 FROM room_memberships rm JOIN users u ON u.user_id=rm.user_id
      WHERE rm.room_id=r.room_id AND rm.state='ACTIVE'
        AND u.account_kind='MEMBER' AND u.user_st='active' AND UPPER(COALESCE(u.delete_yn,'N'))<>'Y'
        AND EXISTS (SELECT 1 FROM owner_profiles op WHERE op.user_id=u.user_id AND op.status='APPROVED')
        AND EXISTS (SELECT 1 FROM account_role_grants ag WHERE ag.user_id=u.user_id
                    AND ag.role_code='OWNER' AND ag.grant_state='ACTIVE')
    )
  );
