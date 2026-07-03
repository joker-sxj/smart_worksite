package com.xd.smartworksite.common.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.xd.smartworksite.**.mapper")
public class MyBatisConfig {
}
