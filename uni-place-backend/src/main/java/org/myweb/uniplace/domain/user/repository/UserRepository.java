package org.myweb.uniplace.domain.user.repository;

import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.AccountKind;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUserEmail(String userEmail);

    boolean existsByUserEmail(String userEmail);

    boolean existsByUserTel(String userTel);

    boolean existsByUserNickname(String userNickname);

    List<User> findAllByUserRole(UserRole userRole);

    Page<User> findByUserRole(UserRole userRole, Pageable pageable);

    Page<User> findByAccountKind(AccountKind accountKind, Pageable pageable);

    Page<User> findByAccountKindAndUserRole(AccountKind accountKind, UserRole userRole, Pageable pageable);

    /** Account and valid tenancy scope are applied before both count and pagination. */
    @Query("""
            select u from User u
            where u.accountKind = org.myweb.uniplace.domain.user.domain.enums.AccountKind.MEMBER
              and u.userSt = org.myweb.uniplace.domain.user.domain.enums.UserStatus.active
              and upper(coalesce(u.deleteYN, 'N')) <> 'Y'
              and exists (
                  select c.contractId from Contract c join c.room r join r.building b
                  where c.user = u
                    and c.contractSt = org.myweb.uniplace.domain.contract.domain.enums.ContractStatus.active
                    and c.contractStart <= :today and c.contractEnd >= :today
                    and upper(coalesce(r.deleteYn, 'N')) <> 'Y'
                    and upper(coalesce(b.deleteYn, 'N')) <> 'Y'
                    and exists (
                        select resident.residentId from Resident resident
                        where resident.contractId = c.contractId and resident.userId = u.userId
                          and resident.buildingId = b.buildingId
                    )
              )
            """)
    Page<User> findCurrentTenantMembers(@Param("today") LocalDate today, Pageable pageable);

    boolean existsByUserIdAndUserRole(String userId, UserRole userRole);

    // 아이디 찾기: 이름 + 전화번호 일치
    Optional<User> findByUserNmAndUserTel(String userNm, String userTel);
}
