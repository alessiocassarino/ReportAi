-- V021: Add USER-role prompt templates for OIL_GAS sector.
-- These are extracted from the previously hardcoded buildSectionUserPrompt,
-- buildSynthesisPrompt, buildUserPrompt, buildExtractionPrompt and buildComparisonPrompt methods.
-- Placeholders: {SECTION_TITLE}, {SECTION_CONTENT}, {ORIGINAL_FILENAME}, {SECTIONS_ANALYSIS}

-- ─────────────────────────────────────────────────────────────
-- CONTRACT RISK ANALYSIS
-- ─────────────────────────────────────────────────────────────

INSERT INTO prompt_template (template_key, workflow_id, sector, version, role, prompt_name, body) VALUES (
    'contract-risk-section-user',
    'contract-risk-analysis',
    'OIL_GAS',
    'v1',
    'USER',
    'Prompt Sezione Contratto - Oil & Gas',
    $$Analizza la seguente sezione del contratto dal punto di vista del Contractor.

Sezione: {SECTION_TITLE}

---
{SECTION_CONTENT}
---

Rispondi con questo JSON (nessun testo aggiuntivo):
{
  "sezione": "<titolo della sezione>",
  "rischi": [
    {
      "clausola": "<riferimento clausola, es. 5.3>",
      "descrizione": "<descrizione del rischio per il Contractor>",
      "livello": "ALTO|MEDIO|BASSO",
      "raccomandazione": "<proposta di modifica o tutela>"
    }
  ],
  "clausole_mancanti": ["<clausola assente ma necessaria>"],
  "sommario": "<sintesi in 1-2 frasi>"
}$$
);

INSERT INTO prompt_template (template_key, workflow_id, sector, version, role, prompt_name, body) VALUES (
    'contract-risk-synthesis-user',
    'contract-risk-analysis',
    'OIL_GAS',
    'v1',
    'USER',
    'Sintesi Contratto - Oil & Gas',
    $$Hai analizzato sezione per sezione il contratto "{ORIGINAL_FILENAME}".
Di seguito trovi i risultati JSON di ogni sezione analizzata.

[RISULTATI ANALISI PER SEZIONE]
{SECTIONS_ANALYSIS}

Restituisci un unico oggetto JSON con questa struttura esatta (nessun testo prima o dopo):
{
  "valutazione_complessiva": "SFAVOREVOLE|EQUILIBRATO|FAVOREVOLE",
  "raccomandazione_finale": "FIRMARE|NEGOZIARE|RIFIUTARE",
  "executive_summary": "<sintesi in 3-5 frasi per il board>",
  "rischi_critici": [
    {"sezione": "", "clausola": "", "descrizione": "", "livello": "ALTO|MEDIO|BASSO"}
  ],
  "matrice_rischi": [
    {"sezione": "", "clausola": "", "rischio": "", "livello": "ALTO|MEDIO|BASSO", "azione": ""}
  ],
  "analisi_sezioni": [
    {
      "titolo": "",
      "sommario": "",
      "rischi": [
        {"clausola": "", "descrizione": "", "livello": "ALTO|MEDIO|BASSO", "raccomandazione": ""}
      ],
      "clausole_mancanti": [""]
    }
  ],
  "top5_clausole": [
    {"riferimento": "", "testo_attuale": "", "testo_proposto": ""}
  ],
  "clausole_mancanti_globali": [""]
}

Regole:
- matrice_rischi ordinata per livello decrescente (ALTO → MEDIO → BASSO)
- rischi_critici: massimo 5 rischi, solo i più gravi
- top5_clausole: le 5 clausole più critiche da rinegoziare con testo alternativo proposto
- Lingua: italiano$$
);

-- ─────────────────────────────────────────────────────────────
-- ESTIMATE GENERATION
-- ─────────────────────────────────────────────────────────────

INSERT INTO prompt_template (template_key, workflow_id, sector, version, role, prompt_name, body) VALUES (
    'estimate-generation-user',
    'estimate-generation',
    'OIL_GAS',
    'v1',
    'USER',
    'Istruzioni Preventivo EPC - Oil & Gas',
    $$[ISTRUZIONI]
Genera un preventivo dettagliato per questo progetto.
Voce VI.a Overhead (OH): 6% del subtotale I-V. Voce VI.b Contingency: 2% del subtotale I-V. Voce VI.c Costi Finanziari e Assicurazioni: 3-5% del subtotale I-V.
Includi sempre: mobilizzazione, costruzione, subcontratti (Forniture + Subappalti separati), indiretti, vitto/alloggio, OH/contingency/finanziari.
Tutti gli importi in EUR. Il report deve essere dettagliato e professionale.

Restituisci ESCLUSIVAMENTE questo JSON:
{
  "nazione": "...",
  "tipo_progetto": "...",
  "cambio_eur_usd": 1.08,
  "executive_summary": "3-5 frasi per top management",
  "quadro_economico": [
    {"voce": "I - Mobilizzazione e Temporary Facilities", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
    {"voce": "II - Costruzione (mezzi + personale + carburante)", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
    {"voce": "III - Subcontratti e Forniture", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
    {"voce": "IV - Indiretti", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
    {"voce": "V - Vitto e Alloggio", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
    {"voce": "SUBTOTALE I-V", "importo_usd": 0, "percentuale": 100.0, "note": ""},
    {"voce": "VI.a - Overhead / OH (6%)", "importo_usd": 0, "percentuale": 6.0, "note": "6% su subtotale I-V"},
    {"voce": "VI.b - Contingency (2%)", "importo_usd": 0, "percentuale": 2.0, "note": "2% su subtotale I-V"},
    {"voce": "VI.c - Costi Finanziari e Assicurazioni (4%)", "importo_usd": 0, "percentuale": 4.0, "note": "3-5% su subtotale I-V"},
    {"voce": "VII - COSTO TOTALE", "importo_usd": 0, "percentuale": 0.0, "note": ""},
    {"voce": "VIII - PREZZO (margine 8%)", "importo_usd": 0, "percentuale": 0.0, "note": ""}
  ],
  "kpi": {
    "prezzo_totale_usd": 0,
    "prezzo_al_km": null,
    "prezzo_al_metro": null,
    "prezzo_inch_metro": null,
    "personale_diretto": 0,
    "personale_indiretto": 0,
    "personale_totale": 0,
    "durata_mesi": 0,
    "ore_uomo_stimate": 0
  },
  "analisi_dettaglio": [
    {
      "categoria": "Mobilizzazione e Temporary Facilities",
      "importo_usd": 0,
      "descrizione": "...",
      "produttivita_applicata": null,
      "voci_principali": [
        {"descrizione": "...", "quantita": "...", "costo_unitario_usd": "...", "fonte_dato": "AZIENDALE|BENCHMARK|ASSUNZIONE", "totale_usd": 0}
      ],
      "assunzioni": ["..."],
      "rischi": ["..."]
    }
  ],
  "imposte_e_oneri": {
    "wht_percentuale": "...",
    "vat_percentuale": "...",
    "customs": "...",
    "impatto_stimato_usd": 0,
    "note": "..."
  },
  "rischi_principali": [
    {"categoria": "...", "descrizione": "...", "impatto": "ALTO|MEDIO|BASSO", "mitigazione": "..."}
  ],
  "cronoprogramma_sintetico": [
    {"fase": "...", "durata": "...", "settimane": "...", "note": "..."}
  ],
  "note_finali": "..."
}$$
);

-- ─────────────────────────────────────────────────────────────
-- PRICE COMPARISON
-- ─────────────────────────────────────────────────────────────

INSERT INTO prompt_template (template_key, workflow_id, sector, version, role, prompt_name, body) VALUES (
    'price-comparison-extraction-user',
    'price-comparison',
    'OIL_GAS',
    'v1',
    'USER',
    'Estrai Dati Offerta - Oil & Gas',
    $$Estrai TUTTE le informazioni rilevanti dell'offerta in questo formato JSON:
{
  "nome_fornitore": "...",
  "riferimento_offerta": "...",
  "data_offerta": "...",
  "valuta_principale": "USD|EUR|...",
  "moduli_offerti": [
    {
      "tipo": "...",
      "codice": "...",
      "descrizione": "...",
      "quantita": 0,
      "prezzo_unitario": "...",
      "prezzo_totale": "...",
      "dimensioni": "...",
      "peso_kg": "...",
      "specifiche_chiave": ["...", "..."],
      "incluso": ["...", "..."],
      "escluso": ["...", "..."],
      "lead_time_settimane": "...",
      "garanzia": "..."
    }
  ],
  "riepilogo_economico": {
    "subtotale": "...",
    "sconti": "...",
    "trasporto_installazione": "...",
    "totale": "...",
    "valuta": "...",
    "note_prezzo": "..."
  },
  "termini_commerciali": {
    "termini_pagamento": "...",
    "condizioni_consegna": "...",
    "validita_offerta": "...",
    "incoterms": "...",
    "penali_ritardo": "...",
    "luogo_consegna": "..."
  },
  "specifiche_tecniche_generali": {
    "standard_riferimento": ["...", "..."],
    "certificazioni": ["...", "..."],
    "materiali_principali": ["...", "..."],
    "classe_pressione": "...",
    "temperatura_operativa": "...",
    "classificazione_area": "..."
  },
  "referenze_oil_gas": ["...", "..."],
  "punti_distintivi": ["...", "..."],
  "limitazioni_esclusioni": ["...", "..."],
  "note_importanti": "..."
}$$
);

INSERT INTO prompt_template (template_key, workflow_id, sector, version, role, prompt_name, body) VALUES (
    'price-comparison-synthesis-user',
    'price-comparison',
    'OIL_GAS',
    'v1',
    'USER',
    'Confronto Offerte - Oil & Gas',
    $$[STEP 3 — CAMPO 'note' OBBLIGATORIO PER OGNI CELLA]
Il campo 'note' in tabella_comparativa DEVE contenere evidenze specifiche estratte dai documenti:
• Prezzo / Lead Time: fonte del dato (pagina/sezione), valuta originale, cambio applicato.
• Qualità Tecnica: standard dichiarati (API, ASME, ISO...), certificazioni, materiali, classe pressione.
• Termini Commerciali: termini pagamento esatti, Incoterms, validità offerta, penali, luogo consegna.
• Referenze: clienti oil&gas nominati, anni di attività, referenze documentate, certificazioni aziendali.
Un campo 'note' vuoto o con solo il numero del punteggio NON è accettabile.

Restituisci ESCLUSIVAMENTE questo JSON (senza markdown):
{
  "titolo_progetto": "Valutazione Fornitori — Confronto Offerte Moduli Oil & Gas",
  "data_valutazione": "GG/MM/AAAA",
  "numero_fornitori": 0,
  "executive_summary": "3-5 frasi concise per il top management con la raccomandazione chiave e i principali driver della scelta",
  "tabella_comparativa": [
    {
      "criterio": "Prezzo Totale",
      "peso_percentuale": 30,
      "unita": "USD",
      "valori_fornitori": [{"fornitore": "...", "valore": "250.150 USD", "punteggio": 0, "note": "Fonte: pag. X sezione riepilogo economico; valuta originale EUR convertita a 1.08; include trasporto; escluso montaggio"}]
    },
    {
      "criterio": "Lead Time",
      "peso_percentuale": 20,
      "unita": "settimane",
      "valori_fornitori": [{"fornitore": "...", "valore": "7 settimane", "punteggio": 0, "note": "Fonte: pag. X clausola consegna; breakdown: 4 sett. produzione + 3 sett. spedizione; data confermata in lettera allegata"}]
    },
    {
      "criterio": "Qualità Tecnica",
      "peso_percentuale": 25,
      "unita": "punteggio/100",
      "valori_fornitori": [{"fornitore": "...", "valore": "Conf. API 6D+ASME, ISO 9001, Duplex 2205", "punteggio": 0, "note": "Standard dichiarati (API 6D, ASME B16.5), certificazioni (ISO 9001/14001, ATEX), materiali specificati, classe pressione, esperienze O&G documentate"}]
    },
    {
      "criterio": "Termini Commerciali",
      "peso_percentuale": 15,
      "unita": "punteggio/100",
      "valori_fornitori": [{"fornitore": "...", "valore": "30/70 DAP, validità 60gg", "punteggio": 0, "note": "Termini pagamento esatti, Incoterms dichiarati, validità offerta in giorni, penali ritardo %, garanzia prodotto, luogo consegna, condizioni forza maggiore"}]
    },
    {
      "criterio": "Referenze e Affidabilità",
      "peso_percentuale": 10,
      "unita": "punteggio/100",
      "valori_fornitori": [{"fornitore": "...", "valore": "3 referenze O&G, 20 anni settore", "punteggio": 0, "note": "Clienti O&G nominati (es. Eni, Total, Shell), anni di attività dichiarati, referenze documentate, certificazioni aziendali, forniture simili completate"}]
    }
  ],
  "analisi_fornitori": [
    {
      "nome": "...",
      "rank": 1,
      "score_totale": 0,
      "punteggio_economico": 0,
      "punteggio_tecnico": 0,
      "punteggio_commerciale": 0,
      "prezzo_totale_offerto": "...",
      "lead_time": "...",
      "conformita_requisiti": "CONFORME|PARZIALMENTE_CONFORME|NON_CONFORME",
      "punti_di_forza": ["...", "..."],
      "punti_di_debolezza": ["...", "..."],
      "rischi_principali": ["...", "..."],
      "opportunita_negoziazione": ["...", "..."],
      "note_tecniche": "..."
    }
  ],
  "matrice_valutazione": [
    {
      "fornitore": "...",
      "criteri": [
        {"nome": "Prezzo", "peso": 30, "punteggio": 0, "ponderato": 0.0},
        {"nome": "Lead Time", "peso": 20, "punteggio": 0, "ponderato": 0.0},
        {"nome": "Qualità Tecnica", "peso": 25, "punteggio": 0, "ponderato": 0.0},
        {"nome": "Termini Commerciali", "peso": 15, "punteggio": 0, "ponderato": 0.0},
        {"nome": "Referenze", "peso": 10, "punteggio": 0, "ponderato": 0.0}
      ],
      "totale_ponderato": 0.0,
      "rank": 1
    }
  ],
  "analisi_rischi": [
    {
      "fornitore": "...",
      "rischio": "...",
      "impatto": "ALTO|MEDIO|BASSO",
      "probabilita": "ALTA|MEDIA|BASSA",
      "mitigazione": "..."
    }
  ],
  "raccomandazione": {
    "fornitore_consigliato": "...",
    "motivazione": "...",
    "saving_stimato_vs_media": "...",
    "condizioni_per_accettazione": ["...", "..."],
    "punti_da_negoziare": ["...", "..."],
    "fornitore_alternativo": "...",
    "motivazione_alternativo": "..."
  },
  "note_finali": "..."
}$$
);
