package com.jtdev.teslaautomaticpreconditioning.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.VehicleData;
import com.jtdev.teslaautomaticpreconditioning.service.FleetApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tesla")
public class TeslaController {

    @Autowired
    private FleetApiService fleetApiService;

    @GetMapping("/vehicle-data")
    public VehicleData getVehicleData(@RequestParam String vin) throws JsonProcessingException {
        return fleetApiService.getVehicleData(vin);
    }
}
