package com.cakeandcandles.party;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PartyServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PartyServiceApplication.class, args);
    }
}
