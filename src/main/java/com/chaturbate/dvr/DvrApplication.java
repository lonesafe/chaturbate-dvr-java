package com.chaturbate.dvr;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Chaturbate DVR 应用程序入口
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.chaturbate.dvr.mapper")
public class DvrApplication {

    public static void main(String[] args) {
        SpringApplication.run(DvrApplication.class, args);
    }
}
