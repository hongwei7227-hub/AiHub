package com.cityaihub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.cityaihub.mapper")
@SpringBootApplication
@EnableScheduling
public class CityAIHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityAIHubApplication.class, args);
    }

}
