package org.myweb.uniplace.domain.community.application;

import org.myweb.uniplace.global.security.AuthUser;
import org.springframework.security.core.context.SecurityContextHolder;

/** Viewer metadata comes from authentication, never from request DTOs. */
record CommunityViewer(String userId, boolean administrator) {
    static CommunityViewer current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthUser user)) {
            return new CommunityViewer(null, false);
        }
        return new CommunityViewer(user.getUserId(), "admin".equalsIgnoreCase(
                user.getRole() == null ? "" : user.getRole().trim()));
    }
}
