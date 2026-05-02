---
name: reportai-commit
description: Analizza le modifiche locali, genera un messaggio di commit contestuale in italiano, committa e pusha sul branch corrente del progetto ReportAI.
---

# ReportAI Commit Assistant

Analizza le modifiche locali, genera un messaggio di commit contestuale, committa e pusha sul branch corrente.

## Steps

1. **Raccogli lo stato del repository**
   - Esegui `git status` per vedere file modificati, aggiunti e non tracciati
   - Esegui `git diff` per vedere le modifiche dettagliate sui file già tracciati
   - Esegui `git diff --cached` per vedere eventuali modifiche già in staging
   - Esegui `git log --oneline -5` per capire lo stile dei commit precedenti del progetto

2. **Analizza le modifiche**
   - Leggi il diff completo e identifica la natura delle modifiche: nuovo feature, bugfix, refactor, config, dipendenze, documentazione
   - Considera il contesto del progetto ReportAI (Spring Boot + React, analisi documenti oil & gas / EPC / energie rinnovabili)
   - Raggruppa le modifiche per area (backend, frontend, configurazione, ecc.)

3. **Genera il messaggio di commit**
   - Segui lo stile dei commit già presenti nel log (italiano se il progetto usa messaggi italiani, inglese se usa inglesi)
   - Il messaggio deve essere conciso e descrittivo: spiega COSA è cambiato e PERCHÉ, non il come
   - Formato: una riga di titolo breve (max 72 caratteri), eventuale corpo se le modifiche sono complesse

4. **Mostra un riepilogo all'utente**
   - Mostra la lista dei file che verranno committati e il messaggio di commit generato
   - Non chiedere conferma: procedi direttamente con il commit e il push

5. **Esegui il commit**
   - Se NON ci sono file già in staging (`git diff --cached` è vuoto), aggiungi i file modificati uno per uno con `git add <file>` — mai `git add .` o `git add -A`
   - Se ci sono già file in staging, rispetta quella selezione senza aggiungere altri file
   - Crea il commit con il messaggio approvato. Su PowerShell usa la here-string:
     ```powershell
     git commit -m @'
     Titolo del commit

     Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
     '@
     ```
     Oppure tramite il tool Bash usa la sintassi HEREDOC:
     ```bash
     git commit -m "$(cat <<'EOF'
     Titolo del commit

     Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
     EOF
     )"
     ```

6. **Pusha sul branch corrente**
   - Esegui `git push` sul branch attivo
   - Se il branch non ha upstream, usa `git push -u origin <branch>`
   - Riporta l'esito del push all'utente

## Regole di sicurezza

- Non committare mai file che potrebbero contenere segreti: `.env`, `*.key`, `*.pem`, `application-prod.properties`, credenziali hardcoded
- Non usare `--no-verify` o `--force` salvo esplicita richiesta dell'utente
- Non fare force push su `main` o `master`
- Non usare mai `git add .` o `git add -A` — aggiungi sempre i file esplicitamente per nome
- Se ci sono conflitti o lo stato del repo è ambiguo, fermarsi e chiedere all'utente

## Note

- Se non ci sono modifiche da committare, informare l'utente e non procedere
- La skill esegue commit e push in automatico senza chiedere conferma; mostra solo un riepilogo prima di procedere
