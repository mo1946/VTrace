package com.vtrace.business.common;

import java.util.Map;
import com.vtrace.business.analysis.service.DuplicateTaskException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException exception) {
        var response = ResponseEntity.status(exception.getStatusCode());
        if (exception.getStatusCode().value() == 429) response.header("Retry-After", "3600");
        return response.body(Map.of("detail", exception.getReason() == null ? "请求失败" : exception.getReason()));
    }

    @ExceptionHandler(DuplicateTaskException.class)
    public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateTaskException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.taskId() == null
                ? Map.of("message", exception.getMessage())
                : Map.of("message", exception.getMessage(), "taskId", exception.taskId()));
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ResponseEntity<Map<String, String>> handleDatabase(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", "任务数据库暂时不可用"));
    }
}
