package com.vtrace.business.analysis;

import com.vtrace.business.analysis.mapper.AnalysisTaskMapper;
import com.vtrace.business.analysis.model.NewAnalysisTask;
import com.vtrace.business.analysis.service.*;
import com.vtrace.business.infrastructure.RedisTaskGuard;
import com.vtrace.business.messaging.AnalysisTaskPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisTaskSubmissionServiceTest {
    private final AnalysisTaskMapper mapper = mock(AnalysisTaskMapper.class);
    private final AnalysisTaskWriter writer = mock(AnalysisTaskWriter.class);
    private final RedisTaskGuard guard = mock(RedisTaskGuard.class);
    private final AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
    private final AnalysisTaskSubmissionService service = new AnalysisTaskSubmissionService(mapper, writer, guard, publisher, 3);

    @Test
    void normalizesPythonWhitespaceAndCountsUnicodeCodePoints() {
        assertThat(AnalysisTaskSubmissionService.normalizeGoal("\u0085\u00a0总结视频\u3000")).isEqualTo("总结视频");
        assertThat(AnalysisTaskSubmissionService.normalizeGoal(null)).isEqualTo(AnalysisTaskSubmissionService.DEFAULT_GOAL);
        assertThat(AnalysisTaskSubmissionService.normalizeGoal("😀".repeat(500))).hasSize(1000);
        assertThatThrownBy(() -> AnalysisTaskSubmissionService.normalizeGoal("😀".repeat(501)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(422));
        assertThatThrownBy(() -> AnalysisTaskSubmissionService.normalizeGoal("\u00a0 "))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void doesNotPublishOrAcquireLockForAnUnownedMedia() {
        when(mapper.findOwnedMedia(2, 1)).thenReturn(null);
        assertThatThrownBy(() -> service.submit(1, "2", "目标"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        verify(guard, never()).acquire(anyString(), anyString());
        verifyNoInteractions(writer, publisher);
    }

    @Test
    void retainsCommittedTaskAndLockWhenMqFails() {
        when(mapper.findOwnedMedia(2, 1)).thenReturn(2);
        when(guard.acquire(anyString(), anyString())).thenReturn(RedisTaskGuard.Acquisition.ACQUIRED);
        var result = service.submit(1, "2", "  总结视频  ");
        assertThat(result.dispatchStatus()).isEqualTo("PENDING");
        var task = ArgumentCaptor.forClass(NewAnalysisTask.class);
        var order = inOrder(writer, publisher);
        order.verify(writer).create(task.capture());
        order.verify(publisher).publish(result.taskId());
        assertThat(task.getValue().goal()).isEqualTo("总结视频");
        assertThat(task.getValue().activeKey()).isEqualTo("2:" + AnalysisTaskSubmissionService.goalHash("总结视频"));
        verify(guard, never()).release(anyString(), anyString());
    }

    @Test
    void releasesOnlyOwnAcquiredLockOnRollback() {
        when(mapper.findOwnedMedia(2, 1)).thenReturn(2);
        when(guard.acquire(anyString(), anyString())).thenReturn(RedisTaskGuard.Acquisition.ACQUIRED);
        doThrow(new IllegalStateException("rollback")).when(writer).create(any());
        assertThatThrownBy(() -> service.submit(1, "2", "目标")).isInstanceOf(IllegalStateException.class);
        verify(guard).release(anyString(), anyString());
        verifyNoInteractions(publisher);
    }

    @Test
    void redisBusyRejectsBeforeWriting() {
        when(mapper.findOwnedMedia(2, 1)).thenReturn(2);
        when(guard.acquire(anyString(), anyString())).thenReturn(RedisTaskGuard.Acquisition.BUSY);
        assertThatThrownBy(() -> service.submit(1, "2", "目标")).isInstanceOf(DuplicateTaskException.class);
        verifyNoInteractions(writer, publisher);
        verify(guard, never()).release(anyString(), anyString());
    }
}
