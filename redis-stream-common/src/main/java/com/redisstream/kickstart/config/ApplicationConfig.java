package com.redisstream.kickstart.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import javax.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Validated
@Configuration
@EnableAutoConfiguration
@ConfigurationProperties(prefix = "")
public @Data
class ApplicationConfig {

    private String oddListKey;
    private String evenListKey;
    private String oddEvenStream;
    private String consumerGroupName;
    private String redisHost;
    private int redisPort;
    private String recordCacheKey;
    private long streamPollTimeout;
    private String consumerName;
    private String failureListKey;

    @PostConstruct
    public void setConsumerName() throws UnknownHostException {
        consumerName = InetAddress.getLocalHost().getHostName();
    }
}
