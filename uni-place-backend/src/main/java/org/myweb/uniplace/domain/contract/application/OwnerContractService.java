package org.myweb.uniplace.domain.contract.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.contract.api.dto.request.ContractAdminSearchRequest;
import org.myweb.uniplace.domain.contract.api.dto.request.ContractUpdateRequest;
import org.myweb.uniplace.domain.contract.api.dto.request.ResidentCreateRequest;
import org.myweb.uniplace.domain.contract.api.dto.response.AdminContractSummaryResponse;
import org.myweb.uniplace.domain.contract.api.dto.response.ContractResponse;
import org.myweb.uniplace.domain.contract.api.dto.response.ResidentResponse;
import org.myweb.uniplace.domain.contract.domain.entity.Contract;
import org.myweb.uniplace.domain.contract.repository.ContractRepository;
import org.myweb.uniplace.domain.contract.repository.ResidentRepository;
import org.myweb.uniplace.domain.file.application.FileService;
import org.myweb.uniplace.domain.ownership.application.publicapi.OwnershipAccess;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner-facing gateway; internal lifecycle/scheduler methods keep their existing transaction contract. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerContractService {
    private final OwnershipAccess ownership;
    private final ContractRepository contracts;
    private final ResidentRepository residents;
    private final ContractService contractService;
    private final ResidentService residentService;
    private final FileService files;

    public Page<AdminContractSummaryResponse> search(ContractAdminSearchRequest request, Pageable pageable) {
        ownership.requireOwner();
        var ids = ownership.readableContractIds();
        if (ids.isEmpty()) return Page.empty(pageable);
        Specification<Contract> scope = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("contractId").in(ids));
            if (request.getContractId() != null) predicates.add(cb.equal(root.get("contractId"), request.getContractId()));
            if (request.getContractStatus() != null) predicates.add(cb.equal(root.get("contractSt"), request.getContractStatus()));
            if (request.getBuildingId() != null) predicates.add(cb.equal(root.get("room").get("building").get("buildingId"), request.getBuildingId()));
            if (request.getRoomNo() != null) predicates.add(cb.equal(root.get("room").get("roomNo"), request.getRoomNo()));
            if (request.getStartFrom() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("contractStart"), request.getStartFrom()));
            if (request.getEndTo() != null) predicates.add(cb.lessThanOrEqualTo(root.get("contractEnd"), request.getEndTo()));
            if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
                String pattern = "%" + request.getKeyword().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("room").get("building").get("buildingNm")), pattern),
                        cb.like(cb.lower(root.get("lessorNm")), pattern), cb.like(cb.lower(root.get("user").get("userId")), pattern)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return contracts.findAll(scope, pageable).map(contract -> {
            String filename = null;
            if (contract.getContractPdfFileId() != null) {
                try { filename = files.getFile(contract.getContractPdfFileId()).getOriginFilename(); }
                catch (BusinessException missingOrInaccessible) { /* Keep unavailable attachments out of the summary. */ }
            }
            return AdminContractSummaryResponse.fromEntity(contract, filename);
        });
    }

    public ContractResponse get(Integer contractId) {
        String userId = ownership.requireOwner();
        if (!ownership.canReadContract(userId, contractId)) throw new BusinessException(ErrorCode.FORBIDDEN);
        return contractService.getContractForAdmin(contractId);
    }

    @Transactional
    public ContractResponse update(Integer contractId, ContractUpdateRequest request) {
        ownership.requireCanManageContract(contractId);
        return contractService.updateContractForAdmin(contractId, request);
    }

    public List<ResidentResponse> residents() {
        ownership.requireOwner();
        var ids = ownership.manageableContractIds();
        return ids.isEmpty() ? List.of() : residents.findByContractIdIn(ids).stream().map(ResidentResponse::fromEntity).toList();
    }

    @Transactional
    public ResidentResponse createResident(ResidentCreateRequest request) {
        ownership.requireOwner();
        if (request == null || request.getContractId() == null || request.getBuildingId() == null
                || request.getUserId() == null || request.getUserId().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        if (!contracts.existsById(request.getContractId())) throw new BusinessException(ErrorCode.CONTRACT_NOT_FOUND);
        ownership.requireCanManageContract(request.getContractId());
        return residentService.createResident(request);
    }
}
