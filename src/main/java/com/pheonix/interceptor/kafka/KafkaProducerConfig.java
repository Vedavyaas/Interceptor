package com.pheonix.interceptor.kafka;


import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    public static final String METRICS_TOPIC = "cloud_metrics";

    private static final String BOOTSTRAP_SERVERS = "localhost:9092";

    @Bean(name = "interceptorProducerFactory")
    public ProducerFactory<String, String> interceptorProducerFactory() {
        Map<String, Object> config = new HashMap<>();

        // ── Connection ───────────────────────────────────────────────────────
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        // ── Serializers ──────────────────────────────────────────────────────
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // ── Reliability (constant — not overridable by the host app) ─────────
        config.put(ProducerConfig.ACKS_CONFIG,              "all");   // wait for all ISR replicas
        config.put(ProducerConfig.RETRIES_CONFIG,           3);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);   // exactly-once producer
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);

        // ── Throughput ───────────────────────────────────────────────────────
        config.put(ProducerConfig.LINGER_MS_CONFIG,        5);        // small batch window (ms)
        config.put(ProducerConfig.BATCH_SIZE_CONFIG,       16_384);   // 16 KB batch
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy"); // lightweight compression

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean(name = "interceptorKafkaTemplate")
    public KafkaTemplate<String, String> interceptorKafkaTemplate(
            @Qualifier("interceptorProducerFactory") ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }
}
