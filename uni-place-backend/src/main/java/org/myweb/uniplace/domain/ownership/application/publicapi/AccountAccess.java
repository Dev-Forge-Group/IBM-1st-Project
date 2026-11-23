package org.myweb.uniplace.domain.ownership.application.publicapi;

import java.util.Set;

/** Current database capabilities, independent of the selected UI workspace. */
public record AccountAccess(String accountKind, Set<String> roles, String ownerStatus) {
    public AccountAccess {
        roles = Set.copyOf(roles);
    }
}
