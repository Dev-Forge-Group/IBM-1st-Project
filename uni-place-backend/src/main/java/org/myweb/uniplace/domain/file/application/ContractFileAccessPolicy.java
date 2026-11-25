package org.myweb.uniplace.domain.file.application;

import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.file.application.publicapi.ContractFileOwnership;
import org.myweb.uniplace.domain.ownership.application.publicapi.OwnershipAccess;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.security.AuthUser;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class ContractFileAccessPolicy {
    private final ContractFileOwnership contractFileOwnership;
    private final OwnershipAccess ownership;
    private final JdbcTemplate jdbc;

    /** Compatibility entry point; reads and writes are distinguished by FileService. */
    public void requireAccess(String parentType, Integer parentId) {
        requireRead(parentType, parentId);
    }

    public void requireRead(String parentType, Integer parentId) {
        switch (type(parentType)) {
            case "AI_DOCUMENT" -> forbidden();
            case "CONTRACT" -> {
                AuthUser user = member();
                if (parentId == null || !ownership.canReadContract(user.getUserId(), parentId)) forbidden();
            }
            case "BUILDING" -> {
                if (parentId == null) forbidden();
                if (!ownership.isPublicBuilding(parentId)) ownership.requireCanManageBuilding(parentId);
            }
            case "ROOM" -> {
                if (parentId == null) forbidden();
                if (!ownership.isPublicRoom(parentId)) ownership.requireCanManageRoom(parentId);
            }
            case "SPACE" -> {
                Integer buildingId = spaceBuilding(parentId);
                if (!ownership.isPublicBuilding(buildingId)) ownership.requireCanManageBuilding(buildingId);
            }
            default -> { /* Other domains retain their own established file consumption contract. */ }
        }
    }

    public void requireWrite(String parentType, Integer parentId) {
        switch (type(parentType)) {
            case "AI_DOCUMENT" -> forbidden();
            case "CONTRACT" -> {
                AuthUser user = member();
                // A consumer uploads the signature of their own newly saved contract in the same transaction.
                if (parentId == null || (!contractFileOwnership.isOwner(parentId, user.getUserId())
                        && !ownership.canManageContract(user.getUserId(), parentId))) forbidden();
            }
            case "BUILDING" -> ownership.requireCanManageBuilding(parentId);
            case "ROOM" -> ownership.requireCanManageRoom(parentId);
            case "SPACE" -> ownership.requireCanManageBuilding(spaceBuilding(parentId));
            default -> { /* Generic cleanup of non-property file types remains with its domain. */ }
        }
    }

    private AuthUser member() {
        AuthUser user = authenticatedUser();
        if (!"MEMBER".equals(user.getAccountKind())) forbidden();
        if (!user.getUserId().equals(ownership.requireMember())) forbidden();
        return user;
    }

    private String type(String parentType) {
        return parentType == null ? "" : parentType.trim().toUpperCase(Locale.ROOT);
    }

    private Integer spaceBuilding(Integer spaceId) {
        if (spaceId == null) throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        var ids = jdbc.queryForList("SELECT building_id FROM common_space WHERE space_id=?",
                Integer.class, spaceId);
        if (ids.isEmpty()) throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        return ids.get(0);
    }

    private void forbidden() {
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    public void requireAdministrator() {
        AuthUser user = authenticatedUser();
        if (!user.isStaff() || !user.hasRole("ADMIN")) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private AuthUser authenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof AuthUser user)
                || user.getUserId() == null || user.getUserId().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }
}
