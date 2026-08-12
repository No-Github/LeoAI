package org.leo.web.service;

import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.core.ai.AiTurnRuntime;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/** Coordinates the database lease acquired after an in-process turn claim. */
@Service
public class AiExecutionClaimService {

    private final AiTurnCoordinator turnCoordinator;
    private final AiExecutionLeaseService executionLeaseService;

    public AiExecutionClaimService(AiTurnCoordinator turnCoordinator,
                                   AiExecutionLeaseService executionLeaseService) {
        this.turnCoordinator = turnCoordinator;
        this.executionLeaseService = executionLeaseService;
    }

    /**
     * Acquires the cross-instance lease after the caller has claimed the runtime.
     * A failed or exceptional lease acquisition always releases that local claim.
     */
    public String tryAcquireAfterClaim(AiTurnRuntime runtime,
                                       String threadId,
                                       Runnable onLeaseLost,
                                       Consumer<RuntimeException> onFailure) {
        try {
            String leaseToken = executionLeaseService.tryAcquireToken(
                    threadId, onLeaseLost);
            if (leaseToken != null) return leaseToken;
        } catch (RuntimeException error) {
            turnCoordinator.releaseClaim(runtime);
            if (onFailure != null) onFailure.accept(error);
            return null;
        }
        turnCoordinator.releaseClaim(runtime);
        return null;
    }

    public void release(String threadId) {
        executionLeaseService.release(threadId);
    }

}
