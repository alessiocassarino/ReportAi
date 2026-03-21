package com.claude.reportAi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 🚀 Classe di avvio principale dell'applicazione ReportAI
 *
 * ReportAiApplication è il punto di ingresso dell'applicazione Spring Boot.
 * Questa classe avvia il container Spring e inizializza tutti i componenti dell'applicazione.
 *
 * Funzionalità principali:
 * - Avvia il server Spring Boot sulla porta 8080
 * - Carica automaticamente tutte le configurazioni Spring
 * - Inizializza il contesto applicativo
 * - Connette il database PostgreSQL
 * - Inizializza il client Claude AI
 * - Connette il vector store PostgreSQL/PGVector
 *
 * Dipendenze caricate automaticamente:
 * ✅ Spring Data JPA (database)
 * ✅ Spring Web (REST API)
 * ✅ Spring AI (Claude, Ollama, PGVector)
 * ✅ Configurazioni personalizzate
 * ✅ Web Search Provider (se abilitato)
 *
 * Per avviare l'applicazione:
 * {@code
 * java -jar target/reportAi-0.0.1-SNAPSHOT.jar
 * }
 *
 * URL di accesso:
 * - API REST: http://localhost:8080/api/reports/generate
 * - Health: http://localhost:8080/actuator/health
 *
 * @author ReportAI Team
 * @version 1.2.0
 * @since 2025-01-15
 */
@SpringBootApplication
public class ReportAiApplication {

	/**
	 * 📍 Metodo main - Punto di ingresso dell'applicazione
	 *
	 * Questo metodo viene invocato quando l'applicazione viene eseguita come JAR.
	 * Spring Boot gestisce automaticamente:
	 * - Scanning dei componenti nel package com.claude.reportAi
	 * - Configurazione automatica di Spring
	 * - Connessione al database
	 * - Inizializzazione dei servizi
	 *
	 * @param args Argomenti da riga di comando (es: --server.port=9090)
	 */
	public static void main(String[] args) {
		// ⚙️ Avvia l'applicazione Spring Boot
		SpringApplication.run(ReportAiApplication.class, args);
	}

}
