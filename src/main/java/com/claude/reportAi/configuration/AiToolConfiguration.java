package com.claude.reportAi.configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vertexai.VertexAI;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;

@Configuration
public class AiToolConfiguration {

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
            @Value("${spring.ai.vertex.ai.gemini.project-id:nexoniq}") String projectId,
            @Value("${spring.ai.vertex.ai.gemini.location:us-central1}") String location,
            @Value("${spring.ai.vertex.ai.gemini.credentials.location:classpath:config/nexoniq-bb9e971552b6.json}") Resource credentialsResource) throws IOException {

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
