package org.myweb.uniplace.domain.file.application;

import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.file.application.publicapi.ContractFileOwnership;
import org.myweb.uniplace.domain.file.domain.enums.FileRefType;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.security.AuthUser;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContractFileAccessPolicy {
    private final ContractFileOwnership contractFileOwnership;

    public void requireAccess(String parentType, Integer parentId) {
        if (parentType == null || !FileRefType.CONTRACT.dbValue().equalsIgnoreCase(parentType.trim())) {
            return;
        }

        AuthUser user = authenticatedUser();
        if ("guest".equalsIgnoreCase(user.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if ("admin".equalsIgnoreCase(user.getRole())) {
            return;
        }
        if (parentId == null || !contractFileOwnership.isOwner(parentId, user.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    public void requireAdministrator() {
        AuthUser user = authenticatedUser();
        if (!"admin".equalsIgnoreCase(user.getRole())) {
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
