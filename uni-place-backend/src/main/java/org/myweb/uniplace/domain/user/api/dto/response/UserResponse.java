package org.myweb.uniplace.domain.user.api.dto.response;

import lombok.Builder;
import lombok.Getter;
import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.UserStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Getter
@Builder(toBuilder = true)
public class UserResponse {
    private String userId;
    private String userNm;
    private String userNickname;
    private String userEmail;
    private LocalDate userBirth;
    private String userTel;

    private String userRole;
    private String accountKind;
    private List<String> roles;
    private String ownerStatus;
    private UserStatus userSt;
    private String deleteYN;

    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;

    public static UserResponse from(User u) {
        String roleName = u.getUserRole() == null ? null : u.getUserRole().name();

        return UserResponse.builder()
                .userId(u.getUserId())
                .userNm(u.getUserNm())
                .userNickname(u.getUserNickname())
                .userEmail(u.getUserEmail())
                .userBirth(u.getUserBirth())
                .userTel(u.getUserTel())
                .userRole(roleName)
                .accountKind(u.getAccountKind() == null ? null : u.getAccountKind().name())
                .roles(List.of())
                .ownerStatus("NONE")
                .userSt(u.getUserSt())
                .deleteYN(u.getDeleteYN())
                .createdAt(u.getCreatedAt())
                .lastLoginAt(u.getLastLoginAt())
                .build();
    }

    public static UserResponse from(User user, String accountKind, Collection<String> effectiveRoles,
            String ownerStatus) {
        List<String> roles = effectiveRoles == null ? List.of() : effectiveRoles.stream().sorted().toList();
        String displayRole = roles.contains("ADMIN") ? "admin" : roles.contains("TENANT") ? "tenant" : "user";
        return from(user).toBuilder()
                .userRole(displayRole)
                .accountKind(accountKind)
                .roles(roles)
                .ownerStatus(ownerStatus == null ? "NONE" : ownerStatus)
                .build();
    }
}
