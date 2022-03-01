package com.bk.authservice.main;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@ComponentScan(basePackages = "com.bk")
@EnableAutoConfiguration
public class Main {

    public static void main(String[] args) {
        System.setProperty("https.protocols", "SSLv3,TLSv1,TLSv1.1,TLSv1.2");
        SpringApplication.run(Main.class, args);
    }

}
