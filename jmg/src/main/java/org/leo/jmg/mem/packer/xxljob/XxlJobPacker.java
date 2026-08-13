package org.leo.jmg.mem.packer.xxljob;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

/** XXL-JOB 2.2+ 默认采用 JSON /run 请求体。 */
@PackerMeta(name = "XxlJob", group = "XXL-JOB", order = 0,
        minTargetJava = 8, dependencies = {"XxlJobJson"}, supportedProtocols = {"http"})
public class XxlJobPacker implements Packer {
    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        return new XxlJobJsonPacker().pack(config);
    }
}
