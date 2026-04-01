package com.ticketing.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic ticketReserved() {
        return TopicBuilder.name(KafkaTopics.TICKET_RESERVED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic ticketCanceled() {
        return TopicBuilder.name(KafkaTopics.TICKET_CANCELED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic queueEnter() {
        return TopicBuilder.name(KafkaTopics.QUEUE_ENTER).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentRequested() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_REQUESTED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentSucceeded() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_SUCCEEDED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailed() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_FAILED).partitions(3).replicas(1).build();
    }
}
