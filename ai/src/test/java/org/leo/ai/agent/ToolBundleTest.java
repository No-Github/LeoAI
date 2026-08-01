package org.leo.ai.agent;

import org.junit.jupiter.api.Test;
import org.leo.ai.tools.platform.DisguiseTools;
import org.leo.ai.tools.platform.FingerprintTools;
import org.leo.ai.tools.platform.PlatformPlanTools;
import org.leo.ai.tools.platform.PluginTools;
import org.leo.ai.tools.platform.PuppetTools;
import org.leo.ai.tools.platform.ShellGeneratorTools;
import org.leo.ai.tools.platform.SkillActivationTools;
import org.leo.ai.tools.platform.TeamTools;
import org.leo.ai.tools.platform.UserTools;
import org.leo.ai.tools.common.UserInputTools;
import org.leo.ai.tools.puppetnode.BasicInfoTools;
import org.leo.ai.tools.puppetnode.BrowserDataTools;
import org.leo.ai.tools.puppetnode.ClipboardTools;
import org.leo.ai.tools.puppetnode.CommandTools;
import org.leo.ai.tools.puppetnode.CredentialHarvestTools;
import org.leo.ai.tools.puppetnode.DatabaseConnectionTools;
import org.leo.ai.tools.puppetnode.FileTools;
import org.leo.ai.tools.puppetnode.HttpRequestTools;
import org.leo.ai.tools.puppetnode.JavaPluginTools;
import org.leo.ai.tools.puppetnode.PlanTools;
import org.leo.ai.tools.puppetnode.ResourceTools;
import org.leo.ai.tools.puppetnode.ReverseTunnelTools;
import org.leo.ai.tools.puppetnode.ScanTools;
import org.leo.ai.tools.puppetnode.ScriptTools;
import org.leo.ai.tools.puppetnode.SessionTools;
import org.leo.ai.tools.puppetnode.SqlTools;
import org.leo.ai.tools.puppetnode.UtilTools;
import org.leo.ai.tools.puppetnode.WebRuntimeTools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ToolBundleTest {

    @Test
    void puppetBundleContainsTheCanonicalToolSetAndIsImmutable() {
        PuppetNodeToolBundle bundle = new PuppetNodeToolBundle(
                mock(CommandTools.class), mock(BasicInfoTools.class),
                mock(ReverseTunnelTools.class), mock(UtilTools.class),
                mock(FileTools.class), mock(ScanTools.class),
                mock(BrowserDataTools.class), mock(CredentialHarvestTools.class),
                mock(DatabaseConnectionTools.class), mock(ClipboardTools.class),
                mock(WebRuntimeTools.class), mock(JavaPluginTools.class),
                mock(HttpRequestTools.class), mock(ScriptTools.class),
                mock(SqlTools.class), mock(ResourceTools.class),
                mock(SessionTools.class), mock(PlanTools.class),
                mock(UserInputTools.class),
                mock(SkillActivationTools.class));

        assertEquals(20, bundle.tools().size());
        assertInstanceOf(CommandTools.class, bundle.tools().get(0));
        assertInstanceOf(PlanTools.class, bundle.tools().get(17));
        assertInstanceOf(UserInputTools.class, bundle.tools().get(18));
        assertInstanceOf(SkillActivationTools.class, bundle.tools().get(19));
        assertThrows(UnsupportedOperationException.class,
                () -> bundle.tools().add(new Object()));
    }

    @Test
    void platformBundleSupportsBridgeToolWithoutMutatingBaseSet() {
        PlatformToolBundle bundle = new PlatformToolBundle(
                mock(PuppetTools.class), mock(UserTools.class), mock(TeamTools.class),
                mock(PluginTools.class), mock(FingerprintTools.class), mock(DisguiseTools.class),
                mock(ShellGeneratorTools.class), mock(PlatformPlanTools.class),
                mock(UserInputTools.class),
                mock(SkillActivationTools.class));
        Object bridge = new Object();

        assertEquals(10, bundle.tools().size());
        assertInstanceOf(PuppetTools.class, bundle.tools().get(0));
        assertInstanceOf(PlatformPlanTools.class, bundle.tools().get(7));
        assertInstanceOf(UserInputTools.class, bundle.tools().get(8));
        assertInstanceOf(SkillActivationTools.class, bundle.tools().get(9));
        assertEquals(10, bundle.toolsWith(null).size());
        assertEquals(11, bundle.toolsWith(bridge).size());
        assertTrue(bundle.toolsWith(bridge).contains(bridge));
        assertEquals(10, bundle.tools().size());
        assertThrows(UnsupportedOperationException.class,
                () -> bundle.toolsWith(bridge).add(new Object()));
    }
}
