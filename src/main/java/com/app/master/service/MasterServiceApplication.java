package com.app.master.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients
@ComponentScan(
    basePackages = {"com.app.core", "com.app.master.service"},
    excludeFilters = {
        @ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = "com\\.app\\.core\\.config\\..*"
        ),
        @ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = "com\\.app\\.core\\.service\\.impl\\..*"
        )
    }
)
@EntityScan(basePackages = {"com.app.master.service.core.entity"})
@EnableJpaRepositories(basePackages = {"com.app.master.service.repository"})
@EnableAsync
@EnableScheduling
public class MasterServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MasterServiceApplication.class, args);
	}

}