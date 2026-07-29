package org.leo.core.puppet.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserDataServiceTest {

    @Test
    void scansWindowsProfilesFromUserProfileInsteadOfUnixHomeVariable() throws Exception {
        StubBrowserDataService service = new StubBrowserDataService();

        Map<String, Object> response = service.scanProfiles();

        assertEquals(200, response.get("code"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals("windows", data.get("os"));
        assertEquals(1, data.get("total"));
        Map<?, ?> profile = (Map<?, ?>) ((List<?>) data.get("profiles")).get(0);
        assertEquals("chrome", profile.get("browser"));
        assertEquals("Default", profile.get("profileName"));
        assertEquals(
                "C:\\Users\\Alice\\AppData\\Local\\Google\\Chrome\\User Data\\Default",
                profile.get("path"));
        assertTrue(service.commands.stream()
                .anyMatch(command -> command.contains("echo %USERPROFILE%")));
    }

    private static final class StubBrowserDataService extends BrowserDataService {

        private final List<String> commands = new ArrayList<>();

        private StubBrowserDataService() {
            super(null, new ArrayList<>(), new ArrayList<>());
        }

        @Override
        protected String execFast(String command) {
            commands.add(command);
            if (command.startsWith("uname -s")) {
                return "Windows";
            }
            if (command.contains("echo %USERPROFILE%")) {
                return "C:\\Users\\Alice\r\n";
            }
            if (command.contains("dir /b")
                    && command.contains("\\Google\\Chrome\\User Data")) {
                return "Default\r\n";
            }
            if (command.contains("if exist")
                    && command.contains("\\Google\\Chrome\\User Data\\Default\\Preferences")) {
                return "1\r\n";
            }
            return "";
        }
    }
}
