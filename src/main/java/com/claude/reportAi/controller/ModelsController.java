package com.claude.reportAi.controller;

import com.claude.reportAi.service.ModelChatClientFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint centralizzato per la lista dei modelli AI supportati.
 * Nessuna restrizione di ruolo: accessibile a tutti gli utenti autenticati.
 *
 * GET /api/models          → tutti i modelli
 * GET /api/models?service= → modelli filtrati per servizio (es. "estimates")
 */
@RestController
@RequestMapping("/api/models")
public class ModelsController {

    @GetMapping
    public ResponseEntity<List<ModelChatClientFactory.ModelInfo>> listModels(
            @RequestParam(required = false) String service) {
        return ResponseEntity.ok(ModelChatClientFactory.getModelsForService(service));
    }
}
