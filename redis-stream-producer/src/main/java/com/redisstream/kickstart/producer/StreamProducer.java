package com.redisstream.kickstart.producer;

import com.redisstream.kickstart.config.ApplicationConfig;
import com.redisstream.kickstart.constant.Constant;
import javafx.application.Application;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Slf4j
@Component
public class StreamProducer {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ApplicationConfig config;

    public void produceNumbers() {
        Random random = new Random();
        while (true) {
            int number = random.nextInt(2000);
            Map<String, String> fields = new HashMap<>();
            fields.put(Constant.NUMBER_KEY, String.valueOf(number));
            StringRecord record = StreamRecords.string(fields).withStreamKey(config.getOddEvenStream());
            redisTemplate.opsForStream().add(record);
            log.info("Message has been published : {}", number);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.error("Thread error:", e);
            }
        }
    }
}
