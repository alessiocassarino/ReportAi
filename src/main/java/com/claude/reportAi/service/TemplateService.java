package com.claude.reportAi.service;

import com.claude.reportAi.constant.TemplateType;
import com.claude.reportAi.dto.AttachmentMetadataRequest;
import com.claude.reportAi.dto.TemplateSummaryResponse;
import com.claude.reportAi.entities.ReportTemplate;
import com.claude.reportAi.repository.ReportTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateService {

    private final ReportTemplateRepository reportTemplateRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.storage.templates-root:./data/templates}")
    private String templatesRoot;

    public ReportTemplate saveTemplate(MultipartFile file, AttachmentMetadataRequest metadataRequest) {
        try {
            Files.createDirectories(Path.of(templatesRoot));

            byte[] bytes = file.getBytes();
            String sha256 = sha256Hex(bytes);

            String originalFilename = Objects.requireNonNullElse(file.getOriginalFilename(), "template.bin");
            String safeFilename = sanitizeFilename(originalFilename);
            Path destination = Path.of(templatesRoot).resolve(UUID.randomUUID() + "_" + safeFilename);

            Files.write(destination, bytes);

            TemplateType templateType = determineTemplateType(
                    metadataRequest != null ? metadataRequest.getTemplateType() : null,
                    originalFilename,
                    file.getContentType()
            );

            String name = resolveTemplateName(metadataRequest, originalFilename);
            String code = resolveTemplateCode(metadataRequest);

            ReportTemplate template = ReportTemplate.builder()
                    .name(name)
                    .code(code)
                    .templateType(templateType)
                    .originalFilename(originalFilename)
                    .contentType(file.getContentType())
                    .sha256(sha256)
                    .storagePath(destination.toAbsolutePath().toString())
                    .active(true)
                    .version(1)
                    .description(metadataRequest != null ? metadataRequest.getDescription() : null)
                    .metadataJson(objectMapper.writeValueAsString(Map.of(
                            "displayName", metadataRequest != null ? nullSafe(metadataRequest.getDisplayName()) : "",
                            "templateName", metadataRequest != null ? nullSafe(metadataRequest.getTemplateName()) : "",
                            "clientFileName", metadataRequest != null ? nullSafe(metadataRequest.getClientFileName()) : ""
                    )))
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            ReportTemplate saved = reportTemplateRepository.save(template);
            log.info("Template salvato -> id={}, name={}, type={}", saved.getId(), saved.getName(), saved.getTemplateType());

            return saved;
        } catch (Exception e) {
            throw new IllegalStateException("Errore salvataggio template", e);
        }
    }

    public Optional<ReportTemplate> findById(Long id) {
        return reportTemplateRepository.findById(id)
                .filter(ReportTemplate::isActive);
    }

    public List<TemplateSummaryResponse> listActiveTemplates() {
        return reportTemplateRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(template -> TemplateSummaryResponse.builder()
                        .id(template.getId())
                        .name(template.getName())
                        .code(template.getCode())
                        .templateType(template.getTemplateType().name())
                        .originalFilename(template.getOriginalFilename())
                        .active(template.isActive())
                        .version(template.getVersion())
                        .build())
                .toList();
    }

    public Resource loadTemplateAsResource(Long templateId) {
        ReportTemplate template = findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template non trovato: " + templateId));

        return new PathResource(Path.of(template.getStoragePath()));
    }

    public TemplateType determineTemplateType(String explicitType, String filename, String contentType) {
        if (explicitType != null && !explicitType.isBlank()) {
            try {
                return TemplateType.valueOf(explicitType.trim().toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
                // fallback sotto
            }
        }

        String lower = filename != null ? filename.toLowerCase(Locale.ROOT) : "";

        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return TemplateType.EXCEL;
        }
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
            return TemplateType.DOCX;
        }
        if (lower.endsWith(".csv")) {
            return TemplateType.CSV;
        }
        if (lower.endsWith(".pdf")) {
            return TemplateType.PDF_REFERENCE;
        }

        return TemplateType.GENERIC;
    }

    private String resolveTemplateName(AttachmentMetadataRequest metadataRequest, String originalFilename) {
        if (metadataRequest != null && metadataRequest.getTemplateName() != null && !metadataRequest.getTemplateName().isBlank()) {
            return metadataRequest.getTemplateName().trim();
        }
        return originalFilename;
    }

    private String resolveTemplateCode(AttachmentMetadataRequest metadataRequest) {
        if (metadataRequest != null && metadataRequest.getTemplateCode() != null && !metadataRequest.getTemplateCode().isBlank()) {
            return metadataRequest.getTemplateCode().trim();
        }
        return "TPL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
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

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}