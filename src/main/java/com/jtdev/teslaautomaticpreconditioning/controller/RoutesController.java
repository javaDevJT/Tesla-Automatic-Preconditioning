package com.jtdev.teslaautomaticpreconditioning.controller;

import com.jtdev.teslaautomaticpreconditioning.service.RoutesCalculationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/routes")
public class RoutesController {

    private final RoutesCalculationService routesCalculationService;

    public RoutesController(RoutesCalculationService routesCalculationService) {
        this.routesCalculationService = routesCalculationService;
    }

    @GetMapping
    public java.util.List<ApiRoute> getRoutes(@RequestParam double latOrigin,
                                              @RequestParam double lonOrigin,
                                              @RequestParam double latDestination,
                                              @RequestParam double lonDestination) {
        var response = routesCalculationService.calculateRouteToDestination(latOrigin, lonOrigin, "28405 Van Dyke Road, Warren, MI");
        var routes = response.getRoutesList();

        var out = new java.util.ArrayList<ApiRoute>(routes.size());
        for (com.google.maps.routing.v2.Route r : routes) {
            long duration = r.hasDuration() ? r.getDuration().getSeconds() : null;
            String polyline = r.hasPolyline() ? r.getPolyline().getEncodedPolyline() : null;
            long distance = r.getDistanceMeters();
            out.add(new ApiRoute(distance, duration, polyline));
        }
        return out;
    }

    public record ApiRoute(long distanceMeters, long duration, String encodedPolyline) {
    }
}
