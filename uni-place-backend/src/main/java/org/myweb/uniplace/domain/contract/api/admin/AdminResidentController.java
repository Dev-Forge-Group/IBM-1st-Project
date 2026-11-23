package org.myweb.uniplace.domain.contract.api.admin;

import java.util.List;

import org.myweb.uniplace.domain.contract.api.dto.request.ResidentCreateRequest;
import org.myweb.uniplace.domain.contract.api.dto.response.ResidentResponse;
import org.myweb.uniplace.domain.contract.application.OwnerContractService;
import org.myweb.uniplace.global.response.ApiResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/owner/residents")
public class AdminResidentController {

    private final OwnerContractService residentService;

    /**
     * 거주자 목록(관리자)
     * GET /admin/residents
     */
    @GetMapping
    public ApiResponse<List<ResidentResponse>> getResidentList() {
        return ApiResponse.ok(residentService.residents());
    }
    
    // ✅ 거주자 생성(관리자 수동)
    @PostMapping
    public ApiResponse<ResidentResponse> createResident(
            @Validated @RequestBody ResidentCreateRequest request
    ) {
        return ApiResponse.ok(residentService.createResident(request));
    }
}
