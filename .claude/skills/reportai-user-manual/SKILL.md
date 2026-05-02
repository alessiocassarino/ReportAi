---
name: reportai-user-manual
description: Use this skill when creating or updating Italian user-facing documentation for the ReportAI project, including user manuals, quick-start guides, FAQs, onboarding guides, and operational instructions for document analysis workflows.
---

# ReportAI User Manual Skill

## Purpose

Create clear Italian documentation for non-technical ReportAI users. Focus on what the user can do in the application, what files they need, what result they should expect, and how to recover from common problems.

## When To Use

Use this skill for requests such as:

- "Crea un manuale utente per ReportAI"
- "Scrivi una guida rapida per gli utenti"
- "Documenta come usare analisi contratti, preventivi e confronto offerte"
- "Prepara FAQ o istruzioni operative per il cliente"

## Source Of Truth

Before writing, inspect the current project instead of relying only on memory:

- `DESCRIZIONE_APPLICAZIONE.md` for product overview and user-facing features.
- `GUIDA_INSTALLAZIONE.md` only for access/deployment notes that affect users.
- `src/main/java/com/claude/reportAi/controller` for available API capabilities.
- `src/main/java/com/claude/reportAi/pipeline/WorkflowRegistry.java` for active workflows.
- `src/main/java/com/claude/reportAi/sector/SectorProfileRegistry.java` for active sectors.
- `src/main/java/com/claude/reportAi/service/ModelChatClientFactory.java` for available AI models.

If documentation and code disagree, prefer the code and mention the discrepancy briefly.

## Recommended Structure

For a full user manual, include:

1. Panoramica dell'applicazione
2. Accesso e ruoli utente
3. Flusso generale di lavoro
4. Base documentale e caricamento file
5. Analisi rischi contratto
6. Generazione preventivo
7. Confronto offerte fornitori
8. Scelta del modello AI
9. Storico, stato job e download risultati
10. Problemi comuni e soluzioni
11. Buone pratiche operative

For a short guide, reduce the structure to: accesso, preparazione file, avvio analisi, monitoraggio, download, troubleshooting.

## Writing Style

- Write in Italian.
- Use direct operational language: "Aprire", "Caricare", "Selezionare", "Scaricare".
- Avoid implementation jargon unless the audience is technical.
- Explain statuses such as `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, and `CANCELLED` in plain language.
- Make clear that generated reports require human review before business use.

## Quality Checklist

Before finishing:

- Verify workflow names and sectors against the current code.
- Do not expose real secrets, API keys, JWT secrets, or local credential filenames.
- Keep admin-only actions separate from normal user actions.
- Include supported input formats and practical file-preparation advice.
- Include expected outputs, especially `.docx` report downloads.
- Add a troubleshooting table for common user errors.
