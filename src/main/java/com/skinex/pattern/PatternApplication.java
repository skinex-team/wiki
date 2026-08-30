package com.skinex.pattern;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PatternApplication {
    public static void main(String[] args) {
        SpringApplication.run(PatternApplication.class, args);
    }
}
