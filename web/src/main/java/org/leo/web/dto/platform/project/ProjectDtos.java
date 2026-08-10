package org.leo.web.dto.platform.project;

import org.leo.core.entity.Project;

import java.util.List;

public final class ProjectDtos {
    private ProjectDtos() {}

    public record ProjectSummary(Project project, int hostCount,
                                 int activeSessionCount, boolean manageable,
                                 boolean contentEditable) {}

    public record ProjectIdRequest(String projectId) {}

    public record ProjectPuppetsRequest(
            String projectId,
            List<String> puppetIds,
            String alias,
            String environment,
            String tags
    ) {}

    public record ProjectChildrenRequest(String parentPuppetId) {}

    public record PuppetMembershipsRequest(List<String> puppetIds) {}

    public record ProjectMembershipSummary(String projectId, String projectName,
                                            String projectCode, String status) {
        public ProjectMembershipSummary(Project project) {
            this(project.getProjectId(), project.getProjectName(),
                    project.getProjectCode(), project.getStatus());
        }
    }

    public record RelationMutationResponse(String projectId, int changedCount) {}
}
