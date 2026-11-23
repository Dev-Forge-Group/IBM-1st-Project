package org.myweb.uniplace.domain.contract.repository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

import org.myweb.uniplace.domain.contract.domain.entity.Resident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ResidentRepository extends JpaRepository<Resident, Integer> {
	boolean existsByContractIdAndUserId(Integer contractId, String userId);

    Optional<Resident> findByContractIdAndUserId(Integer contractId, String userId);

    List<Resident> findByContractIdIn(Collection<Integer> contractIds);

    void deleteByContractIdAndUserId(Integer contractId, String userId);
}
