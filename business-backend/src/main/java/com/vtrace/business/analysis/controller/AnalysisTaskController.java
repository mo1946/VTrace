package com.vtrace.business.analysis.controller;

import com.vtrace.business.analysis.dto.TaskStatusResponse;
import com.vtrace.business.analysis.dto.TaskSubmissionResponse;
import com.vtrace.business.analysis.service.AnalysisTaskSubmissionService;
import com.vtrace.business.analysis.service.AnalysisTaskService;
import com.vtrace.business.auth.PythonTokenAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalysisTaskController {
    private final AnalysisTaskService service;
    private final AnalysisTaskSubmissionService submissions;
    private final PythonTokenAuthenticator authenticator;

    public AnalysisTaskController(AnalysisTaskService service, AnalysisTaskSubmissionService submissions,
                                  PythonTokenAuthenticator authenticator) {
        this.service = service;
        this.submissions = submissions;
        this.authenticator = authenticator;
    }

    @GetMapping("/analysis/task-status")
    public TaskStatusResponse getStatus(@RequestParam("taskId") String taskId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.getStatus(taskId, authenticator.authenticate(authorization));
    }

    @PostMapping("/analysis/ai")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskSubmissionResponse submit(
            @RequestParam(value = "id", required = false) String mediaId,
            @RequestParam(value = "goal", required = false) String goal,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return submissions.submit(authenticator.authenticate(authorization), mediaId, goal);
    }
}
