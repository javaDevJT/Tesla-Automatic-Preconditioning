package com.jtdev.teslaautomaticpreconditioning;

import org.springframework.boot.SpringApplication;

public class TestTeslaAutomaticPreconditioningApplication {

    public static void main(String[] args) {
        SpringApplication.from(TeslaAutomaticPreconditioningApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
