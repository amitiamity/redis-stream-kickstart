package com.redisstream.kickstart.listener;

import com.redisstream.kickstart.producer.StreamProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProducerListener {

    @Autowired
    private StreamProducer streamProducer;

    @EventListener
    public void onEvent(ApplicationEvent applicationEvent) {
        log.info("Producer has been started");
        streamProducer.produceNumbers();
    }
}
