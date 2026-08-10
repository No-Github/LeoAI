package org.leo.service.project;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Project;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.dao.mapper.ProjectMapper;
import org.leo.service.PuppetService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectServiceTest {

    @Test
    void inheritsProjectScopeFromAttachedAncestorPuppet() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        PuppetService puppetService = mock(PuppetService.class);
        ProjectService service = new ProjectService(mapper, puppetService);

        Puppet child = new Puppet();
        child.setPuppetId("child");
        child.setParentPuppetId("gateway");
        when(mapper.containsPuppet("project-1", "child")).thenReturn(0);
        when(mapper.containsPuppet("project-1", "gateway")).thenReturn(1);
        when(puppetService.findPuppetById("child")).thenReturn(child);

        assertTrue(service.containsPuppet("project-1", "child"));
    }

    @Test
    void resolvesChildSelectionToItsEntryPuppet() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        PuppetService puppetService = mock(PuppetService.class);
        ProjectService service = new ProjectService(mapper, puppetService);

        Puppet child = puppet("child", "gateway");
        Puppet gateway = puppet("gateway", "root");
        when(puppetService.findPuppetById("child")).thenReturn(child);
        when(puppetService.findPuppetById("gateway")).thenReturn(gateway);

        assertEquals("gateway", service.resolveEntryPuppetId("child"));
    }

    @Test
    void exposesEntryProjectMembershipsOnChildNodes() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        PuppetService puppetService = mock(PuppetService.class);
        ProjectService service = new ProjectService(mapper, puppetService);
        User user = new User();
        user.setUserId("user-1");
        user.setPrivilege("normal");
        Project visible = project("user-1", null, "private");
        visible.setProjectId("project-1");

        when(puppetService.findPuppetById("child")).thenReturn(puppet("child", "gateway"));
        when(puppetService.findPuppetById("gateway")).thenReturn(puppet("gateway", "root"));
        when(mapper.findProjectsByPuppetId("gateway")).thenReturn(List.of(visible));

        Map<String, List<Project>> result = service.listVisibleMemberships(List.of("child"), user);

        assertEquals(List.of(visible), result.get("child"));
    }

    @Test
    void appliesOwnerAndTeamVisibilityWithoutGrantingPrivateProjects() {
        ProjectService service = new ProjectService(
                mock(ProjectMapper.class), mock(PuppetService.class));
        User user = new User();
        user.setUserId("user-1");
        user.setTeamId("team-1");
        user.setPrivilege("normal");

        Project owned = project("user-1", null, "private");
        Project team = project("user-2", "team-1", "team");
        Project privateProject = project("user-2", "team-1", "private");

        assertTrue(service.canView(owned, user));
        assertTrue(service.canView(team, user));
        assertFalse(service.canView(privateProject, user));
    }

    @Test
    void separatesPublicVisibilityFromProjectContentEditing() {
        ProjectService service = new ProjectService(
                mock(ProjectMapper.class), mock(PuppetService.class));
        User user = new User();
        user.setUserId("user-1");
        user.setTeamId("team-1");
        user.setPrivilege("normal");

        Project publicProject = project("user-2", "team-2", "public");
        Project teamProject = project("user-2", "team-1", "team");
        Project ownedProject = project("user-1", null, "private");

        assertTrue(service.canView(publicProject, user));
        assertFalse(service.canEditContent(publicProject, user));
        assertTrue(service.canEditContent(teamProject, user));
        assertTrue(service.canEditContent(ownedProject, user));
    }

    private static Project project(String owner, String teamId, String permission) {
        Project project = new Project();
        project.setOwnerUserId(owner);
        project.setTeamId(teamId);
        project.setPermission(permission);
        return project;
    }

    private static Puppet puppet(String puppetId, String parentPuppetId) {
        Puppet puppet = new Puppet();
        puppet.setPuppetId(puppetId);
        puppet.setParentPuppetId(parentPuppetId);
        return puppet;
    }
}
