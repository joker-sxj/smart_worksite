package com.xd.smartworksite.intelligence.facade;

import com.xd.smartworksite.intelligence.dto.RouteDecisionRequest;
import com.xd.smartworksite.intelligence.dto.RouteDecisionResponse;

public interface RouteDecisionFacade {

    RouteDecisionResponse decide(RouteDecisionRequest request);
}
