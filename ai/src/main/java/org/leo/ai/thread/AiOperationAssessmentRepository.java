package org.leo.ai.thread;

import org.leo.core.entity.AiOperationAssessment;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AiOperationAssessmentRepository {
    private final AiConversationMapper mapper;

    public AiOperationAssessmentRepository(AiConversationMapper mapper) {
        this.mapper = mapper;
    }

    public AiOperationAssessment findPending(String userId, String threadId,
                                             String toolName, String argumentsHash) {
        long now = System.currentTimeMillis();
        mapper.expireOperationAssessments(now);
        return mapper.findPendingOperationAssessment(userId, threadId, toolName, argumentsHash, now);
    }

    public AiOperationAssessment create(AiOperationAssessment assessment) {
        if (mapper.insertOperationAssessment(assessment) != 1) {
            throw new IllegalStateException("创建操作评估失败");
        }
        return assessment;
    }

    public boolean consume(String assessmentId, long consumedAt) {
        return mapper.consumeOperationAssessment(assessmentId, consumedAt) == 1;
    }
}
