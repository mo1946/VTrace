package com.vtrace.business.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses rows created by scripts/seed_mysql_fixture.py through the actual Python ORM. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"vtrace.redis.enabled=false", "vtrace.jwt-secret=vtrace-stage2-test-secret-at-least-32-bytes"})
@EnabledIfEnvironmentVariable(named = "VTRACE_TEST_MYSQL_URL", matches = "jdbc:mysql:.*")
class AnalysisTaskMySqlIntegrationTest {
    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper objectMapper;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("VTRACE_TEST_MYSQL_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("VTRACE_TEST_MYSQL_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("VTRACE_TEST_MYSQL_PASSWORD"));
    }

    @ParameterizedTest
    @CsvSource({"1,QUEUED,0", "2,PROCESSING,1", "3,RETRYING,2", "4,COMPLETED,1", "5,FAILED,3"})
    void readsPythonCreatedTaskStates(int number, String state, int attempts) throws Exception {
        HttpResponse<String> response = get("?taskId=" + taskId(number));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("taskId").asText()).isEqualTo(taskId(number));
        assertThat(body.path("state").asText()).isEqualTo(state);
        assertThat(body.path("attemptCount").asInt()).isEqualTo(attempts);
        assertThat(body.path("mediaId").asInt()).isPositive();
        assertThat(body.path("createdAt").asText()).startsWith("2026-07-21T10:20:30");
        if (number == 4) {
            assertThat(body.path("report").asText()).isEqualTo("# Python 报告\n\n带时间戳的证据总结。");
        } else {
            assertThat(body.path("report").isNull()).isTrue();
        }
        assertThat(body.has("trace")).isFalse();
    }

    @Test
    void readsChineseProgressAndMysqlDatetimeWithoutMutatingTask() throws Exception {
        JsonNode first = objectMapper.readTree(get("?taskId=" + taskId(2)).body());
        assertThat(first.path("stage").asText()).isEqualTo("OCR");
        assertThat(first.path("message").asText()).isEqualTo("正在识别关键帧（3/8 帧）");
        assertThat(first.path("progressCurrent").asInt()).isEqualTo(3);
        assertThat(first.path("progressTotal").asInt()).isEqualTo(8);
        assertThat(first.path("progressPercent").asInt()).isEqualTo(38);
        assertThat(first.path("error").isNull()).isTrue();
        assertThat(first.path("updatedAt").asText()).isEqualTo("2026-07-21T10:21:30");
        JsonNode second = objectMapper.readTree(get("?taskId=" + taskId(2)).body());
        assertThat(second).isEqualTo(first);
    }

    @Test
    void handlesNullableLegacyStageAndFailureMessage() throws Exception {
        JsonNode queued = objectMapper.readTree(get("?taskId=" + taskId(1)).body());
        assertThat(queued.path("stage").isNull()).isTrue();
        assertThat(queued.path("progressPercent").asInt()).isZero();
        assertThat(queued.path("message").asText()).isEqualTo("任务已排队");
        JsonNode failed = objectMapper.readTree(get("?taskId=" + taskId(5)).body());
        assertThat(failed.path("message").asText()).isEqualTo("RuntimeError: 模型暂时不可用");
    }

    @Test
    void nonexistentTaskReturns404() throws Exception {
        HttpResponse<String> response = get("?taskId=" + taskId(9));
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(response.body()).path("detail").asText())
                .isEqualTo("分析任务不存在");
    }

    @Test
    void missingAndBlankTaskIdsReturn400() throws Exception {
        assertThat(get("").statusCode()).isEqualTo(400);
        assertThat(get("?taskId=").statusCode()).isEqualTo(400);
    }

    @Test
    void treatsSqlSyntaxAsAnIdInsteadOfExecutingIt() throws Exception {
        assertThat(get("?taskId=%27%20OR%201%3D1%20--").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String query) throws Exception {
        JsonNode fixture = objectMapper.readTree(Files.readString(Path.of(
                System.getenv().getOrDefault("VTRACE_TEST_FIXTURE_FILE", "target/python-fixture.json"))));
        return client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/analysis/task-status" + query))
                .header("Authorization", "Bearer " + fixture.path("token").asText())
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String taskId(int number) {
        return "00000000-0000-4000-8000-00000000000" + number;
    }
}
