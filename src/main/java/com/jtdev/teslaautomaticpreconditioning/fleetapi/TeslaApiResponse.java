package com.jtdev.teslaautomaticpreconditioning.fleetapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wrapper class for Tesla API responses that wrap vehicle data in a "response" object
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeslaApiResponse {
    private VehicleData response;
}
