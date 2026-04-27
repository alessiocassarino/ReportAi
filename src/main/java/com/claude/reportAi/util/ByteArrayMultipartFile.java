package com.claude.reportAi.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Adapts a byte[] into a MultipartFile so pipeline steps can pass file bytes
 * to services that still expect a MultipartFile (v1 interface).
 */
public class ByteArrayMultipartFile implements MultipartFile {

    private final byte[] content;
    private final String name;
    private final String originalFilename;
    private final String contentType;

    public ByteArrayMultipartFile(byte[] content, String filename, String contentType) {
        this.content = content;
        this.name = "file";
        this.originalFilename = filename;
        this.contentType = contentType;
    }

    @Override public String getName() { return name; }
    @Override public String getOriginalFilename() { return originalFilename; }
    @Override public String getContentType() { return contentType; }
    @Override public boolean isEmpty() { return content == null || content.length == 0; }
    @Override public long getSize() { return content == null ? 0 : content.length; }
    @Override public byte[] getBytes() { return content; }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(content == null ? new byte[0] : content); }

    @Override
    public void transferTo(java.io.File dest) throws IOException {
        try (var out = new java.io.FileOutputStream(dest)) {
            out.write(content);
        }
    }
}
