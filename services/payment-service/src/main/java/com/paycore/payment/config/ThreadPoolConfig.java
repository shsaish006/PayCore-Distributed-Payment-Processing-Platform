package com.paycore.payment.config;

import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

@Configuration
public class ThreadPoolConfig {

    @Bean
    public AsyncTaskExecutor applicationTaskExecutor() {
        // Build thread pool using Java 21 Virtual Threads
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean
    public TomcatProtocolHandlerCustomizer<?> tomcatProtocolHandlerCustomizer() {
        // Enforce virtual threads for all incoming HTTP connection handler threads in Tomcat
        return protocolHandler -> protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }
}
