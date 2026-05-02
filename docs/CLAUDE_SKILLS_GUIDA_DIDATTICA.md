# Guida didattica alle Claude Skills

Documento aggiornato al 28 aprile 2026.

## 1. Cosa sono le Skill

Le Claude Skills sono cartelle che insegnano a Claude come svolgere meglio un tipo di lavoro ricorrente.

Una skill puo contenere:

- istruzioni operative;
- esempi;
- script eseguibili;
- template;
- documenti di riferimento;
- asset da usare negli output.

In pratica, una skill e un piccolo pacchetto di competenza. Serve quando non vuoi ripetere ogni volta lo stesso prompt lungo o quando vuoi che Claude segua una procedura precisa.

Esempio:

```text
Voglio che Claude generi sempre manuali utente nello stesso stile,
leggendo prima certi file del progetto e controllando certe cose.
```

Questa e una situazione perfetta per una skill.

## 2. Cosa una Skill non e

Una skill non e magia e non e un microservizio.

Una skill:

- non modifica il tuo programma automaticamente;
- non viene eseguita dal backend Spring Boot;
- non diventa un endpoint API;
- non sostituisce codice applicativo;
- non ha poteri illimitati.

Una skill fornisce istruzioni a Claude. Se contiene script, Claude puo decidere di usarli, ma l'esecuzione dipende dall'ambiente, dai permessi e dagli strumenti disponibili.

Questa distinzione e importante: una skill puo aiutare Codex o Claude Code a lavorare sul progetto, ma non diventa parte runtime della tua applicazione ReportAI a meno che tu non costruisca esplicitamente un'integrazione.

## 3. Come funziona il caricamento

Le skill usano un principio chiamato progressive disclosure, cioe caricamento progressivo.

Claude non carica sempre tutto il contenuto di tutte le skill. Di solito il processo e questo:

1. All'avvio legge solo il nome e la descrizione delle skill disponibili.
2. Quando una richiesta sembra pertinente, carica il file `SKILL.md`.
3. Se servono file aggiuntivi, legge solo quelli necessari.

Questo evita di riempire il contesto con istruzioni inutili.

Esempio:

```text
.claude/skills/reportai-user-manual/
  SKILL.md
  references/
    api.md
    stile.md
  templates/
    manuale-base.md
```

Claude puo leggere prima `SKILL.md` e aprire `references/stile.md` solo se sta davvero scrivendo un manuale.

## 4. Dove si salvano le Skill

Ci sono tre posizioni principali.

| Tipo | Percorso | Quando usarla |
|---|---|---|
| Personale | `~/.claude/skills/` | Skill disponibili in tutti i tuoi progetti |
| Di progetto | `.claude/skills/` | Skill condivise nel repository corrente |
| Plugin | bundle installato dal plugin | Skill distribuite tramite plugin |

Nel tuo progetto ReportAI, le skill di progetto stanno qui:

```text
.claude/skills/
```

Esempio gia creato:

```text
.claude/skills/reportai-user-manual/SKILL.md
```

Le skill di progetto sono utili per procedure specifiche del repository, per esempio:

- generare manuali utente ReportAI;
- creare prompt template coerenti;
- fare review dei workflow AI;
- produrre documentazione tecnica nello stile del progetto;
- preparare dati di test per i report.

## 5. Anatomia minima di una Skill

Ogni skill deve avere una cartella e un file `SKILL.md`.

Struttura minima:

```text
nome-skill/
  SKILL.md
```

Esempio:

```markdown
---
name: reportai-user-manual
description: Use this skill when creating Italian user manuals, quick-start guides, FAQs, or onboarding documentation for the ReportAI project.
---

# ReportAI User Manual

## Instructions

When creating user documentation:

1. Read the current project documentation.
2. Verify workflows and sectors from the code.
3. Write in Italian.
4. Keep admin-only actions separate.
5. Include troubleshooting and best practices.
```

Il file e composto da due parti:

- frontmatter YAML;
- corpo Markdown.

## 6. Frontmatter

Il frontmatter e la parte tra `---`.

Campi fondamentali:

```yaml
---
name: nome-della-skill
description: Spiega cosa fa la skill e quando va usata.
---
```

Il campo piu importante e `description`, perche Claude lo usa per capire quando attivare la skill.

Una descrizione debole:

```yaml
description: Per documenti.
```

Una descrizione buona:

```yaml
description: Create Italian user manuals and operational guides for ReportAI. Use when the user asks for documentation, onboarding material, FAQs, or instructions for contract analysis, estimate generation, supplier comparison, or document ingestion workflows.
```

Regola pratica: la descrizione deve dire sia cosa fa la skill sia quando usarla.

## 7. Corpo del file SKILL.md

Il corpo Markdown contiene le istruzioni vere.

Una buona skill include:

- obiettivo;
- quando usarla;
- fonti da leggere;
- procedura;
- stile di output;
- checklist finale;
- esempi brevi.

Esempio di struttura:

```markdown
# Nome Skill

## Purpose

Spiega lo scopo.

## When To Use

Elenca i casi in cui attivarla.

## Workflow

1. Leggi le fonti.
2. Applica la procedura.
3. Verifica il risultato.

## Output

Descrivi il formato atteso.

## Quality Checklist

- Controllo 1
- Controllo 2
- Controllo 3
```

## 8. File opzionali

Una skill puo avere file aggiuntivi.

Struttura piu completa:

```text
my-skill/
  SKILL.md
  references/
    guida-dettagliata.md
    schema-api.md
  scripts/
    valida_output.py
  templates/
    template-report.md
  assets/
    logo.png
```

Uso tipico:

- `references/`: documentazione da leggere solo se serve;
- `scripts/`: utility ripetibili;
- `templates/`: modelli di output;
- `assets/`: immagini, font, file base o risorse.

Non mettere tutto in `SKILL.md`. Se il contenuto diventa lungo, spostalo in `references/`.

## 9. Skill e codice eseguibile

Una skill puo includere script.

Esempio:

```text
invoice-checker/
  SKILL.md
  scripts/
    validate_invoice.py
```

Nel `SKILL.md` puoi scrivere:

````markdown
When validating an invoice JSON, run:

```bash
python scripts/validate_invoice.py input.json
```
````

Pero attenzione: la skill non esegue codice da sola. Claude legge l'istruzione e, se ha accesso agli strumenti adatti, puo eseguire lo script.

Questo significa che:

- l'ambiente deve avere Python, Node o gli strumenti richiesti;
- i permessi devono consentire l'esecuzione;
- lo script deve essere trattato come codice potenzialmente sensibile;
- l'utente o il sistema possono bloccare comandi rischiosi.

Nel caso di ReportAI, una skill potrebbe includere script per:

- validare un JSON di report;
- controllare che un manuale abbia tutte le sezioni;
- confrontare due versioni di prompt;
- generare dati demo;
- verificare che un output `.docx` contenga certe sezioni.

## 10. Skill, CLAUDE.md, comandi, plugin e MCP

Questi concetti sono collegati ma diversi.

| Strumento | A cosa serve |
|---|---|
| `CLAUDE.md` | Istruzioni generali del progetto, caricate spesso come contesto di base |
| Skill | Procedura specializzata caricata solo quando serve |
| Slash command | Comando invocato esplicitamente dall'utente |
| Plugin | Pacchetto che puo distribuire skill, strumenti e integrazioni |
| MCP | Protocollo per collegare strumenti esterni e risorse a un agente |

Una differenza pratica:

```text
CLAUDE.md = regole generali del progetto
Skill = procedura specifica
MCP = accesso a strumenti o dati esterni
Plugin = pacchetto installabile che puo contenere skill e tool
```

Esempio:

- Metti in `CLAUDE.md`: "Il progetto usa Java 21 e Spring Boot".
- Metti in una skill: "Come generare un manuale utente ReportAI".
- Usa MCP/tooling: per leggere database, browser, file o servizi esterni.

## 11. Quando creare una Skill

Crea una skill quando:

- ripeti spesso lo stesso prompt lungo;
- vuoi standardizzare una procedura;
- vuoi condividere una competenza con il team;
- vuoi che Claude segua checklist precise;
- vuoi separare una procedura lunga dalle istruzioni generali;
- vuoi includere script o template riusabili.

Non creare una skill quando:

- l'attivita e una tantum;
- bastano due righe di istruzioni;
- la procedura cambia ogni volta;
- stai mettendo dentro informazioni generiche che Claude conosce gia;
- vuoi compensare codice applicativo mancante.

## 12. Buone pratiche

Skill efficaci:

- sono piccole e focalizzate;
- risolvono un compito ripetibile;
- hanno una descrizione molto chiara;
- contengono esempi concreti;
- dicono cosa verificare prima di finire;
- rimandano a reference esterne solo quando serve;
- non contengono segreti;
- non includono file inutili.

Skill deboli:

- sono troppo generiche;
- hanno descrizioni vaghe;
- cercano di fare troppe cose;
- duplicano documentazione enorme;
- contengono script non spiegati;
- non indicano quando usarle.

## 13. Sicurezza

Le skill possono essere potenti, soprattutto se includono script.

Regole di sicurezza:

- usa skill solo da fonti fidate;
- leggi sempre gli script prima di eseguirli;
- non mettere API key, password o token nelle skill;
- evita comandi distruttivi;
- limita lo scope della skill;
- separa istruzioni operative da credenziali e configurazioni sensibili;
- se una skill modifica file, deve spiegare esattamente quali file puo toccare.

In Claude Code esiste anche il campo `allowed-tools`, che puo limitare gli strumenti usabili da una skill.

Esempio:

```yaml
---
name: safe-reader
description: Read-only project inspection skill. Use when reviewing files without making changes.
allowed-tools: Read, Grep, Glob
---
```

Questo serve per skill di sola lettura o workflow sensibili.

## 14. Esempio didattico per ReportAI

Questa skill insegna a creare documentazione utente per ReportAI.

Percorso:

```text
.claude/skills/reportai-user-manual/SKILL.md
```

Contenuto semplificato:

```markdown
---
name: reportai-user-manual
description: Use this skill when creating or updating Italian user-facing documentation for the ReportAI project, including user manuals, quick-start guides, FAQs, onboarding guides, and operational instructions for document analysis workflows.
---

# ReportAI User Manual Skill

## Purpose

Create clear Italian documentation for non-technical ReportAI users.

## Source Of Truth

Before writing, inspect:

- DESCRIZIONE_APPLICAZIONE.md
- GUIDA_INSTALLAZIONE.md
- WorkflowRegistry.java
- SectorProfileRegistry.java
- ModelChatClientFactory.java

## Writing Style

- Write in Italian.
- Use direct operational language.
- Keep admin-only actions separate.
- Include troubleshooting.
```

Questa e una buona skill perche:

- ha uno scopo specifico;
- indica quando usarla;
- dice quali file leggere;
- definisce lo stile;
- include una checklist.

## 15. Mini laboratorio

### Esercizio 1: creare una skill semplice

Crea:

```text
.claude/skills/reportai-review/SKILL.md
```

Contenuto:

```markdown
---
name: reportai-review
description: Review ReportAI backend changes for bugs, security risks, missing tests, and inconsistencies with existing Spring Boot patterns.
---

# ReportAI Review

## Instructions

When reviewing code:

1. Check changed Java files first.
2. Look for security regressions.
3. Verify workflow and sector behavior.
4. Check report generation changes carefully.
5. Report findings with file and line references.
```

Poi chiedi a Claude:

```text
Fai una review delle modifiche al backend.
```

Se la descrizione e buona, Claude dovrebbe capire che quella skill e pertinente.

### Esercizio 2: aggiungere una reference

Aggiungi:

```text
.claude/skills/reportai-review/references/security.md
```

Nel `SKILL.md` scrivi:

```markdown
For authentication or JWT changes, read `references/security.md`.
```

In questo modo Claude non carica la reference sempre, ma solo quando serve.

### Esercizio 3: aggiungere uno script

Aggiungi:

```text
.claude/skills/reportai-review/scripts/check_no_secrets.py
```

Lo script potrebbe cercare pattern sospetti come:

- `sk-ant-`
- `JWT_SECRET=`
- `GOOGLE_APPLICATION_CREDENTIALS`
- chiavi JSON private

Nel `SKILL.md` puoi indicare:

```markdown
Before finalizing security-sensitive reviews, run `scripts/check_no_secrets.py` if available.
```

## 16. Checklist per creare una Skill nuova

Prima di considerare una skill pronta:

- Il nome e corto, minuscolo e con trattini.
- La descrizione spiega cosa fa e quando usarla.
- Il compito e specifico, non generico.
- Il `SKILL.md` non e troppo lungo.
- Le reference sono separate quando il materiale cresce.
- Gli script sono documentati e sicuri.
- Non ci sono segreti.
- Ci sono esempi realistici.
- C'e una checklist finale.
- La skill e stata provata con una richiesta reale.

## 17. Fonti ufficiali consultate

- Anthropic Claude Code, Agent Skills: https://docs.claude.com/en/docs/claude-code/skills
- Anthropic Claude Code, Extend Claude with skills: https://code.claude.com/docs/en/skills
- Anthropic Agent Skills overview: https://docs.claude.com/en/docs/agents-and-tools/agent-skills
- Claude.ai Skills overview: https://claude.com/docs/skills/overview
- Claude.ai Creating custom skills: https://claude.com/docs/skills/how-to
- Anthropic API Create Skill: https://docs.claude.com/en/api/skills/create-skill
