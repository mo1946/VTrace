package com.vtrace.business.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RocketMqMessageContractTest {
    @Test
    void preservesPythonTopicTagKeyAndUtf8Body() throws Exception {
        var json = new ObjectMapper();
        var publisher = new RocketMqAnalysisTaskPublisher(json, "", "video-analysis-topic", "test", 1000);
        var message = publisher.message("task-id");
        assertThat(message.getTopic()).isEqualTo("video-analysis-topic");
        assertThat(message.getTags()).isEqualTo("analysis");
        assertThat(message.getKeys()).isEqualTo("task-id");
        assertThat(json.readTree(new String(message.getBody(), StandardCharsets.UTF_8)))
                .isEqualTo(json.readTree("{\"type\":\"analysis\",\"taskId\":\"task-id\"}"));
        assertThat(publisher.publish("task-id")).isFalse();
    }
}
