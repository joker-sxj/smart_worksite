package com.xd.smartworksite.intelligence.controller;

import com.xd.smartworksite.common.result.ApiResponse;
import com.xd.smartworksite.intelligence.dto.RouteDecisionRequest;
import com.xd.smartworksite.intelligence.dto.RouteDecisionResponse;
import com.xd.smartworksite.intelligence.facade.RouteDecisionFacade;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/intelligence")
@Validated
public class RouteDecisionController {

    private final RouteDecisionFacade routeDecisionFacade;

    public RouteDecisionController(RouteDecisionFacade routeDecisionFacade) {
        this.routeDecisionFacade = routeDecisionFacade;
    }

    @PostMapping("/route")
    public ApiResponse<RouteDecisionResponse> decide(@Valid @RequestBody RouteDecisionRequest request) {
        return ApiResponse.success(routeDecisionFacade.decide(request));
    }
}
