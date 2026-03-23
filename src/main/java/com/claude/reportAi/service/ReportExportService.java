package com.claude.reportAi.service;

import com.claude.reportAi.dto.StoredArtifact;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Service
@Slf4j
public class ReportExportService {

    private final Path storageRoot;

    public ReportExportService(@Value("${app.reports.storage-path:generated-reports}") String storagePath) throws IOException {
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        Files.createDirectories(this.storageRoot);
        log.info("Storage report inizializzato in '{}'", this.storageRoot);
    }

    /**
     * Mantengo questo metodo per compatibilità col codice esistente.
     * In questa implementazione lo uso solo per CSV.
     */
    public String export(String content, String requestedFormat) {
        StoredArtifact artifact = saveTextArtifact("report", requestedFormat, content);
        return artifact.fileName();
    }

    public StoredArtifact saveTextArtifact(String baseName, String extension, String content) {
        try {
            String ext = normalizeExtension(extension);
            String fileName = buildFileName(baseName, ext);
            Path target = resolveSafe(fileName);
            Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

            log.info("File testuale salvato -> '{}'", target);
            return new StoredArtifact(fileName, "/api/reports/download/" + fileName);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Impossibile salvare il file testuale", ex);
        }
    }

    public StoredArtifact saveBinaryArtifact(String originalFilename, byte[] content) {
        try {
            String baseName = getBaseName(originalFilename);
            String extension = getExtension(originalFilename);
            String fileName = buildFileName(baseName, extension);
            Path target = resolveSafe(fileName);

            Files.write(target, content, StandardOpenOption.CREATE_NEW);

            log.info("File binario salvato -> '{}'", target);
            return new StoredArtifact(fileName, "/api/reports/download/" + fileName);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Impossibile salvare il file binario", ex);
        }
    }

    public Resource loadAsResource(String fileName) {
        try {
            Path path = resolveSafe(fileName);
            Resource resource = new UrlResource(path.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File non trovato: " + fileName);
            }

            return resource;
        }
        catch (MalformedURLException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome file non valido: " + fileName, ex);
        }
    }

    public String resolveContentType(String fileName) {
        String extension = getExtension(fileName).toLowerCase(Locale.ROOT);

        return switch (extension) {
            case "csv" -> "text/csv";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    private Path resolveSafe(String fileName) {
        Path resolved = storageRoot.resolve(fileName).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome file non consentito");
        }
        return resolved;
    }

    private String buildFileName(String baseName, String extension) {
        String safeBase = sanitizeBaseName(baseName);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String random = UUID.randomUUID().toString().substring(0, 8);
        return safeBase + "-" + timestamp + "-" + random + "." + extension;
    }

    private String sanitizeBaseName(String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return "report";
        }
        String sanitized = baseName.replaceAll("[^a-zA-Z0-9-_]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        if (sanitized.isBlank()) {
            sanitized = "report";
        }
        if (sanitized.length() > 60) {
            sanitized = sanitized.substring(0, 60);
        }
        return sanitized.toLowerCase(Locale.ROOT);
    }

    private String normalizeExtension(String ext) {
        if (ext == null || ext.isBlank()) {
            return "txt";
        }
        return ext.replace(".", "").trim().toLowerCase(Locale.ROOT);
    }

    private String getBaseName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "report";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(0, lastDot) : filename;
    }

    private String getExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "bin";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 && lastDot < filename.length() - 1
                ? filename.substring(lastDot + 1)
                : "bin";
    }
}