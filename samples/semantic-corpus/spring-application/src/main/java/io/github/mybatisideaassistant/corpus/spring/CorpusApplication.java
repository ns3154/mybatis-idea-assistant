package io.github.mybatisideaassistant.corpus.spring;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("io.github.mybatisideaassistant.corpus.java.mapper")
public class CorpusApplication {
    public static void main(String[] args) {
        SpringApplication.run(CorpusApplication.class, args);
    }
}
