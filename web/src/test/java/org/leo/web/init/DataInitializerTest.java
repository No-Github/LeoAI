package org.leo.web.init;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.core.util.PasswordUtil;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DataInitializerTest {

    @Test
    void createsAdminWithFixedInitialPassword() throws Exception {
        UserService users = mock(UserService.class);
        TeamService teams = mock(TeamService.class);
        DataInitializer initializer = new DataInitializer(users, teams);

        initializer.init();

        ArgumentCaptor<User> adminCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).addUser(adminCaptor.capture());
        assertTrue(PasswordUtil.verify("54ikun", adminCaptor.getValue().getPassword()));
        assertEquals(1, adminCaptor.getValue().getPasswordChangeRequired());
    }
}
