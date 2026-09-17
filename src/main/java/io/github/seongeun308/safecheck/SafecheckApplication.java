package io.github.seongeun308.safecheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SafecheckApplication {

    static void main(String[] args) {
        SpringApplication.run(SafecheckApplication.class, args);
    }

}
