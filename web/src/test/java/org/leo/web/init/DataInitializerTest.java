package org.leo.web.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.entity.User;
import org.leo.core.util.PasswordUtil;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DataInitializerTest {

    @TempDir
    Path tempDir;

    @Test
    void createsRandomBootstrapPasswordFileWhenNoPasswordIsConfigured() throws Exception {
        UserService users = mock(UserService.class);
        TeamService teams = mock(TeamService.class);
        Path passwordFile = tempDir.resolve("initial-admin-password");
        DataInitializer initializer = new DataInitializer(users, teams, "", passwordFile.toString());

        initializer.init();

        String generatedPassword = Files.readString(passwordFile).trim();
        ArgumentCaptor<User> adminCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).addUser(adminCaptor.capture());
        assertTrue(generatedPassword.length() >= 12);
        assertTrue(PasswordUtil.verify(generatedPassword, adminCaptor.getValue().getPassword()));
    }
}
