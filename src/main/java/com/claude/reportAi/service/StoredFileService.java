package com.claude.reportAi.service;

import com.claude.reportAi.dto.DocumentUploadResponse;
import com.claude.reportAi.entities.StoredFile;
import com.claude.reportAi.repository.StoredFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class StoredFileService {

    private static final Logger log = LoggerFactory.getLogger(StoredFileService.class);

    private static final int CHUNK_SIZE = 60;
    private static final int MAX_CHARS_PER_CHUNK_SAFETY = 800;
    private static final int MAX_NUM_CHUNKS = 20000;
    private static final int VECTORSTORE_BATCH_SIZE = 25;

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ResourceLoader resourceLoader;

    @Value("${app.storage.root}")
    private String storageRoot;

    @Transactional
    public DocumentUploadResponse ingest(MultipartFile multipartFile) {
        validate(multipartFile);

        Path destination = null;

        try {
            byte[] bytes = multipartFile.getBytes();
            String sha256 = sha256Hex(bytes);

            Optional<StoredFile> existing = storedFileRepository.findBySha256(sha256);
            if (existing.isPresent()) {
                return new DocumentUploadResponse(
                        existing.get().getId(),
                        existing.get().getOriginalFilename(),
                        "ALREADY_EXISTS"
                );
            }

            UUID fileId = UUID.randomUUID();
            Path dir = Paths.get(storageRoot);
            Files.createDirectories(dir);

            String safeName = fileId + "_" + sanitizeFilename(
                    Objects.requireNonNullElse(multipartFile.getOriginalFilename(), "file.bin")
            );

            destination = dir.resolve(safeName);
            Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);

            ExtractionResult extraction = extractWithTika(destination);

            StoredFile entity = StoredFile.builder()
                    .originalFilename(Objects.requireNonNullElse(multipartFile.getOriginalFilename(), "unknown"))
                    .contentType(multipartFile.getContentType())
                    .sha256(sha256)
                    .sizeBytes(multipartFile.getSize())
                    .storagePath(destination.toAbsolutePath().toString())
                    .extractedText(extraction.text())
                    .metadataJson(objectMapper.writeValueAsString(extraction.metadata()))
                    .extractionStatus("DONE")
                    .build();

            storedFileRepository.save(entity);

            List<Document> chunks = toChunks(entity, extraction.text());

            log.info("File '{}' estratto: {} chars, {} chunks generati",
                    entity.getOriginalFilename(),
                    extraction.text() != null ? extraction.text().length() : 0,
                    chunks.size());

            if (chunks.isEmpty()) {
                log.warn("Nessun chunk valido generato per file '{}'", entity.getOriginalFilename());
                return new DocumentUploadResponse(entity.getId(), entity.getOriginalFilename(), "NO_TEXT");
            }

            addDocumentsInBatches(chunks);

            return new DocumentUploadResponse(entity.getId(), entity.getOriginalFilename(), "INDEXED");

        } catch (Exception e) {
            if (destination != null) {
                try {
                    Files.deleteIfExists(destination);
                } catch (Exception cleanupEx) {
                    log.warn("Impossibile eliminare il file temporaneo {}", destination, cleanupEx);
                }
            }

            throw new IllegalStateException("Errore durante ingestione file", e);
        }
    }

    private void addDocumentsInBatches(List<Document> chunks) {
        int total = chunks.size();
        int batchNumber = 1;

        for (int start = 0; start < total; start += VECTORSTORE_BATCH_SIZE) {
            int end = Math.min(start + VECTORSTORE_BATCH_SIZE, total);
            List<Document> batch = chunks.subList(start, end);

            log.info("Indicizzazione batch {} -> chunk {}-{} di {}",
                    batchNumber, start, end - 1, total);

            try {
                vectorStore.add(batch);
            } catch (Exception e) {
                log.error("Errore sul batch {} (chunk {}-{})", batchNumber, start, end - 1, e);
                throw e;
            }

            batchNumber++;
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File vuoto");
        }
    }

    private ExtractionResult extractWithTika(Path filePath) {
        Resource resource = resourceLoader.getResource("file:" + filePath.toAbsolutePath());
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> docs = reader.get();

        String extractedText = docs.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourcePath", filePath.toString());
        metadata.put("reader", "tika");
        metadata.put("docCount", docs.size());

        return new ExtractionResult(extractedText, metadata);
    }

    private List<Document> toChunks(StoredFile entity, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Document whole = new Document(text, buildBaseMetadata(entity));

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE)
                .withMinChunkSizeChars(20)
                .withMinChunkLengthToEmbed(20)
                .withMaxNumChunks(MAX_NUM_CHUNKS)
                .withKeepSeparator(true)
                .build();

        List<Document> initialChunks = splitter.split(List.of(whole));
        List<Document> safeChunks = forceSplitLargeChunks(initialChunks, MAX_CHARS_PER_CHUNK_SAFETY);

        AtomicInteger idx = new AtomicInteger(0);

        return safeChunks.stream()
                .filter(Objects::nonNull)
                .filter(d -> d.getText() != null && !d.getText().isBlank())
                .map(d -> {
                    Map<String, Object> md = new HashMap<>(d.getMetadata());
                    md.put("chunkIndex", idx.getAndIncrement());
                    return new Document(d.getText().trim(), md);
                })
                .toList();
    }

    private List<Document> forceSplitLargeChunks(List<Document> chunks, int maxChars) {
        List<Document> result = new ArrayList<>();

        for (Document doc : chunks) {
            if (doc == null || doc.getText() == null || doc.getText().isBlank()) {
                continue;
            }

            String text = doc.getText().trim();

            if (text.length() <= maxChars) {
                result.add(doc);
                continue;
            }

            log.warn("Chunk troppo grande rilevato ({} chars). Applico split di sicurezza.", text.length());

            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + maxChars, text.length());

                if (end < text.length()) {
                    int lastWhitespace = findLastWhitespace(text, start, end);
                    if (lastWhitespace > start) {
                        end = lastWhitespace;
                    }
                }

                String part = text.substring(start, end).trim();
                if (!part.isBlank()) {
                    result.add(new Document(part, new HashMap<>(doc.getMetadata())));
                }

                start = end;
            }
        }

        return result;
    }

    private int findLastWhitespace(String text, int start, int end) {
        for (int i = end - 1; i > start; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return end;
    }

    private Map<String, Object> buildBaseMetadata(StoredFile entity) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileId", entity.getId().toString());
        metadata.put("filename", Objects.toString(entity.getOriginalFilename(), "unknown"));
        metadata.put("contentType", Objects.toString(entity.getContentType(), "unknown"));
        metadata.put("sha256", Objects.toString(entity.getSha256(), "unknown"));
        return metadata;
    }

    private String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);

        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record ExtractionResult(String text, Map<String, Object> metadata) {
    }
}