package com.redisstream.kickstart.scheduler;

import com.redisstream.kickstart.config.ApplicationConfig;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@EnableScheduling
@Component
public class PendingMessageScheduler {

    private static long MAX_NUMBER_FETCH = 1;
    private static long MAX_RETRY = 3;
    private static final String NUMBER_KEY = "number";
    private String consumerName;
    private String streamName;
    private String consumerGroupName;

    @Autowired
    private ApplicationConfig config;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Scheduled(fixedRate = 4000)
    public void processPendingMessage() {

        consumerName = config.getConsumerName();
        streamName = config.getOddEvenStream();
        consumerGroupName = config.getConsumerGroupName();

        log.info("Processing pending message by consumer {}", consumerName);
        PendingMessages messages = redisTemplate.opsForStream().pending(streamName,
                consumerGroupName, Range.unbounded(), MAX_NUMBER_FETCH);
        for (PendingMessage message : messages) {
            //claim message
            claimMessage(message);
            processMessage(message);
        }
    }

    /**
     * claim the message
     *
     * @param pendingMessage
     */
    private void claimMessage(PendingMessage pendingMessage) {
        RedisAsyncCommands commands = (RedisAsyncCommands) redisTemplate.getConnectionFactory().getConnection().getNativeConnection();
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(streamName)
                .add(consumerGroupName)
                .add(consumerName)
                .add("20") //idle time , message will only be claimed if it has been idle by 20 ms
                .add(pendingMessage.getIdAsString());
        commands.dispatch(CommandType.XCLAIM, new StatusOutput<>(StringCodec.UTF8), args);
        log.info("Message: " + pendingMessage.getIdAsString() + " has been claimed by " + consumerGroupName + ":" + consumerName);
    }

    /**
     * If the maximum retry count lapses, then add the message into error list and acknowledge the message (remove from
     * the pending list).
     * Else process the message and acknowledge it
     */
    private void processMessage(PendingMessage pendingMessage) {

        List<MapRecord<String, Object, Object>> messagesToProcess = redisTemplate.opsForStream().range(streamName,
                Range.closed(pendingMessage.getIdAsString(), pendingMessage.getIdAsString()));

        if (messagesToProcess == null || messagesToProcess.isEmpty()) {
            log.error("Message is not present. It has been either processed or deleted by some other process : {}",
                    pendingMessage.getIdAsString());
        } else if (pendingMessage.getTotalDeliveryCount() > MAX_RETRY) {
            MapRecord<String, Object, Object> message = messagesToProcess.get(0);
            redisTemplate.opsForList().rightPush(config.getFailureListKey(), message.getValue().get(NUMBER_KEY));
            redisTemplate.opsForStream().acknowledge(streamName, consumerGroupName, pendingMessage.getIdAsString());
            log.info("Message has been added into failure list and acknowledged : {}", pendingMessage.getIdAsString());
        } else {
            try {
                MapRecord<String, Object, Object> message = messagesToProcess.get(0);
                String inputNumber = (String) message.getValue().get(NUMBER_KEY);
                final int number = Integer.parseInt(inputNumber);
                if (number % 2 == 0) {
                    redisTemplate.opsForList().rightPush(config.getEvenListKey(), inputNumber);
                } else {
                    redisTemplate.opsForList().rightPush(config.getOddListKey(), inputNumber);
                }
                redisTemplate.opsForHash().put(config.getRecordCacheKey(), "last_result", number);
                redisTemplate.opsForHash().increment(config.getRecordCacheKey(), "processed", 1);
                redisTemplate.opsForHash().increment(config.getRecordCacheKey(), "retry_processed", 1);
                redisTemplate.opsForStream().acknowledge(config.getConsumerGroupName(), message);
                log.info("Message has been processed after retrying");
            } catch (Exception ex) {
                //log the exception and increment the number of errors count
                log.error("Failed to process the message: {} ", messagesToProcess.get(0).getValue().get(NUMBER_KEY), ex);
                redisTemplate.opsForHash().increment(config.getRecordCacheKey(), "errors", 1);
            }
        }
    }
}
