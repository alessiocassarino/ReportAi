package com.claude.reportAi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AttachmentContentExtractionService {

    @Autowired
    private ResourceLoader resourceLoader;

    public String extractText(Path filePath, String contentType) {
        try {
            Resource resource = resourceLoader.getResource("file:" + filePath.toAbsolutePath());
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> docs = reader.get();

            String extractedText = docs.stream()
                    .map(Document::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n\n"));

            log.info("Testo estratto da file '{}' -> lunghezza={}",
                    filePath.getFileName(),
                    extractedText != null ? extractedText.length() : 0);

            return extractedText;
        } catch (Exception e) {
            throw new IllegalStateException("Errore durante estrazione testo allegato: " + filePath, e);
        }
    }

    public List<Document> buildDocuments(Path filePath,
                                         String originalFilename,
                                         String contentType,
                                         String text,
                                         Map<String, Object> extraMetadata) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("filename", originalFilename);
        metadata.put("contentType", contentType != null ? contentType : "");
        metadata.put("sourcePath", filePath.toAbsolutePath().toString());

        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            metadata.putAll(extraMetadata);
        }

        Document whole = new Document(text, metadata);

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
                .map(doc -> {
                    Map<String, Object> chunkMetadata = new HashMap<>(doc.getMetadata());
                    chunkMetadata.put("chunkIndex", String.valueOf(idx.getAndIncrement()));
                    return new Document(doc.getText(), chunkMetadata);
                })
                .toList();
    }
}