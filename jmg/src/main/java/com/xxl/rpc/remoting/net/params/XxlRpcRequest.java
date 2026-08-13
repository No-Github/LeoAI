package com.xxl.rpc.remoting.net.params;

import java.io.Serializable;

public class XxlRpcRequest implements Serializable {
    private static final long serialVersionUID = 42L;

    private String requestId;
    private long createMillisTime;
    private String accessToken;
    private String className;
    private String methodName;
    private Class<?>[] parameterTypes;
    private Object[] parameters;

    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public long getCreateMillisTime() { return createMillisTime; }
    public void setCreateMillisTime(long value) { createMillisTime = value; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String value) { accessToken = value; }
    public String getClassName() { return className; }
    public void setClassName(String value) { className = value; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String value) { methodName = value; }
    public Class<?>[] getParameterTypes() { return parameterTypes; }
    public void setParameterTypes(Class<?>[] value) { parameterTypes = value; }
    public Object[] getParameters() { return parameters; }
    public void setParameters(Object[] value) { parameters = value; }
}
