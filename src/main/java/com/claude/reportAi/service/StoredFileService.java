package com.claude.reportAi.service;

import com.claude.reportAi.dto.VectorUploadResponse;
import com.claude.reportAi.entities.StoredFile;
import com.claude.reportAi.repository.StoredFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StoredFileService {

    private static final int CHUNK_SIZE = 60;
    private static final int MAX_CHARS_PER_CHUNK_SAFETY = 800;
    private static final int MAX_NUM_CHUNKS = 20000;
    private static final int VECTORSTORE_BATCH_SIZE = 25;
    private static final int OVERLAP_CHARS = 200;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain",
            "text/html",
            "application/rtf",
            "text/csv"
    );

    private final StoredFileRepository storedFileRepository;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    @Value("${app.storage.root}")
    private String storageRoot;

    @Transactional
    public VectorUploadResponse ingest(byte[] bytes, String originalFilename, String contentType) {
        validateFile(bytes, originalFilename, contentType);

        Path destination = null;

        try {
            String sha256 = sha256Hex(bytes);

            Optional<StoredFile> existing = storedFileRepository.findBySha256(sha256);
            if (existing.isPresent()) {
                log.info("File '{}' già presente (sha256={})", originalFilename, sha256);
                return new VectorUploadResponse(
                        existing.get().getId(),
                        existing.get().getOriginalFilename(),
                        "ALREADY_EXISTS"
                );
            }

            UUID fileId = UUID.randomUUID();
            Path dir = Paths.get(storageRoot);
            Files.createDirectories(dir);

            String safeName = fileId + "_" + sanitizeFilename(
                    Objects.requireNonNullElse(originalFilename, "file.bin")
            );

            destination = dir.resolve(safeName);
            Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);

            ExtractionResult extraction = extractWithTika(destination);

            StoredFile entity = StoredFile.builder()
                    .originalFilename(Objects.requireNonNullElse(originalFilename, "unknown"))
                    .contentType(contentType)
                    .sha256(sha256)
                    .sizeBytes((long) bytes.length)
                    .storagePath(destination.toAbsolutePath().toString())
                    .extractedText(extraction.text())
                    .metadataJson(objectMapper.writeValueAsString(extraction.tikaMetadata()))
                    .extractionStatus("DONE")
                    .build();

            storedFileRepository.save(entity);

            List<Document> chunks = toChunks(entity, extraction.text(), extraction.tikaMetadata());

            log.info("File '{}' estratto: {} chars, {} chunks generati",
                    entity.getOriginalFilename(),
                    extraction.text() != null ? extraction.text().length() : 0,
                    chunks.size());

            if (chunks.isEmpty()) {
                log.warn("Nessun chunk valido per file '{}'", entity.getOriginalFilename());
                deleteFromDisk(destination, originalFilename);
                return new VectorUploadResponse(entity.getId(), entity.getOriginalFilename(), "NO_TEXT");
            }

            addDocumentsInBatches(chunks);

            deleteFromDisk(destination, originalFilename);

            return new VectorUploadResponse(entity.getId(), entity.getOriginalFilename(), "INDEXED");

        } catch (Exception e) {
            deleteFromDisk(destination, originalFilename);
            throw new IllegalStateException("Errore durante ingestione file: " + originalFilename, e);
        }
    }

    private void deleteFromDisk(Path path, String filename) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
            log.debug("File '{}' eliminato dal disco dopo l'indicizzazione.", filename);
        } catch (Exception ex) {
            log.warn("Impossibile eliminare il file '{}' dal disco: {}", filename, ex.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    public static void validateFile(byte[] bytes, String originalFilename, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Il file '" + originalFilename + "' è vuoto");
        }
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    "Tipo di file non supportato: '" + contentType + "'. " +
                    "Tipi accettati: PDF, DOC, DOCX, XLS, XLSX, TXT, HTML, RTF, CSV"
            );
        }
    }

    // -----------------------------------------------------------------------
    // Text extraction + Tika metadata
    // -----------------------------------------------------------------------

    private ExtractionResult extractWithTika(Path filePath) {
        Resource resource = resourceLoader.getResource("file:" + filePath.toAbsolutePath());
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> docs = reader.get();

        String extractedText = docs.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));

        // Collect all metadata extracted by Tika from the document itself
        Map<String, Object> tikaMetadata = new HashMap<>();
        for (Document doc : docs) {
            doc.getMetadata().forEach((k, v) -> {
                if (v != null) {
                    // Convert to String to ensure vector store compatibility
                    tikaMetadata.putIfAbsent(k, v.toString());
                }
            });
        }
        tikaMetadata.put("reader", "tika");
        tikaMetadata.put("docCount", docs.size());

        return new ExtractionResult(extractedText, tikaMetadata);
    }

    // -----------------------------------------------------------------------
    // Chunking with overlap
    // -----------------------------------------------------------------------

    private List<Document> toChunks(StoredFile entity, String text, Map<String, Object> tikaMetadata) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Document whole = new Document(text, buildBaseMetadata(entity, tikaMetadata));

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE)
                .withMinChunkSizeChars(20)
                .withMinChunkLengthToEmbed(20)
                .withMaxNumChunks(MAX_NUM_CHUNKS)
                .withKeepSeparator(true)
                .build();

        List<Document> initialChunks = splitter.split(List.of(whole));
        List<Document> safeChunks = forceSplitLargeChunks(initialChunks, MAX_CHARS_PER_CHUNK_SAFETY);
        List<Document> overlappedChunks = applyOverlap(safeChunks);

        int totalChunks = overlappedChunks.size();
        AtomicInteger idx = new AtomicInteger(0);

        return overlappedChunks.stream()
                .filter(Objects::nonNull)
                .filter(d -> d.getText() != null && !d.getText().isBlank())
                .map(d -> {
                    Map<String, Object> md = new HashMap<>(d.getMetadata());
                    md.put("chunkIndex", idx.getAndIncrement());
                    md.put("totalChunks", totalChunks);
                    return new Document(d.getText().trim(), md);
                })
                .toList();
    }

    /**
     * For each chunk (except the first), prepends the last OVERLAP_CHARS characters
     * of the previous chunk to preserve context across boundaries.
     * Uses original (non-overlapped) text as source to prevent cascade growth.
     */
    private List<Document> applyOverlap(List<Document> chunks) {
        if (chunks.size() <= 1) return chunks;

        // Capture original texts before mutation
        List<String> originalTexts = chunks.stream()
                .map(d -> d.getText() != null ? d.getText() : "")
                .toList();

        List<Document> result = new ArrayList<>();
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String prevOriginal = originalTexts.get(i - 1);
            String suffix = prevOriginal.length() > OVERLAP_CHARS
                    ? prevOriginal.substring(prevOriginal.length() - OVERLAP_CHARS)
                    : prevOriginal;

            // Trim to first word boundary to avoid starting mid-word
            int firstSpace = suffix.indexOf(' ');
            if (firstSpace > 0 && firstSpace < suffix.length() / 2) {
                suffix = suffix.substring(firstSpace + 1);
            }

            Document curr = chunks.get(i);
            String newText = suffix.trim() + " " + curr.getText().trim();
            result.add(new Document(newText.trim(), new HashMap<>(curr.getMetadata())));
        }

        return result;
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

    // -----------------------------------------------------------------------
    // Metadata builder
    // -----------------------------------------------------------------------

    private Map<String, Object> buildBaseMetadata(StoredFile entity, Map<String, Object> tikaMetadata) {
        Map<String, Object> metadata = new HashMap<>();

        // Core identifiers
        metadata.put("fileId", entity.getId().toString());
        metadata.put("source", Objects.toString(entity.getOriginalFilename(), "unknown"));
        metadata.put("filename", Objects.toString(entity.getOriginalFilename(), "unknown"));
        metadata.put("contentType", Objects.toString(entity.getContentType(), "unknown"));
        metadata.put("sha256", Objects.toString(entity.getSha256(), "unknown"));

        // Size and time — useful for filtering in RAG queries
        metadata.put("fileSizeBytes", entity.getSizeBytes());
        metadata.put("uploadedAt", Instant.now().toString());

        // Tika-extracted document metadata (author, title, creation date, etc.)
        // Stored under "doc.*" prefix to avoid collisions with our own keys
        tikaMetadata.forEach((k, v) -> {
            if (v != null && !k.equals("reader") && !k.equals("docCount")) {
                metadata.put("doc." + k, v.toString());
            }
        });

        return metadata;
    }

    // -----------------------------------------------------------------------
    // Vector store batch insert with rollback on partial failure
    // -----------------------------------------------------------------------

    private void addDocumentsInBatches(List<Document> chunks) {
        int total = chunks.size();
        int batchNumber = 1;
        List<String> insertedIds = new ArrayList<>();

        for (int start = 0; start < total; start += VECTORSTORE_BATCH_SIZE) {
            int end = Math.min(start + VECTORSTORE_BATCH_SIZE, total);
            List<Document> batch = chunks.subList(start, end);

            log.info("Indicizzazione batch {} -> chunk {}-{} di {}", batchNumber, start, end - 1, total);

            try {
                vectorStore.add(batch);
                batch.forEach(d -> insertedIds.add(d.getId()));
            } catch (Exception e) {
                log.error("Errore sul batch {} (chunk {}-{}). Rollback di {} documenti già inseriti.",
                        batchNumber, start, end - 1, insertedIds.size(), e);
                rollbackInsertedDocuments(insertedIds);
                throw e;
            }

            batchNumber++;
        }
    }

    private void rollbackInsertedDocuments(List<String> insertedIds) {
        if (insertedIds.isEmpty()) return;
        try {
            vectorStore.delete(insertedIds);
            log.info("Rollback vector store completato: {} documenti rimossi.", insertedIds.size());
        } catch (Exception rollbackEx) {
            log.error("Rollback vector store fallito. {} documenti orfani rimasti nel vector store.",
                    insertedIds.size(), rollbackEx);
        }
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

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

    private record ExtractionResult(String text, Map<String, Object> tikaMetadata) {
    }
}
