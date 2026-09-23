package com.fraudplatform.scoring.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic decisionEventsTopic() {
        return TopicBuilder.name("decision-events").partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic paymentEventsDlqTopic() {
        return TopicBuilder.name("payment-events-dlq").partitions(6).replicas(1).build();
    }

    /**
     * Retry 3 times with a 1-second interval, then publish to the DLQ topic.
     * Spring Kafka's DeadLetterPublishingRecoverer routes to "{topic}.DLT" by default,
     * but we override the destination to our explicit "payment-events-dlq" topic.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(
                        "payment-events-dlq", record.partition()));

        // 3 retries, 1 s apart — then hand off to the DLQ recoverer
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3));
    }
}
