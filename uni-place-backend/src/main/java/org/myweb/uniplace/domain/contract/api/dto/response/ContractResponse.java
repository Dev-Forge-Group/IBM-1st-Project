package org.myweb.uniplace.domain.contract.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.myweb.uniplace.domain.contract.domain.entity.Contract;
import org.myweb.uniplace.domain.contract.domain.enums.ContractStatus;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.global.storage.StorageService;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractResponse {

    private Integer contractId;
    private Integer roomId;
    private Integer roomNo;
    private Integer buildingId;
    private String buildingNm;
    private String buildingAddr;
    private LocalDate contractStart;
    private LocalDate contractEnd;
    private BigDecimal deposit;
    private BigDecimal rentPrice;
    private BigDecimal manageFee;
    private Integer paymentDay;
    private ContractStatus contractStatus;
    private String lessorNm;
    private LocalDateTime requestedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime moveinAt;
    private Integer contractPdfFileId;
    private String contractPdfUrl;

    /**
     * StorageService 인자를 받는 기존 호출도 인증된 파일 proxy URL을 사용한다.
     */
    public static ContractResponse fromEntity(Contract c, StorageService storage) {
        Room r = c.getRoom();
        Building b = (r != null ? r.getBuilding() : null);

        Integer pdfId = c.getContractPdfFileId();
        String pdfUrl = pdfId != null ? "/files/" + pdfId + "/download" : null;

        return ContractResponse.builder()
                .contractId(c.getContractId())
                .roomId(r != null ? r.getRoomId() : null)
                .roomNo(r != null ? r.getRoomNo() : null)
                .buildingId(b != null ? b.getBuildingId() : null)
                .buildingNm(b != null ? b.getBuildingNm() : null)
                .buildingAddr(b != null ? b.getBuildingAddr() : null)
                .contractStart(c.getContractStart())
                .contractEnd(c.getContractEnd())
                .deposit(c.getDeposit())
                .rentPrice(c.getRentPrice())
                .manageFee(c.getManageFee())
                .paymentDay(c.getPaymentDay())
                .contractStatus(c.getContractSt())
                .lessorNm(c.getLessorNm())
                .requestedAt(c.getCreatedAt())
                .approvedAt(c.getSignAt())
                .moveinAt(c.getMoveinAt())
                .contractPdfFileId(pdfId)
                .contractPdfUrl(pdfUrl)
                .build();
    }

    /** 하위 호환 (StorageService 없이 호출 시에도 인증 proxy 사용) */
    public static ContractResponse fromEntity(Contract c) {
        return fromEntity(c, null);
    }
}
