package com.claude.reportAi.configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vertexai.VertexAI;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

@Configuration
public class AiToolConfiguration {

    @Bean
    @Primary
    AnthropicApi anthropicApi(
            @Value("${spring.ai.anthropic.api-key}") String apiKey,
            @Value("${spring.ai.anthropic.base-url:https://api.anthropic.com}") String baseUrl) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMinutes(60));
        factory.setConnectTimeout(Duration.ofSeconds(30));


        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(factory);


        return AnthropicApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();
    }

    /**
     * Sovrascrive il bean auto-configurato da Spring AI (che usa @ConditionalOnMissingBean).
     * Il bean auto-configurato imposta DEFAULT_TEMPERATURE=0.8 nelle defaultOptions anche se
     * non specificata in application.properties — il che causa un errore 400 su claude-opus-4-7
     * e modelli futuri che deprecano il parametro. Creando il bean qui con temperature=null,
     * il parametro non viene mai incluso nella richiesta di default; il ModelChatClientFactory
     * lo aggiunge selettivamente solo per i modelli che lo supportano.
     */
    @Bean
    @Primary
    AnthropicChatModel anthropicChatModel(
            AnthropicApi anthropicApi,
            ObjectProvider<ToolCallingManager> toolCallingManagerProvider,
            ObjectProvider<RetryTemplate> retryTemplateProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            @Value("${spring.ai.anthropic.chat.options.max-tokens:8192}") Integer maxTokens) {

        AnthropicChatOptions defaultOptions = AnthropicChatOptions.builder()
                .model(AnthropicChatModel.DEFAULT_MODEL_NAME)
                .maxTokens(maxTokens)
                // temperature omessa intenzionalmente: null → non serializzata → non inviata all'API
                .build();

        return new AnthropicChatModel(
                anthropicApi,
                defaultOptions,
                toolCallingManagerProvider.getIfAvailable(ToolCallingManager.builder()::build),
                retryTemplateProvider.getIfAvailable(() -> RetryUtils.DEFAULT_RETRY_TEMPLATE),
                observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    @Qualifier("anthropicChatClient")
    ChatClient anthropicChatClient(@Qualifier("anthropicChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * Bean VertexAI con credenziali caricate esplicitamente dal service account JSON.
     * Sovrascrive il bean auto-configurato di Spring AI (@ConditionalOnMissingBean),
     * garantendo che venga usato il service account e non Application Default Credentials.
     */
    @Bean
    public VertexAI vertexAI(
            @Value("${spring.ai.vertex.ai.gemini.project-id:progetto-ai-494215}") String projectId,
            @Value("${spring.ai.vertex.ai.gemini.location:us-central1}") String location,
            @Value("${spring.ai.vertex.ai.gemini.credentials.location:classpath:config/progetto-ai-494215-1a94e8abc771.json}") Resource credentialsResource) throws IOException {

        GoogleCredentials credentials = GoogleCredentials
                .fromStream(credentialsResource.getInputStream())
                .createScoped("https://www.googleapis.com/auth/cloud-platform");

        return new VertexAI.Builder()
                .setProjectId(projectId)
                .setLocation(location)
                .setCredentials(credentials)
                .build();
    }

    @Bean
    @Qualifier("geminiChatClient")
    ChatClient geminiChatClient(VertexAiGeminiChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

}
