package com.cityaihub.ai.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.cityaihub.ai.mapper")
public class AiMapperConfiguration {
}
