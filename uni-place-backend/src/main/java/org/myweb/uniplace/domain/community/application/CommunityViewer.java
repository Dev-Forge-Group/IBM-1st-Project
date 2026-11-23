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
        // Account administrators have no business permission to identify anonymous authors.
        return new CommunityViewer(user.getUserId(), false);
    }
}
