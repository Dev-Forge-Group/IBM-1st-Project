package org.myweb.uniplace.domain.contract.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.contract.application.publicapi.ReviewContractHistory;
import org.myweb.uniplace.domain.contract.domain.enums.ContractStatus;
import org.myweb.uniplace.domain.contract.repository.ContractRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewContractHistoryAdapter implements ReviewContractHistory {
    private final ContractRepository contractRepository;

    @Override
    public boolean hasActiveOrEndedContract(String userId, Integer roomId) {
        return contractRepository.existsByUserIdAndRoomIdAndStatusIn(
                userId, roomId, List.of(ContractStatus.active, ContractStatus.ended));
    }
}
