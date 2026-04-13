package com.claude.reportAi.controller;

import com.claude.reportAi.service.ModelChatClientFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint centralizzato per la lista dei modelli AI supportati.
 * Nessuna restrizione di ruolo: accessibile a tutti gli utenti autenticati.
 *
 * GET /api/models → lista di ModelInfo
 */
@RestController
@RequestMapping("/api/models")
public class ModelsController {

    @GetMapping
    public ResponseEntity<List<ModelChatClientFactory.ModelInfo>> listModels() {
        return ResponseEntity.ok(ModelChatClientFactory.SUPPORTED_MODELS);
    }
}
