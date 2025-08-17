package com.jtdev.teslaautomaticpreconditioning.service;

import com.google.maps.routing.v2.*;
import com.google.type.LatLng;
import org.springframework.stereotype.Service;

@Service
public class RoutesCalculationService {

    private final RoutesClient routesClient;

    public RoutesCalculationService(RoutesClient routesClient) {
        this.routesClient = routesClient;
    }

    public ComputeRoutesResponse calculateRouteToDestination(double latOrigin, double lonOrigin,
                                                             String addressDestination) {
        ComputeRoutesRequest request = ComputeRoutesRequest.newBuilder()
                .setOrigin(Waypoint.newBuilder()
                        .setLocation(Location.newBuilder()
                                .setLatLng(LatLng.newBuilder()
                                        .setLatitude(latOrigin)
                                        .setLongitude(lonOrigin)
                                        .build())
                                .build())
                        .build())
                .setDestination(Waypoint.newBuilder()
                        .setAddress(addressDestination)
                        .setVehicleStopover(true)
                        .build())
                .setTravelMode(RouteTravelMode.DRIVE)
                .setRoutingPreference(RoutingPreference.TRAFFIC_AWARE_OPTIMAL)
                .addRequestedReferenceRoutes(ComputeRoutesRequest.ReferenceRoute.FUEL_EFFICIENT)
                .setRouteModifiers(RouteModifiers.newBuilder()
                        .setVehicleInfo(VehicleInfo.newBuilder()
                                .setEmissionType(VehicleEmissionType.ELECTRIC))
                        .build())
                .build();

        return routesClient.computeRoutes(request);
    }
}
