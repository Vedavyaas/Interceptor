package com.pheonix.interceptor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pheonix.interceptor.model.EventPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaMessageController {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageController.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaMessageController(
            @Qualifier("interceptorKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper  = objectMapper;
    }

    /**
     * Serializes {@code payload} to JSON and publishes it to the interceptor
     * metrics topic.
     *
     * @param payload the {@link EventPayload} to publish
     */
    public void sendMessage(EventPayload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(KafkaProducerConfig.METRICS_TOPIC, json);
        } catch (JsonProcessingException e) {
            log.error("KafkaMessageController: failed to serialize EventPayload", e);
        }
    }
}
