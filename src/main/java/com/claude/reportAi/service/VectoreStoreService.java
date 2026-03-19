package com.claude.reportAi.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VectoreStoreService {

    private final VectorStore vectorStore;

    public VectoreStoreService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<Document> searchRelevantDocuments(String query) {

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(5)
                .similarityThreshold(0.75)
                .build();

        return vectorStore.similaritySearch(request);
    }
}
