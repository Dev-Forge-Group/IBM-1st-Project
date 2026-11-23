// 경로: org/myweb/uniplace/domain/property/api/dto/response/BuildingDetailResponse.java
package org.myweb.uniplace.domain.property.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

import org.myweb.uniplace.domain.file.api.dto.response.FileResponse;
import org.myweb.uniplace.domain.property.domain.entity.Building;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BuildingDetailResponse {

    private Integer buildingId;
    private String buildingNm;
    private String buildingAddr;
    private String buildingDesc;

    private String landCategory;
    private BigDecimal buildSize;
    private String buildingUsage;
    private String existElv;
    private Integer parkingCapacity;

    // ✅ 임대인 정보
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String buildingLessorNm;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String buildingLessorTel;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String buildingLessorAddr;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String buildingLessorRrn;

    private List<FileResponse> files;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String ownershipStatus;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean publicVisible;

    public BuildingDetailResponse withPublicVisibility(boolean visible) {
        return toBuilder().publicVisible(visible).build();
    }

    public BuildingDetailResponse withOwnershipStatus(String status) {
        return toBuilder().ownershipStatus(status).build();
    }

    public static BuildingDetailResponse fromPublicEntity(Building entity, List<FileResponse> files) {
        return fromEntity(entity, files).toBuilder()
                .buildingLessorNm(null).buildingLessorTel(null)
                .buildingLessorAddr(null).buildingLessorRrn(null).build();
    }

    public static BuildingDetailResponse fromEntity(Building b, List<FileResponse> files) {
        return BuildingDetailResponse.builder()
                .buildingId(b.getBuildingId())
                .buildingNm(b.getBuildingNm())
                .buildingAddr(b.getBuildingAddr())
                .buildingDesc(b.getBuildingDesc())
                .landCategory(b.getLandCategory())
                .buildSize(b.getBuildSize())
                .buildingUsage(b.getBuildingUsage())
                .existElv(b.getExistElv())
                .parkingCapacity(b.getParkingCapacity())
                .buildingLessorNm(b.getBuildingLessorNm())
                .buildingLessorTel(b.getBuildingLessorTel())
                .buildingLessorAddr(b.getBuildingLessorAddr())
                .buildingLessorRrn(b.getBuildingLessorRrn())
                .files(files)
                .build();
    }
}
