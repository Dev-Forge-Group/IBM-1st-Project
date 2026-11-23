package org.myweb.uniplace.domain.property.api.admin;

import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.property.application.PropertyDashboardService;
import org.myweb.uniplace.domain.property.api.dto.response.PropertyDashboardResponse;
import org.myweb.uniplace.global.response.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/owner/property-dashboard")
@RequiredArgsConstructor
public class AdminPropertyDashboardController {
    private final PropertyDashboardService propertyDashboardService;

    @GetMapping
    public ApiResponse<PropertyDashboardResponse> getDashboard(
            @RequestParam(name = "buildingId", required = false) Integer buildingId) {
        return ApiResponse.ok(propertyDashboardService.getDashboard(buildingId));
    }
}
