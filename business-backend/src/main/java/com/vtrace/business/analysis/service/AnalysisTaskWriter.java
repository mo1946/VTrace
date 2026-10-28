package com.vtrace.business.analysis.service;

import com.vtrace.business.analysis.mapper.AnalysisTaskMapper;
import com.vtrace.business.analysis.model.NewAnalysisTask;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Separate bean so the transaction commits before the caller publishes to RocketMQ. */
@Service
public class AnalysisTaskWriter {
    private final AnalysisTaskMapper mapper;

    public AnalysisTaskWriter(AnalysisTaskMapper mapper) { this.mapper = mapper; }

    @Transactional
    public void create(NewAnalysisTask task) {
        if (mapper.findOwnedMedia(task.mediaId(), task.userId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体不存在");
        }
        String active = mapper.findActiveTask(task.mediaId(), task.goalHash());
        if (active != null) throw new DuplicateTaskException(active);
        mapper.insertTask(task);
        if (mapper.markMediaProcessing(task.mediaId(), task.userId()) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体不存在");
        }
    }
}
