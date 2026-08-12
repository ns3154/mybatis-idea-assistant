package io.github.mybatisideaassistant.samples.gradle.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gradle 多模块样例入口。
 */
@SpringBootApplication
@MapperScan("io.github.mybatisideaassistant.samples.gradle.data.mapper")
public class GradleSampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(GradleSampleApplication.class, args);
    }
}
