// 경로: org/myweb/uniplace/domain/property/api/dto/response/BuildingSummaryResponse.java
package org.myweb.uniplace.domain.property.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

import org.myweb.uniplace.domain.property.domain.entity.Building;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BuildingSummaryResponse {

    private Integer buildingId;
    private String buildingNm;
    private String buildingAddr;
    private String buildingDesc;

    private String landCategory;
    private BigDecimal buildSize;
    private String buildingUsage;
    private String existElv;
    private Integer parkingCapacity;

    // ✅ 썸네일
    private Integer thumbFileId;
    private String thumbUrl;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String ownershipStatus;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean publicVisible;

    public BuildingSummaryResponse withPublicVisibility(boolean visible) {
        return toBuilder().publicVisible(visible).build();
    }

    public BuildingSummaryResponse withOwnershipStatus(String status) {
        return toBuilder().ownershipStatus(status).build();
    }

    public static BuildingSummaryResponse fromEntity(Building b, Integer thumbFileId, String thumbUrl) {
        return BuildingSummaryResponse.builder()
                .buildingId(b.getBuildingId())
                .buildingNm(b.getBuildingNm())
                .buildingAddr(b.getBuildingAddr())
                .buildingDesc(b.getBuildingDesc())
                .landCategory(b.getLandCategory())
                .buildSize(b.getBuildSize())
                .buildingUsage(b.getBuildingUsage())
                .existElv(b.getExistElv())
                .parkingCapacity(b.getParkingCapacity())
                .thumbFileId(thumbFileId)
                .thumbUrl(thumbUrl)
                .build();
    }
}