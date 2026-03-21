package com.claude.reportAi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class TemporaryFileService {

    @Value("${app.storage.temp-root:./data/temp}")
    private String tempRoot;

    public Path createRequestTempDirectory() {
        try {
            Path root = Path.of(tempRoot);
            Files.createDirectories(root);

            Path requestDir = root.resolve(UUID.randomUUID().toString());
            Files.createDirectories(requestDir);

            log.info("Creata directory temporanea request-scoped: {}", requestDir);
            return requestDir;
        } catch (IOException e) {
            throw new IllegalStateException("Errore creazione directory temporanea", e);
        }
    }

    public Path saveTempFile(Path requestDir, MultipartFile file) {
        try {
            Files.createDirectories(requestDir);

            String originalFilename = Objects.requireNonNullElse(file.getOriginalFilename(), "file.bin");
            String safeFilename = sanitizeFilename(originalFilename);
            Path destination = requestDir.resolve(UUID.randomUUID() + "_" + safeFilename);

            Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);

            log.info("Salvato file temporaneo: {}", destination);
            return destination;
        } catch (IOException e) {
            throw new IllegalStateException("Errore salvataggio file temporaneo", e);
        }
    }

    public void cleanupDirectory(Path requestDir) {
        if (requestDir == null) {
            return;
        }

        try {
            if (!Files.exists(requestDir)) {
                return;
            }

            Files.walk(requestDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("Impossibile eliminare path temporaneo: {}", path, e);
                        }
                    });

            log.info("Cleanup completato per directory: {}", requestDir);
        } catch (IOException e) {
            log.warn("Errore durante cleanup directory temporanea: {}", requestDir, e);
        }
    }

    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}