package com.jtdev.teslaautomaticpreconditioning.config;

import com.jtdev.teslaautomaticpreconditioning.fleetapi.FleetApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FleetApiClientConfig {

    @Value("${tessie.oauth.token}")
    private String accessToken;

    @Bean
    public FleetApi fleetApi() {
        return FleetApi.newBuilder().accessToken(accessToken).baseUrl("https://api.tessie.com/api/1").build();
    }
}
