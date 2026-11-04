package com.vidacruz.triage.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** triage.kafka.*: topic y version del envelope de eventos. */
@ConfigurationProperties("triage.kafka")
public record TriageKafkaProperties(String topic, int eventVersion) {
}
