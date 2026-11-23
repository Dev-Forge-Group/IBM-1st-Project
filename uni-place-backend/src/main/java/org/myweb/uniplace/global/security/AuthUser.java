package org.myweb.uniplace.global.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Getter
public class AuthUser implements UserDetails {

    private final String userId;
    private final String role;
    private final String accountKind;
    private final Set<String> roles;
    private final String ownerStatus;

    /** Compatibility constructor for signed-token principals and trusted legacy callers. */
    public AuthUser(String userId, String role) {
        this.userId = userId;
        this.role = role;
        this.accountKind = "admin".equalsIgnoreCase(role) ? "STAFF"
                : "guest".equalsIgnoreCase(role) ? "GUEST" : "MEMBER";
        this.roles = normalizedRoles(accountKind, role == null ? List.of() : List.of(role));
        this.ownerStatus = "NONE";
    }

    /** Effective capabilities come from the current database; the single role remains a display alias. */
    public AuthUser(String userId, String role, String accountKind, Collection<String> roles, String ownerStatus) {
        this.userId = userId;
        this.role = role;
        this.accountKind = accountKind == null ? "MEMBER" : accountKind.toUpperCase(Locale.ROOT);
        this.roles = normalizedRoles(this.accountKind, roles);
        this.ownerStatus = ownerStatus == null ? "NONE" : ownerStatus;
    }

    public boolean hasRole(String requestedRole) {
        if (requestedRole == null) return false;
        String normalized = requestedRole.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ROLE_")) normalized = normalized.substring(5);
        return roles.contains(normalized);
    }

    public boolean isStaff() {
        return "STAFF".equals(accountKind);
    }

    private static Set<String> normalizedRoles(String kind, Collection<String> source) {
        Set<String> permitted = "STAFF".equals(kind) ? Set.of("ADMIN")
                : "GUEST".equals(kind) ? Set.of("GUEST") : Set.of("CUSTOMER", "USER", "TENANT", "OWNER");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (source != null) {
            source.stream().filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .map(value -> value.startsWith("ROLE_") ? value.substring(5) : value)
                    .filter(permitted::contains).sorted().forEach(normalized::add);
        }
        return Collections.unmodifiableSet(normalized);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(value -> new SimpleGrantedAuthority("ROLE_" + value)).toList();
    }

    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return userId; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
