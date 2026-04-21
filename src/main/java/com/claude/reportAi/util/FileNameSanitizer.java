package com.claude.reportAi.util;

/**
 * Sanitizes user-provided output filenames before persisting them.
 *
 * Defends against:
 *  - Path traversal  (strips / \ .. sequences)
 *  - Content-Disposition header injection (strips double quotes)
 *  - Windows-illegal chars  (* ? : < > | and control characters)
 *  - Leading/trailing dots and spaces (break Windows file system)
 *  - Excessively long names
 *
 * If the sanitized result is blank the method returns {@code null},
 * signalling the caller to use a timestamped fallback instead.
 */
public final class FileNameSanitizer {

    private static final int MAX_LENGTH = 150;

    private FileNameSanitizer() {}

    /**
     * @param rawName  the value submitted by the user (may be null / blank)
     * @return sanitized name ending in {@code .docx}, or {@code null} if rawName was empty/invalid
     */
    public static String sanitize(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }

        String name = rawName
                .replaceAll("[/\\\\]", "")        // path separators
                .replaceAll("\\.\\.", "")           // parent-dir traversal
                .replaceAll("[\"*?:<>|]", "")       // Content-Disposition & Windows illegal chars
                .replaceAll("[\\x00-\\x1F\\x7F]", "") // control characters
                .strip()
                .replaceAll("^[. ]+", "")           // leading dots/spaces
                .replaceAll("[. ]+$", "");           // trailing dots/spaces

        if (name.isBlank()) {
            return null;
        }

        if (name.length() > MAX_LENGTH) {
            name = name.substring(0, MAX_LENGTH).stripTrailing();
        }

        if (!name.toLowerCase().endsWith(".docx")) {
            name = name + ".docx";
        }

        return name;
    }
}
