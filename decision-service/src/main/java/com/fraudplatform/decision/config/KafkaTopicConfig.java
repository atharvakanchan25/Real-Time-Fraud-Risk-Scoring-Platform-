package com.fraudplatform.decision.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableConfigurationProperties(DecisionThresholds.class)
public class KafkaTopicConfig {

    @Bean
    public NewTopic finalDecisionsTopic() {
        return TopicBuilder.name("final-decisions").partitions(6).replicas(1).build();
    }
}
