# redis-stream-kickstart (Consumer side Example)

## Overview
The following repo shows how to create redis stream consumer using spring data redis and process it.
In this simple use case, we are consuming a number which is being added into redis stream (use redis-cli to add element into stream as mentioned down the document), consumer will read the message from the stream, identitfy if is even or odd or error and will add in respective lists and hashes as record. Idea here to show main redis stream concepts like:
  1. Consumer group and processing the message successfully
  2. Pending messages (if message is not proccessed or acknowledged) (to be implemented)
  3. Claiming the messages (if any of the consumer goes down permanently or claim the message if consumer does not come up after specified time) (to be implmented)

## Guidelines
Befor cloning this repository and running it, please keep your redis server up.

1. Clone this repository
2. You can run this application by creating docker image of the application locally.
3. Before creating image, please update the redis host and port in application.properties.
4. To create the docker image go inside the folder redis-stream-kickstart and run below command
```docker
docker build -t redis-stream-example .
docker images
docker run -p 8082:8082 -d redis-stream-exampl
```
5. Else, open the project and make the gradle build to run the applicaiton
6. Once the application is started, it would have created the consumer group and stream if it did not exit.

## Source Code Review
1. Create the redis connecton using the lettuce connection factory by providing the redis host and port
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
        redisTemplate.setHashValueSerializer(new Jackson2JsonRedisSerializer<>(String.class));
        redisTemplate.setHashKeySerializer(new Jackson2JsonRedisSerializer<>(Integer.class));
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }
```
3. Creating stream consumer class
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
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofMillis(config.getStreamPollTimeout())).build());
   ```
   4. Subcribing the listener
   Here we have mentioned the consumerName (to idetify the consumer uniquely in consumer group) and read strategy to consume data from the last consumed offset.
   ```java
    this.subscription = listenerContainer.receive(
                Consumer.from(consumerGroupName, consumerName),
                StreamOffset.create(streamName, ReadOffset.lastConsumed()),
                this);
   ```
   5. Implementing DisposableBean
    override the destroy method to cancel the subscription and stop the message listener container.
   
