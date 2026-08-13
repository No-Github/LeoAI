package org.leo.jmg.mem.packer.xxljob;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

@PackerMeta(name = "XxlJobJson", group = "XXL-JOB", order = 1,
        minTargetJava = 8, supportedProtocols = {"http"})
public class XxlJobJsonPacker implements Packer {
    @Override
    public String pack(ClassPackerConfig config) {
        long now = System.currentTimeMillis();
        return "{\n"
                + "  \"jobId\" : 1,\n"
                + "  \"executorHandler\" : \"demoJobHandler\",\n"
                + "  \"executorParams\" : \"demoJobHandler\",\n"
                + "  \"executorBlockStrategy\" : \"COVER_EARLY\",\n"
                + "  \"executorTimeout\" : 0,\n"
                + "  \"logId\" : 1,\n"
                + "  \"logDateTime\" : " + now + ",\n"
                + "  \"glueType\" : \"GLUE_GROOVY\",\n"
                + "  \"glueSource\" : \""
                + XxlJobPayloadSupport.jsonEscape(XxlJobPayloadSupport.source(config)) + "\",\n"
                + "  \"glueUpdatetime\" : " + now + ",\n"
                + "  \"broadcastIndex\" : 0,\n"
                + "  \"broadcastTotal\" : 0\n"
                + "}";
    }
}
