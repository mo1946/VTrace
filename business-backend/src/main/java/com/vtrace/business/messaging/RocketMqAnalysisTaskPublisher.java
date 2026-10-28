package com.vtrace.business.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RocketMqAnalysisTaskPublisher implements AnalysisTaskPublisher {
    private static final Logger log = LoggerFactory.getLogger(RocketMqAnalysisTaskPublisher.class);
    private final ObjectMapper json;
    private final String nameserver;
    private final String topic;
    private final String group;
    private final int timeout;
    private DefaultMQProducer producer;

    public RocketMqAnalysisTaskPublisher(ObjectMapper json,
            @Value("${vtrace.rocketmq.nameserver}") String nameserver,
            @Value("${vtrace.rocketmq.topic}") String topic,
            @Value("${vtrace.rocketmq.producer-group}") String group,
            @Value("${vtrace.rocketmq.send-timeout-ms}") int timeout) {
        this.json = json;
        this.nameserver = nameserver;
        this.topic = topic;
        this.group = group;
        this.timeout = timeout;
    }

    // Serialize lifecycle/send for this small MVP; prevents close/start races.
    @Override
    public synchronized boolean publish(String taskId) {
        if (nameserver.isBlank()) {
            log.warn("rocketmq_unconfigured task_id={} delivery=PENDING", taskId);
            return false;
        }
        try {
            if (producer == null) {
                DefaultMQProducer candidate = new DefaultMQProducer(group);
                candidate.setNamesrvAddr(nameserver);
                candidate.setSendMsgTimeout(timeout);
                candidate.setRetryTimesWhenSendFailed(2);
                try {
                    candidate.start();
                } catch (Exception exception) {
                    candidate.shutdown();
                    throw exception;
                }
                producer = candidate;
            }
            var result = producer.send(message(taskId), timeout);
            boolean sent = result.getSendStatus() == SendStatus.SEND_OK;
            log.info("analysis_message_dispatch task_id={} status={} message_id={}",
                    taskId, result.getSendStatus(), result.getMsgId());
            return sent;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("rocketmq_publish_interrupted task_id={} delivery=PENDING", taskId);
            return false;
        } catch (Exception exception) {
            log.warn("rocketmq_publish_failed task_id={} delivery=PENDING error={} reason={}",
                    taskId, exception.getClass().getSimpleName(), exception.getMessage());
            return false;
        }
    }

    Message message(String taskId) throws JsonProcessingException {
        return new Message(topic, "analysis", taskId,
                json.writeValueAsString(Map.of("type", "analysis", "taskId", taskId))
                        .getBytes(StandardCharsets.UTF_8));
    }

    @PreDestroy
    public synchronized void close() {
        if (producer != null) producer.shutdown();
    }
}
