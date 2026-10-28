package com.vtrace.business.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtrace.business.analysis.mapper.AnalysisTaskMapper;
import com.vtrace.business.analysis.model.NewAnalysisTask;
import com.vtrace.business.analysis.service.AnalysisTaskWriter;
import com.vtrace.business.analysis.service.AnalysisTaskSubmissionService;
import com.vtrace.business.messaging.AnalysisTaskPublisher;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"vtrace.redis.enabled=false", "vtrace.jwt-secret=vtrace-stage2-test-secret-at-least-32-bytes"})
@EnabledIfEnvironmentVariable(named = "VTRACE_TEST_MYSQL_URL", matches = "jdbc:mysql:.*")
class AnalysisTaskCreationMySqlIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisTaskWriter writer;
    @MockitoBean AnalysisTaskPublisher publisher;
    @MockitoSpyBean AnalysisTaskMapper mapper;
    JsonNode fixture;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("VTRACE_TEST_MYSQL_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("VTRACE_TEST_MYSQL_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("VTRACE_TEST_MYSQL_PASSWORD"));
    }

    @BeforeEach
    void fixtureAndPublisher() throws Exception {
        fixture = json.readTree(Files.readString(Path.of(System.getenv()
                .getOrDefault("VTRACE_TEST_FIXTURE_FILE", "target/python-fixture.json"))));
        when(publisher.publish(anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(mapper.findById(call.getArgument(0))).isNotNull();
            return true;
        });
    }

    @Test
    void createsPythonCompatibleRowAndPublishesOnlyAfterCommit() throws Exception {
        String goal = "总结视频 " + UUID.randomUUID();
        String hash = AnalysisTaskSubmissionService.goalHash(goal);
        assertThat(AnalysisTaskSubmissionService.goalHash("总结视频")).isEqualTo(fixture.path("hashExample").asText());
        var response = post("\u00a0" + goal + "\u3000", token());
        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("dispatchStatus").asText()).isEqualTo("SENT");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM analysis_tasks WHERE id=?", body.path("taskId").asText());
        assertThat(row.get("goal")).isEqualTo(goal);
        assertThat(row.get("goal_hash")).isEqualTo(hash);
        assertThat(row.get("active_key")).isEqualTo(mediaId() + ":" + hash);
        assertThat(row.get("state")).isEqualTo("QUEUED");
        assertThat(row.get("stage")).isEqualTo("QUEUED");
        assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(row.get("max_attempts")).isEqualTo(3);
        assertThat(row.get("plan_json")).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=?", String.class, mediaId()))
                .isEqualTo("PROCESSING");
    }

    @Test
    void duplicateReturnsExistingTaskWithoutRepublishing() throws Exception {
        String goal = "duplicate-" + UUID.randomUUID();
        JsonNode first = json.readTree(post(goal, token()).body());
        var second = post(goal, token());
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(json.readTree(second.body()).path("taskId").asText()).isEqualTo(first.path("taskId").asText());
        verify(publisher, times(1)).publish(anyString());
    }

    @Test
    void mysqlUniqueConstraintRejectsConcurrentSubmissionsWithoutRedis() throws Exception {
        String goal = "concurrent-" + UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int i = 0; i < 6; i++) responses.add(pool.submit(() -> {
                start.await();
                return post(goal, token()).statusCode();
            }));
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (var response : responses) codes.add(response.get(20, TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(202, 409, 409, 409, 409, 409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_tasks WHERE goal=?", Integer.class, goal)).isEqualTo(1);
        verify(publisher, times(1)).publish(anyString());
    }

    @Test
    void mqFailureRetainsQueuedTaskAndDuplicateStillConflicts() throws Exception {
        doReturn(false).when(publisher).publish(anyString());
        String goal = "mq-failure-" + UUID.randomUUID();
        var response = post(goal, token());
        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("dispatchStatus").asText()).isEqualTo("PENDING");
        assertThat(mapper.findById(body.path("taskId").asText()).state()).isEqualTo("QUEUED");
        assertThat(post(goal, token()).statusCode()).isEqualTo(409);
    }

    @Test
    void failedMediaUpdateRollsBackTaskInsertion() {
        String id = UUID.randomUUID().toString();
        doReturn(0).when(mapper).markMediaProcessing(mediaId(), fixture.path("userId").asInt());
        assertThatThrownBy(() -> writer.create(new NewAnalysisTask(id, mediaId(), fixture.path("userId").asInt(),
                "rollback", "f".repeat(64), mediaId() + ":" + id, 3, LocalDateTime.now())))
                .isInstanceOf(RuntimeException.class);
        assertThat(mapper.findById(id)).isNull();
        verifyNoInteractions(publisher);
    }

    @Test
    void enforcesPythonTokenAndUserOwnership() throws Exception {
        assertThat(post("auth", null).statusCode()).isEqualTo(401);
        assertThat(post("auth", fixture.path("disabledToken").asText()).statusCode()).isEqualTo(401);
        assertThat(post("auth", fixture.path("otherToken").asText()).statusCode()).isEqualTo(404);
        var request = HttpRequest.newBuilder(URI.create(base() + "/analysis/task-status?taskId=00000000-0000-4000-8000-000000000001"))
                .header("Authorization", "Bearer " + fixture.path("otherToken").asText()).GET().build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        verifyNoInteractions(publisher);
    }

    @Test
    void validatesBlankTooLongAndMissingId() throws Exception {
        assertThat(post(" ", token()).statusCode()).isEqualTo(400);
        assertThat(post("字".repeat(501), token()).statusCode()).isEqualTo(422);
        var request = HttpRequest.newBuilder(URI.create(base() + "/analysis/ai"))
                .header("Authorization", "Bearer " + token()).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(422);
        verifyNoInteractions(publisher);
    }

    private String token() { return fixture.path("token").asText(); }
    private int mediaId() { return fixture.path("mediaId").asInt(); }
    private String base() { return "http://127.0.0.1:" + port; }
    private HttpResponse<String> post(String goal, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base() + "/analysis/ai?id=" + mediaId()
                + "&goal=" + URLEncoder.encode(goal, StandardCharsets.UTF_8)));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
