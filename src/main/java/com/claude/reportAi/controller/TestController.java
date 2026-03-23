package com.claude.reportAi.controller;

import com.claude.reportAi.tool.WebSearchTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicSkillsResponseHelper;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

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
        Sei un professionista senior in ambito business, strategico e corporate, con oltre 30 anni di esperienza internazionale.
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

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileGenerationResponse> generateFiles(
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestPart("chatRequest") String chatRequest) throws IOException {

        String requestId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        log.info("[{}] START /generate-files", requestId);
        log.info("[{}] chatRequest length={}", requestId, chatRequest != null ? chatRequest.length() : 0);
        log.info("[{}] filesCount={}", requestId, files != null ? files.size() : 0);

        if (files != null && !files.isEmpty()) {
            for (MultipartFile file : files) {
                if (file != null) {
                    log.info("[{}] inputFile name='{}' contentType='{}' size={} bytes",
                            requestId,
                            file.getOriginalFilename(),
                            file.getContentType(),
                            file.getSize());
                }
            }
        }



        try {
            log.info("[{}] Building AI request: model=claude-sonnet-4-5, maxTokens=6000, cacheStrategy={}",
                    requestId,
                    AnthropicCacheStrategy.SYSTEM_AND_TOOLS);

            long aiStart = System.currentTimeMillis();

            ChatResponse response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(user -> {
                        user.text(chatRequest);
                        if (files != null && !files.isEmpty()) {
                            user.media(convert(files.toArray(MultipartFile[]::new)));
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
                            .temperature(0.3)
                            .maxTokens(6000)
                            .cacheOptions(AnthropicCacheOptions.builder()
                                    .strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS)
                                    .build())
                            .skill(AnthropicApi.AnthropicSkill.PDF)
                            .skill(AnthropicApi.AnthropicSkill.DOCX)
                            .skill(AnthropicApi.AnthropicSkill.PPTX)
                            .skill(AnthropicApi.AnthropicSkill.XLSX)
                            .build())
                    .call()
                    .chatResponse();

            long aiEnd = System.currentTimeMillis();
            log.info("[{}] AI response received in {} ms", requestId, (aiEnd - aiStart));

            List<String> fileIds = AnthropicSkillsResponseHelper.extractFileIds(response);
            String containerId = AnthropicSkillsResponseHelper.extractContainerId(response);

            log.info("[{}] Extracted containerId={}", requestId, containerId);
            log.info("[{}] Extracted fileIds count={} values={}", requestId,
                    fileIds != null ? fileIds.size() : 0,
                    fileIds);

            Path outputDir = Files.createTempDirectory("anthropic-skills-output-");
            log.info("[{}] Created temp outputDir={}", requestId, outputDir);

            long downloadStart = System.currentTimeMillis();
            List<Path> downloadedFiles = AnthropicSkillsResponseHelper.downloadAllFiles(response, anthropicApi, outputDir);
            long downloadEnd = System.currentTimeMillis();

            log.info("[{}] Downloaded files count={} in {} ms",
                    requestId,
                    downloadedFiles != null ? downloadedFiles.size() : 0,
                    (downloadEnd - downloadStart));

            if (downloadedFiles != null && !downloadedFiles.isEmpty()) {
                for (Path file : downloadedFiles) {
                    log.info("[{}] downloadedFile={}", requestId, file);
                }
            }

            String textResponse = response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText()
                    : null;

            log.info("[{}] textResponse present={} length={}",
                    requestId,
                    textResponse != null,
                    textResponse != null ? textResponse.length() : 0);

            FileGenerationResponse body = new FileGenerationResponse(
                    textResponse,
                    containerId,
                    fileIds,
                    downloadedFiles.stream().map(Path::toString).toList()
            );

            long end = System.currentTimeMillis();
            log.info("[{}] END success totalTime={} ms", requestId, (end - start));

            return ResponseEntity.ok(body);

        } catch (Exception e) {
            long end = System.currentTimeMillis();
            log.error("[{}] ERROR after {} ms: {}", requestId, (end - start), e.getMessage(), e);
            throw e;
        }
    }

    private Media[] convert(MultipartFile... files) {
        if (files == null || files.length == 0) {
            log.debug("convert(): no files provided");
            return new Media[0];
        }

        log.debug("convert(): converting {} files to Media[]", files.length);

        return Stream.of(files)
                .filter(Objects::nonNull)
                .map(file -> {
                    log.debug("convert(): file='{}', contentType='{}', size={}",
                            file.getOriginalFilename(),
                            file.getContentType(),
                            file.getSize());
                    return new Media(
                            MimeType.valueOf(Objects.requireNonNull(file.getContentType())),
                            file.getResource()
                    );
                })
                .toArray(Media[]::new);
    }

    public record FileGenerationResponse(
            String textResponse,
            String containerId,
            List<String> fileIds,
            List<String> downloadedFiles
    ) {
    }
}