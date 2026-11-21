package io.github.seonwkim.example.shardedchat.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The sharded chat's control app: the platform the chat nodes run on, and the page (README.md). */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlApplication.class, args);
    }
}
