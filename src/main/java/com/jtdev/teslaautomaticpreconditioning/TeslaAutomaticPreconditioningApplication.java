package com.jtdev.teslaautomaticpreconditioning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class TeslaAutomaticPreconditioningApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeslaAutomaticPreconditioningApplication.class, args);
    }

}
