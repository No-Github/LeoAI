package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.leo.core.util.json.PortableJsonCodec;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpBasicInfoComponentTest {

    @Test
    void collectsResourceFileSystemAndNetworkData() throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
        URL resource = Objects.requireNonNull(getClass().getResource("/components/BasicInfoComponent.php"));
        Path component = Paths.get(resource.toURI());
        String script = "$component=require $argv[1];"
                + "echo json_encode(call_user_func($component['handle'],'get',array()));";
        Process process = new ProcessBuilder("php", "-r", script, component.toString())
                .redirectErrorStream(true)
                .start();

        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "PHP basic-info collection timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.exitValue() == 0, output);

        Map<String, Object> response = PortableJsonCodec.decode(output.getBytes(StandardCharsets.UTF_8));
        Map<?, ?> basicInfo = assertInstanceOf(Map.class, response.get("BasicInfo"));
        Map<?, ?> hardware = assertInstanceOf(Map.class, basicInfo.get("HardwareInfo"));
        assertTrue(number(hardware.get("TotalPhysicalMemoryMB")) > 0, hardware.toString());
        assertTrue(hardware.containsKey("TotalSwapSpaceMB"), hardware.toString());

        Map<?, ?> environment = assertInstanceOf(Map.class, basicInfo.get("EnvironmentInfo"));
        assertFalse(environment.isEmpty());

        List<?> fileSystems = assertInstanceOf(List.class, basicInfo.get("FileSystemInfo"));
        assertFalse(fileSystems.isEmpty());
        Map<?, ?> fileSystem = assertInstanceOf(Map.class, fileSystems.get(0));
        assertTrue(fileSystem.containsKey("Root"));
        assertTrue(number(fileSystem.get("TotalSpaceMB")) >= 0);

        List<?> networks = assertInstanceOf(List.class, basicInfo.get("NetworkInfo"));
        assertFalse(networks.isEmpty());
        Map<?, ?> network = assertInstanceOf(Map.class, networks.get(0));
        assertTrue(network.containsKey("IsUp"));
        assertInstanceOf(List.class, network.get("IPAddresses"));
    }

    private boolean phpAvailable() {
        try {
            Process process = new ProcessBuilder("php", "-v").redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : -1;
    }
}
