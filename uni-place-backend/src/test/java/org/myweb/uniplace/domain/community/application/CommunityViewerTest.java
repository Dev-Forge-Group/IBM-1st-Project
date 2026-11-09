package org.myweb.uniplace.domain.community.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.myweb.uniplace.global.security.AuthUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CommunityViewerTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anUnauthenticatedPrincipalCannotRevealAdministratorIdentity() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthUser("admin", "admin"), null));

        assertThat(CommunityViewer.current()).isEqualTo(new CommunityViewer(null, false));
    }

    @Test
    void anUnrecognizedPrincipalHasNoOwnershipOrAdministratorRights() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, java.util.List.of()));

        assertThat(CommunityViewer.current()).isEqualTo(new CommunityViewer(null, false));
    }
}
