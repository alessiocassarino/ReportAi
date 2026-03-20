package com.claude.reportAi.service;

import com.claude.reportAi.dto.DocumentUploadResponse;
import com.claude.reportAi.entities.StoredFile;
import com.claude.reportAi.repository.StoredFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.ResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class StoredFileService {

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private  ResourceLoader resourceLoader;

    @Value("${app.storage.root}")
    private String storageRoot;

    @Transactional
    public DocumentUploadResponse ingest(MultipartFile multipartFile) {
        validate(multipartFile);

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
            Path destination = dir.resolve(safeName);
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
            vectorStore.add(chunks);

            return new DocumentUploadResponse(entity.getId(), entity.getOriginalFilename(), "INDEXED");

        } catch (Exception e) {
            throw new IllegalStateException("Errore durante ingestione file", e);
        }
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
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

        Document whole = new Document(text, Map.of(
                "fileId", entity.getId().toString(),
                "filename", Objects.toString(entity.getOriginalFilename(), "unknown"),
                "contentType", Objects.toString(entity.getContentType(), "unknown"),
                "sha256", Objects.toString(entity.getSha256(), "unknown")
        ));

        TokenTextSplitter splitter = new TokenTextSplitter(
                300,   // chunk size
                50,    // overlap
                10,    // min chunk size chars
                1000,  // max chunk size chars
                true   // keep separator
        );

        List<Document> chunks = splitter.split(List.of(whole));

        AtomicInteger idx = new AtomicInteger(0);
        return chunks.stream()
                .map(d -> {
                    Map<String, Object> md = new HashMap<>();
                    md.put("fileId", Objects.toString(d.getMetadata().get("fileId"), ""));
                    md.put("filename", Objects.toString(d.getMetadata().get("filename"), ""));
                    md.put("contentType", Objects.toString(d.getMetadata().get("contentType"), ""));
                    md.put("sha256", Objects.toString(d.getMetadata().get("sha256"), ""));
                    md.put("chunkIndex", String.valueOf(idx.getAndIncrement()));
                    return new Document(d.getText(), md);
                })
                .toList();
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

    private record ExtractionResult(String text, Map<String, Object> metadata) {}
}
