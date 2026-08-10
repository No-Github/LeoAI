package org.leo.web.util;

import org.springframework.http.ContentDisposition;

import java.nio.charset.StandardCharsets;

/** 下载响应头构造工具。 */
public final class DownloadHeaders {

    private DownloadHeaders() {
    }

    public static ContentDisposition attachment(String filename) {
        return ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
    }

    public static String attachmentValue(String filename) {
        return attachment(filename).toString();
    }
}
