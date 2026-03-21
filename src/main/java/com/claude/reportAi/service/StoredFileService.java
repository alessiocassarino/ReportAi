package com.claude.reportAi.service;

import com.claude.reportAi.dto.DocumentUploadResponse;
import com.claude.reportAi.dto.ExtractedMetadata;
import com.claude.reportAi.entities.DocumentMetadata;
import com.claude.reportAi.entities.StoredFile;
import com.claude.reportAi.repository.DocumentMetadataRepository;
import com.claude.reportAi.repository.StoredFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class StoredFileService {

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Autowired
    private DocumentMetadataRepository documentMetadataRepository;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ResourceLoader resourceLoader;

    @Autowired
    private MetadataExtractionService metadataExtractionService;

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

            StoredFile savedFile = storedFileRepository.save(entity);

            ExtractedMetadata extractedMetadata = metadataExtractionService.extract(
                    savedFile.getOriginalFilename(),
                    extraction.text(),
                    savedFile.getContentType()
            );

            DocumentMetadata documentMetadata = DocumentMetadata.builder()
                    .storedFile(savedFile)
                    .documentType(extractedMetadata.getDocumentType())
                    .language(extractedMetadata.getLanguage())
                    .country(extractedMetadata.getCountry())
                    .clientName(extractedMetadata.getClientName())
                    .projectName(extractedMetadata.getProjectName())
                    .sector(extractedMetadata.getSector())
                    .contractType(extractedMetadata.getContractType())
                    .documentDate(extractedMetadata.getDocumentDate())
                    .documentVersion(extractedMetadata.getDocumentVersion())
                    .tagsJson(objectMapper.writeValueAsString(
                            extractedMetadata.getTags() != null ? extractedMetadata.getTags() : List.of()
                    ))
                    .createdAt(Instant.now())
                    .build();

            documentMetadataRepository.save(documentMetadata);

            List<Document> chunks = toChunks(savedFile, extractedMetadata, extraction.text());
            if (!chunks.isEmpty()) {
                vectorStore.add(chunks);
            }

            return new DocumentUploadResponse(savedFile.getId(), savedFile.getOriginalFilename(), "INDEXED");

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

    private List<Document> toChunks(StoredFile entity, ExtractedMetadata extractedMetadata, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Map<String, Object> baseMetadata = new HashMap<>();
        baseMetadata.put("fileId", entity.getId().toString());
        baseMetadata.put("filename", Objects.toString(entity.getOriginalFilename(), "unknown"));
        baseMetadata.put("contentType", Objects.toString(entity.getContentType(), "unknown"));
        baseMetadata.put("sha256", Objects.toString(entity.getSha256(), "unknown"));
        baseMetadata.put("documentType", extractedMetadata.getDocumentType() != null ? extractedMetadata.getDocumentType().name() : "UNKNOWN");
        baseMetadata.put("language", extractedMetadata.getLanguage() != null ? extractedMetadata.getLanguage().name() : "UNKNOWN");
        baseMetadata.put("country", safeMetadataValue(extractedMetadata.getCountry()));
        baseMetadata.put("clientName", safeMetadataValue(extractedMetadata.getClientName()));
        baseMetadata.put("projectName", safeMetadataValue(extractedMetadata.getProjectName()));
        baseMetadata.put("sector", safeMetadataValue(extractedMetadata.getSector()));
        baseMetadata.put("contractType", safeMetadataValue(extractedMetadata.getContractType()));
        baseMetadata.put("documentVersion", safeMetadataValue(extractedMetadata.getDocumentVersion()));
        baseMetadata.put("documentDate", extractedMetadata.getDocumentDate() != null ? extractedMetadata.getDocumentDate().toString() : "");

        if (extractedMetadata.getTags() != null && !extractedMetadata.getTags().isEmpty()) {
            baseMetadata.put("tags", String.join(",", extractedMetadata.getTags()));
        } else {
            baseMetadata.put("tags", "");
        }

        Document whole = new Document(text, baseMetadata);

        TokenTextSplitter splitter = new TokenTextSplitter(
                300,
                50,
                10,
                1000,
                true
        );

        List<Document> chunks = splitter.split(List.of(whole));
        AtomicInteger idx = new AtomicInteger(0);

        return chunks.stream()
                .map(d -> {
                    Map<String, Object> md = new HashMap<>(d.getMetadata());
                    md.put("chunkIndex", String.valueOf(idx.getAndIncrement()));
                    return new Document(d.getText(), md);
                })
                .toList();
    }

    private String safeMetadataValue(String value) {
        return value == null ? "" : value;
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