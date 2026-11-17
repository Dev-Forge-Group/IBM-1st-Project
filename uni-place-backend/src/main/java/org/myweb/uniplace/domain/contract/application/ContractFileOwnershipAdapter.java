package org.myweb.uniplace.domain.contract.application;

import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.contract.repository.ContractRepository;
import org.myweb.uniplace.domain.file.application.publicapi.ContractFileOwnership;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContractFileOwnershipAdapter implements ContractFileOwnership {
    private final ContractRepository contractRepository;

    @Override
    public boolean isOwner(Integer contractId, String userId) {
        return contractRepository.existsByContractIdAndUser_UserId(contractId, userId);
    }
}
