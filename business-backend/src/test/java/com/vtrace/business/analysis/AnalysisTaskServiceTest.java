package com.vtrace.business.analysis;

import com.vtrace.business.analysis.mapper.AnalysisTaskMapper;
import com.vtrace.business.analysis.model.AnalysisTask;
import com.vtrace.business.analysis.service.AnalysisTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AnalysisTaskServiceTest {
    private final AnalysisTaskMapper mapper = mock(AnalysisTaskMapper.class);
    private final AnalysisTaskService service = new AnalysisTaskService(mapper);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "1234567890123456789012345678901234567"})
    void rejectsInvalidIdsBeforeQuerying(String taskId) {
        assertThatThrownBy(() -> service.getStatus(taskId, 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(mapper);
    }

    @Test
    void missingTaskReturns404() {
        assertThatThrownBy(() -> service.getStatus("missing", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @ParameterizedTest
    @CsvSource({"1,8,12", "3,8,38", "15,10,100", "-1,10,0", "1,0,0"})
    void progressMatchesPythonRoundingAndBounds(int current, int total, int expected) {
        when(mapper.findByIdForUser("task", 1)).thenReturn(new AnalysisTask("task", 1, "PROCESSING", "ASR",
                1, current, total, null, null, null, null, null));
        assertThat(service.getStatus("task", 1).progressPercent()).isEqualTo(expected);
    }
}
