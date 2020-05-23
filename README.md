# redis-stream-kickstart

## Overview
The following repo shows how to use redis stream for producing and consuming data using consumer group. There are 3 modules in this project as below :
1. redis-stream-common : 
  This module is a library which has confiugration details to connect with redis and common files to be used among other modules.
2. redis-stream-producer : 
  This module is responsible to produce the data using spring data redis libraries.
3. redis-stream-consumer : 
  This module is responsible to consume the data. Identifying the pending messages, claiming and acknowledging them.

## Use Case
In this use case, we are producing a number through producer and consuming it based on either it is even or odd. We are adding these numbers in odd-list-key, even-list-key and failure-list-key respectively. Main goal to show main redis stream concepts like:
  1. Consumer group and processing the message successfully
  2. Pending messages (if message is not processed or acknowledged) 
  3. Claiming the messages (if any of the consumer goes down permanently or if the message is not processed does not until specified time) 

## Guidelines
Before cloning this repository and running it, please keep your redis server up. We can use gradle plugin to run the boot application.

1. Clone this repository
2. Build the project by running the below command
  ```gradle
    gradlew clean build
  ```
3. Run the producer application
  ```gradle
    gradlew :redis-stream-producer:bootRun
  ```
  By default this will run on port 8083 (mentioned in it's build.gradle). Also you can run on different port by running below gradle command
  ```gradle
     gradlew :redis-stream-producer:bootRun -Pport=[your choice]
  ```
  Producer will start the adding the data into stream.
4. Now you can run the multiple instances of the consumer appliation by using below gradle command
  ```gradle
    gradlew :redis-stream-consumer:bootRun -Pport=[your choice]
  ```
  You will observe consumer has started processing the message. There is a scheduler job running inside consumer application which will keep looking for any pending message to process it.

## Source Code Review
1. Create the redis connection using the lettuce connection factory by providing the redis host and port
  com.redisstream.kickstart.config.RedisConfig.java
```java
    @Bean
    public RedisStandaloneConfiguration redisStandaloneConfiguration() {
        return new RedisStandaloneConfiguration(applicationConfig.getRedisHost(), applicationConfig.getRedisPort());
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(redisStandaloneConfiguration());
    }
```
2. Creating the redis template
```java 
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }
```
3. Creating the producer to add the data into stream
  Class : com.redisstream.kickstart.producer.StreamProducer.produceNumbers()
```java
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
```
4. Creating stream consumer class
  Class : com.redisstream.kickstart.consumer.StreamConsumer.java
  1. We need to implement the StreamListener<K, V extends Record<K, ?>> interface of the spring data redis and override the onMessage
  2. We have implemented the InitializingBean to set afterPropertiesSet(). 
     This first checks if the stream exist or not before creating the consumer group. It consumer group does not exist, it creates with the native commands as below :
     ```java
      RedisAsyncCommands commands = (RedisAsyncCommands) redisTemplate.getConnectionFactory().getConnection().getNativeConnection();
                CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                        .add(CommandKeyword.CREATE)
                        .add(streamName)
                        .add(consumerGroupName)
                        .add("0")
                        .add("MKSTREAM");
                commands.dispatch(CommandType.XGROUP, new StatusOutput<>(StringCodec.UTF8), args);
     ```
     Corresponding redis-cli command is : XGROUP CREATE newstream mygroup 0 MKSTREAM
   3. Adding the StreamMessageListenerContainer
   ```java
        this.listenerContainer = StreamMessageListenerContainer.create(redisTemplate.getConnectionFactory(),
                StreamMessageListenerContainer
                        .StreamMessageListenerContainerOptions.builder()
                        .hashKeySerializer(new JdkSerializationRedisSerializer())
                        .hashValueSerializer(new JdkSerializationRedisSerializer())
                        .pollTimeout(Duration.ofMillis(config.getStreamPollTimeout()))
                        .build());
   ```
   4. Subscribing the listener
   Here we have mentioned the consumerName (to identify the consumer uniquely in consumer group) and read strategy to consume data from the last consumed offset.
   ```java
    this.subscription = listenerContainer.receive(
                Consumer.from(consumerGroupName, consumerName),
                StreamOffset.create(streamName, ReadOffset.lastConsumed()),
                this);
   ```
   5. Implementing DisposableBean to override the destroy method to cancel the subscription and stop the message listener container.

5. Creating a scheduler to fetch pending messages and claim it to process it
   Class : com.redisstream.kickstart.scheduler.PendingMessageScheduler
   1. Reading the pending messages :
   ```java
      PendingMessages messages = redisTemplate.opsForStream().pending(streamName,
                consumerGroupName, Range.unbounded(), MAX_NUMBER_FETCH);
   ```
   2. Claiming the pending messages
   ```java
     for (PendingMessage message : messages) { 
      RedisAsyncCommands commands = (RedisAsyncCommands) redisTemplate.getConnectionFactory().getConnection().getNativeConnection();
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(streamName)
                .add(consumerGroupName)
                .add(consumerName)
                .add("20") //idle time , message will only be claimed if it has been idle by 20 ms
                .add(pendingMessage.getIdAsString());
        commands.dispatch(CommandType.XCLAIM, new StatusOutput<>(StringCodec.UTF8), args);
     }
   ```
   3. Processing the messages
   ```java
     for (PendingMessage message : messages) { 
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
                redisTemplate.opsForHash().put(config.getRecordCacheKey(), LAST_RESULT_HASH_KEY, number);
                redisTemplate.opsForHash().increment(config.getRecordCacheKey(), PROCESSED_HASH_KEY, 1);
                redisTemplate.opsForHash().increment(config.getRecordCacheKey(), RETRY_PROCESSED_HASH_KEY, 1);
                redisTemplate.opsForStream().acknowledge(config.getConsumerGroupName(), message);
                log.info("Message has been processed after retrying");
            } catch (Exception ex) {
                //log the exception and increment the number of errors count
                log.error("Failed to process the message: {} ", messagesToProcess.get(0).getValue().get(NUMBER_KEY), ex);
                redisTemplate.opsForHash().increment(config.getRecordCacheKey(), ERRORS_HASH_KEY, 1);
            }
        }
     }
   ```
   
