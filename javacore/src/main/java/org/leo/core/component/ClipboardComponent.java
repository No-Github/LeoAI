package org.leo.core.component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 剪贴板操作组件
 * <p>
 * 在 puppet 侧读取或写入目标主机剪贴板内容。
 * <p>
 * 操作类型（action）：
 * - read：读取当前剪贴板文本内容
 * - write：向剪贴板写入文本（参数 content）
 * - monitor：连续采样剪贴板内容变化（参数 duration 秒，默认10，上限60）
 * <p>
 * Windows: PowerShell Get-Clipboard / Set-Clipboard
 * macOS: pbpaste / pbcopy
 * Linux: xclip / xsel / wl-paste / wl-copy
 * <p>
 * 遵循 COMPONENT_GUIDE.md：Java 1.6 语法。
 */
public class ClipboardComponent implements Runnable {

    private static final int MAX_CONTENT_CHARS = 1024 * 1024;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 65536;
    private static final long COMMAND_TIMEOUT_MS = 30000L;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

    // execCommand 工作线程通信字段（防止阻塞 HTTP 请求线程）
    private volatile boolean execCmdMode = false;
    private volatile boolean execCmdDone = false;
    private volatile boolean execCmdCancelled = false;
    private String execCmdInput;
    private byte[] execCmdStdin;
    private volatile String execCmdOutput;
    private volatile Process execCmdProcess;


    public void run() {
        if (execCmdMode) { execCmdWorker(); return; }
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = copyStringObjectMap(h.invoke(null, null, null));
            results = new java.util.HashMap<String, Object>();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap<String, Object>();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage() != null ? t.getMessage() : t.getClass().getName());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }


    public void invoke() throws Exception {
        String action = getStringParam("action");
        if (action == null || action.length() == 0) {
            action = "read";
        }
        action = action.trim().toLowerCase(Locale.ENGLISH);

        if ("read".equals(action)) {
            doRead();
        } else if ("write".equals(action)) {
            doWrite();
        } else if ("monitor".equals(action)) {
            doMonitor();
        } else {
            results.put("code", 400);
            results.put("msg", "unknown action: " + action);
        }
    }

    // ==================== read ====================

    private void doRead() {
        boolean isWindows = isWindowsOs();
        boolean isMac = isMacOs();

        HashMap<String, Object> data = new HashMap<String, Object>();
        data.put("os", isWindows ? "windows" : (isMac ? "macos" : "linux"));

        String content = null;

        if (isWindows) {
            content = readWindows();
        } else if (isMac) {
            content = readMac();
        } else {
            content = readLinux();
        }

        if (content != null) {
            data.put("content", truncate(content, 65536));
            data.put("length", Integer.valueOf(content.length()));
            data.put("lines", Integer.valueOf(countLines(content)));
            data.put("empty", Boolean.valueOf(content.trim().length() == 0));
            results.put("code", 200);
        } else {
            data.put("content", "");
            data.put("empty", Boolean.TRUE);
            data.put("error", "无法读取剪贴板，可能无图形会话或缺少工具(xclip/xsel/wl-paste)");
            results.put("code", 200);
        }
        results.put("data", data);
    }

    private String readWindows() {
        String output = execCommand("powershell -Command \"Get-Clipboard -Raw\"");
        if (output != null) return output;
        output = execCommand("powershell -Command \"Get-Clipboard\"");
        return output;
    }

    private String readMac() {
        return execCommand("pbpaste 2>/dev/null");
    }

    private String readLinux() {
        // 优先 xclip（X11 最常见）
        String output = execCommand("xclip -selection clipboard -o 2>/dev/null");
        if (output != null && output.length() > 0) return output;
        // xsel
        output = execCommand("xsel --clipboard --output 2>/dev/null");
        if (output != null && output.length() > 0) return output;
        // Wayland: wl-paste
        output = execCommand("wl-paste 2>/dev/null");
        if (output != null && output.length() > 0) return output;
        // 最后尝试 xclip primary
        output = execCommand("xclip -o 2>/dev/null");
        return output;
    }

    // ==================== write ====================

    private void doWrite() {
        String content = getStringParam("content");
        if (content == null) {
            results.put("code", 400);
            results.put("msg", "content parameter is required");
            return;
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            results.put("code", Integer.valueOf(413));
            results.put("msg", "content 超过 1MB 字符上限");
            return;
        }

        boolean isWindows = isWindowsOs();
        boolean isMac = isMacOs();
        boolean success;

        if (isWindows) {
            success = writeWindows(content);
        } else if (isMac) {
            success = writeMac(content);
        } else {
            success = writeLinux(content);
        }

        HashMap<String, Object> data = new HashMap<String, Object>();
        data.put("os", isWindows ? "windows" : (isMac ? "macos" : "linux"));
        data.put("written", Boolean.valueOf(success));
        data.put("length", Integer.valueOf(content.length()));

        if (success) {
            results.put("code", 200);
        } else {
            results.put("code", 500);
            data.put("error", "写入剪贴板失败，可能无图形会话或缺少工具");
        }
        results.put("data", data);
    }

    private boolean writeWindows(String content) {
        String output = execCommand(
                "powershell -NoProfile -Command \"[Console]::InputEncoding=[Text.Encoding]::UTF8; "
                        + "Set-Clipboard -Value ([Console]::In.ReadToEnd())\"",
                toUtf8(content));
        return output != null;
    }

    private boolean writeMac(String content) {
        String output = execCommand("pbcopy 2>/dev/null", toUtf8(content));
        return output != null;
    }

    private boolean writeLinux(String content) {
        byte[] input = toUtf8(content);
        // 尝试 xclip
        String output = execCommand("xclip -selection clipboard 2>/dev/null && echo OK", input);
        if (output != null && output.contains("OK")) return true;
        // 尝试 xsel
        output = execCommand("xsel --clipboard --input 2>/dev/null && echo OK", input);
        if (output != null && output.contains("OK")) return true;
        // 尝试 wl-copy (Wayland)
        output = execCommand("wl-copy 2>/dev/null && echo OK", input);
        if (output != null && output.contains("OK")) return true;
        return false;
    }

    // ==================== monitor ====================

    private void doMonitor() {
        int duration = getIntParam("duration", 10);
        if (duration < 1) duration = 1;
        if (duration > 60) duration = 60;
        int interval = getIntParam("interval", 1);
        if (interval < 1) interval = 1;
        if (interval > 60) interval = 60;

        ArrayList<HashMap<String, Object>> snapshots = new ArrayList<HashMap<String, Object>>();
        String lastContent = null;

        long endTime = System.currentTimeMillis() + duration * 1000L;
        int seq = 0;

        while (System.currentTimeMillis() < endTime && seq < 120) {
            String current = readClipboard();
            if (current == null) current = "";

            boolean changed = (lastContent == null && current.length() > 0) ||
                              (lastContent != null && !lastContent.equals(current));

            if (changed) {
                seq++;
                HashMap<String, Object> snap = new HashMap<String, Object>();
                snap.put("seq", Integer.valueOf(seq));
                snap.put("timestamp", Long.valueOf(System.currentTimeMillis()));
                snap.put("content", truncate(current, 4096));
                snap.put("length", Integer.valueOf(current.length()));
                snapshots.add(snap);
                lastContent = current;
            }

            try {
                Thread.sleep(interval * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        HashMap<String, Object> data = new HashMap<String, Object>();
        data.put("duration", Integer.valueOf(duration));
        data.put("changes", Integer.valueOf(snapshots.size()));
        data.put("snapshots", snapshots);
        if (snapshots.isEmpty()) {
            data.put("note", "监控期间剪贴板无变化");
        }
        results.put("code", 200);
        results.put("data", data);
    }

    private String readClipboard() {
        if (isWindowsOs()) return readWindows();
        if (isMacOs()) return readMac();
        return readLinux();
    }

    private static HashMap<String, Object> copyStringObjectMap(Object value) {
        HashMap<String, Object> copy = new HashMap<String, Object>();
        if (!(value instanceof Map)) {
            return copy;
        }
        Map<?, ?> source = (Map<?, ?>) value;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String) {
                copy.put((String) entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    // ==================== 工具方法 ====================

    private String execCommand(String command) {
        return execCommand(command, null);
    }

    private synchronized String execCommand(String command, byte[] stdin) {
        // 上一个超时 worker 尚未退出时不复用共享状态，避免串写下一次命令结果。
        if (execCmdMode && !execCmdDone) return null;
        execCmdInput = command;
        execCmdStdin = stdin;
        execCmdOutput = null;
        execCmdDone = false;
        execCmdCancelled = false;
        execCmdProcess = null;
        execCmdMode = true; // volatile write: happens-before worker thread start
        Thread worker = new Thread(this,
                getClass().getSimpleName() + "-" + THREAD_SEQUENCE.incrementAndGet());
        worker.setDaemon(true);
        worker.start();
        long deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS;
        while (!execCmdDone && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (!execCmdDone) {
            execCmdCancelled = true;
            Process process = execCmdProcess;
            if (process != null) {
                try { process.destroy(); } catch (Exception ignored) {}
                try { process.getOutputStream().close(); } catch (Exception ignored) {}
                try { process.getInputStream().close(); } catch (Exception ignored) {}
            }
            worker.interrupt();
            long graceDeadline = System.currentTimeMillis() + 1000L;
            while (!execCmdDone && System.currentTimeMillis() < graceDeadline) {
                try { Thread.sleep(20L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return execCmdOutput;
    }

    private void execCmdWorker() {
        Process proc = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            if (execCmdCancelled) return;
            String[] cmd;
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("win")) {
                cmd = new String[]{"cmd.exe", "/c", execCmdInput};
            } else {
                cmd = new String[]{"/bin/sh", "-c", execCmdInput};
            }
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.redirectErrorStream(true);
            proc = builder.start();
            execCmdProcess = proc;
            if (execCmdCancelled) {
                try { proc.destroy(); } catch (Exception ignored) {}
                return;
            }
            os = proc.getOutputStream();
            if (execCmdStdin != null && execCmdStdin.length > 0) {
                os.write(execCmdStdin);
                os.flush();
            }
            try { os.close(); } catch (Exception ignored) {}
            os = null;
            is = proc.getInputStream();
            InputStreamReader reader = new InputStreamReader(
                    is, isWindowsOs() ? detectWindowsCharset() : "UTF-8");
            StringBuffer sb = new StringBuffer();
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                int remaining = MAX_COMMAND_OUTPUT_CHARS - sb.length();
                if (remaining > 0) {
                    sb.append(buffer, 0, read < remaining ? read : remaining);
                }
            }
            int exitCode = -1;
            try { exitCode = proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            execCmdOutput = exitCode == 0 ? sb.toString() : null;
        } catch (Exception e) {
            execCmdOutput = null;
        } finally {
            if (os != null) { try { os.close(); } catch (Exception ignored) {} }
            if (is != null) { try { is.close(); } catch (Exception ignored) {} }
            if (proc != null) { try { proc.destroy(); } catch (Exception ignored) {} }
            execCmdProcess = null;
            execCmdMode = false;
            execCmdDone = true;
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n... (truncated, total " + s.length() + " chars)";
    }

    private int countLines(String s) {
        if (s == null || s.length() == 0) return 0;
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') count++;
        }
        return count;
    }

    private String getStringParam(String key) {
        Object val = params.get(key);
        if (val == null) return null;
        if (val instanceof String) return (String) val;
        if (val instanceof byte[]) {
            try { return new String((byte[]) val, "UTF-8"); }
            catch (UnsupportedEncodingException ignored) { return new String((byte[]) val); }
        }
        return String.valueOf(val);
    }

    private int getIntParam(String key, int defaultVal) {
        Object val = params.get(key);
        if (val == null) return defaultVal;
        if (val instanceof Number) return ((Number) val).intValue();
        String text = getStringParam(key);
        try { return Integer.parseInt(text == null ? "" : text.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private String detectWindowsCharset() {
        String charset = System.getProperty("sun.jnu.encoding");
        if (charset != null && charset.length() > 0) return charset;
        charset = System.getProperty("file.encoding");
        if (charset != null && charset.length() > 0) return charset;
        return "GBK";
    }

    private byte[] toUtf8(String value) {
        try { return value.getBytes("UTF-8"); }
        catch (UnsupportedEncodingException ignored) { return value.getBytes(); }
    }

    private boolean isWindowsOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
