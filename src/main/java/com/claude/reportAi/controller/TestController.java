package com.claude.reportAi.controller;

import com.claude.reportAi.tool.WebSearchTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.ai.anthropic.AnthropicSkillsResponseHelper;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/test")
@Slf4j
@RequiredArgsConstructor
public class TestController {

    private final ChatClient chatClient;
    private final WebSearchTools webSearchTools;
    private final VectorStore vectorStore;
    private final AnthropicApi anthropicApi;

    private static final String SYSTEM_PROMPT = """
        Sei un senior engineer java developer  con oltre 30 anni di esperienza internazionale.
        Le tue risposte sono sempre professionali, chiare e in italiano.

        Regole obbligatorie:
        - Se l'utente richiede un file e nella pipeline è disponibile una skill compatibile, devi generare un file reale usando quella skill.
        - Se l'utente non richiede un file, genera una risposta testuale professionale, chiara, concreta, ben strutturata e adatta a un contesto executive o aziendale.
        - Se l'utente richiede un file ma nessuna skill compatibile è disponibile, rispondi con un testo professionale e chiaro, spiegando che il file richiesto non può essere generato con la configurazione attuale.
        - Non descrivere il processo.
        - Non dire che creerai il file.
        - Non chiedere conferma.
        - Non limitarti a spiegare il contenuto.

        Se l'utente richiede sia testo che file:
        - il file generato è l'output principale
        - puoi aggiungere solo un brevissimo riepilogo testuale insieme al file
        """;

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileGenerationResponse> generateFiles(
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestPart("chatRequest") String chatRequest) throws IOException {

        long start = System.currentTimeMillis();

        log.info("START /api/test/generate");
        log.info("chatRequest length={}", chatRequest != null ? chatRequest.length() : 0);
        log.info("filesCount={}", files != null ? files.size() : 0);

        if (files != null && !files.isEmpty()) {
            for (MultipartFile file : files) {
                if (file != null) {
                    log.info("inputFile name='{}' contentType='{}' size={} bytes",
                            file.getOriginalFilename(),
                            file.getContentType(),
                            file.getSize());
                }
            }
        }

        try {
            String enrichedUserText = buildUserText(chatRequest, files);
            Media[] supportedMedia = convertSupportedMedia(files);

            log.info("Building AI request: model=claude-sonnet-4-5, maxTokens=6000, cacheStrategy={}",
                    AnthropicCacheStrategy.SYSTEM_AND_TOOLS);

            long aiStart = System.currentTimeMillis();

            ChatResponse response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(user -> {
                        user.text(enrichedUserText);
                        if (supportedMedia.length > 0) {
                            user.media(supportedMedia);
                        }
                    })
                    .tools(webSearchTools)
                    .advisors(
                            QuestionAnswerAdvisor.builder(vectorStore)
                                    .searchRequest(SearchRequest.builder()
                                            .query(chatRequest)
                                            .topK(7)
                                            .similarityThreshold(0.5d)
                                            .build())
                                    .build()
                    )
                    .options(AnthropicChatOptions.builder()
                            .model("claude-sonnet-4-5")
                            .maxTokens(8192)
                            .skill(AnthropicApi.AnthropicSkill.PDF)
                            .skill(AnthropicApi.AnthropicSkill.DOCX)
                            .skill(AnthropicApi.AnthropicSkill.PPTX)
                            .skill(AnthropicApi.AnthropicSkill.XLSX)
                            .cacheOptions(AnthropicCacheOptions.builder()
                                    .strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS)
                                    .build())
                            .build())
                    .call()
                    .chatResponse();

            long aiEnd = System.currentTimeMillis();
            log.info("AI response received in {} ms", (aiEnd - aiStart));

            List<String> fileIds = AnthropicSkillsResponseHelper.extractFileIds(response);
            String containerId = AnthropicSkillsResponseHelper.extractContainerId(response);

            log.info("Extracted containerId={}", containerId);
            log.info("Extracted fileIds count={} values={}",
                    fileIds != null ? fileIds.size() : 0,
                    fileIds);

            Path outputDir = Files.createTempDirectory("anthropic-skills-output-");
            log.info("Created temp outputDir={}", outputDir);

            List<Path> downloadedFiles = List.of();
            if (fileIds != null && !fileIds.isEmpty()) {
                long downloadStart = System.currentTimeMillis();
                downloadedFiles = AnthropicSkillsResponseHelper.downloadAllFiles(response, anthropicApi, outputDir);
                long downloadEnd = System.currentTimeMillis();

                log.info("Downloaded files count={} in {} ms",
                        downloadedFiles.size(),
                        (downloadEnd - downloadStart));

                for (Path file : downloadedFiles) {
                    log.info("downloadedFile={}", file);
                }
            } else {
                log.info("No generated files found in response");
            }

            String textResponse = response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText()
                    : null;

            log.info("textResponse present={} length={}",
                    textResponse != null,
                    textResponse != null ? textResponse.length() : 0);

            FileGenerationResponse body = new FileGenerationResponse(
                    textResponse,
                    containerId,
                    fileIds != null ? fileIds : List.of(),
                    downloadedFiles.stream().map(Path::toString).toList()
            );

            long end = System.currentTimeMillis();
            log.info("END success totalTime={} ms", (end - start));

            return ResponseEntity.ok(body);

        } catch (Exception e) {
            long end = System.currentTimeMillis();
            log.error("ERROR after {} ms: {}", (end - start), e.getMessage(), e);
            throw e;
        }
    }

    private String buildUserText(String chatRequest, List<MultipartFile> files) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(chatRequest != null ? chatRequest : "");

        if (files == null || files.isEmpty()) {
            return sb.toString();
        }

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            String contentType = file.getContentType();
            String originalFilename = file.getOriginalFilename();

            if (XLSX_MIME.equals(contentType)) {
                log.info("Extracting Excel content from '{}'", originalFilename);

                String excelText = extractExcelText(file);

                sb.append("\n\n")
                        .append("[Contenuto estratto dal file Excel: ")
                        .append(originalFilename != null ? originalFilename : "file.xlsx")
                        .append("]\n")
                        .append(excelText);
            } else if (!isAnthropicSupportedMedia(file)) {
                log.info("Skipping unsupported user media '{}' with contentType='{}'",
                        originalFilename, contentType);

                sb.append("\n\n")
                        .append("[Nota: è stato ricevuto un file non allegabile direttamente al modello: ")
                        .append(originalFilename != null ? originalFilename : "file")
                        .append(" | contentType=")
                        .append(contentType)
                        .append("]");
            }
        }

        return sb.toString();
    }

    private Media[] convertSupportedMedia(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            log.debug("convertSupportedMedia(): no files provided");
            return new Media[0];
        }

        List<Media> mediaList = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            String contentType = file.getContentType();

            if (!isAnthropicSupportedMedia(file)) {
                log.debug("convertSupportedMedia(): skipping unsupported file='{}', contentType='{}'",
                        file.getOriginalFilename(),
                        contentType);
                continue;
            }

            log.debug("convertSupportedMedia(): adding supported file='{}', contentType='{}', size={}",
                    file.getOriginalFilename(),
                    contentType,
                    file.getSize());

            mediaList.add(new Media(
                    MimeType.valueOf(Objects.requireNonNull(contentType)),
                    file.getResource()
            ));
        }

        return mediaList.toArray(Media[]::new);
    }

    private boolean isAnthropicSupportedMedia(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) {
            return false;
        }

        return contentType.startsWith("image/")
                || MediaType.APPLICATION_PDF_VALUE.equals(contentType);
    }

    private String extractExcelText(MultipartFile file) throws IOException {
        StringBuilder sb = new StringBuilder();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                sb.append("Foglio: ").append(sheet.getSheetName()).append("\n");

                for (Row row : sheet) {
                    boolean hasContent = false;

                    for (Cell cell : row) {
                        String value = readCell(cell);
                        if (!value.isBlank()) {
                            hasContent = true;
                        }
                        sb.append(value).append("\t");
                    }

                    if (hasContent) {
                        sb.append("\n");
                    }
                }

                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String readCell(Cell cell) {
        if (cell == null) {
            return "";
        }

        return switch (cell.getCellType()) {
            case STRING -> safe(cell.getStringCellValue());
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                double numericValue = cell.getNumericCellValue();
                if (numericValue == Math.rint(numericValue)) {
                    yield Long.toString((long) numericValue);
                }
                yield Double.toString(numericValue);
            }
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case FORMULA -> safe(cell.getCellFormula());
            default -> "";
        };
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record FileGenerationResponse(
            String textResponse,
            String containerId,
            List<String> fileIds,
            List<String> downloadedFiles
    ) {
    }

    @PostMapping("/generate2")
    public ResponseEntity<String> generate2(@RequestParam("chatRequest") String chatRequest) throws IOException {

        ChatResponse response = chatClient.prompt()
                .user(chatRequest)
                .options(AnthropicChatOptions.builder()
                        .model("claude-sonnet-4-5")
                        .maxTokens(8192)
                        .skill(AnthropicApi.AnthropicSkill.XLSX)
                        .skill(AnthropicApi.AnthropicSkill.PPTX)
                        .build())
                .call()
                .chatResponse();

        List<String> fileIds = AnthropicSkillsResponseHelper.extractFileIds(response);

        if (fileIds == null || fileIds.isEmpty()) {
            throw new RuntimeException("No file was generated");
        }

        String fileId = fileIds.getFirst();
        AnthropicApi.FileMetadata metadata = anthropicApi.getFileMetadata(fileId);
        byte[] content = anthropicApi.downloadFile(fileId);

        Path outputDir = Files.createTempDirectory("anthropic-xlsx-output-");
        Path outputPath = outputDir.resolve(metadata.filename());
        Files.write(outputPath, content);

        return ResponseEntity.ok(outputPath.toString());
    }
}