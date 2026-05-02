# reportai-commit

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

4. **Verifica prima di procedere**
   - Mostra all'utente: lista dei file che verranno committati e il messaggio di commit proposto
   - Chiedi conferma esplicita prima di eseguire il commit

5. **Esegui il commit**
   - Aggiungi i file con `git add` selettivo (mai `git add .` o `git add -A` su file sensibili come `.env`)
   - Crea il commit con il messaggio approvato usando HEREDOC per preservare la formattazione
   - Appendi `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>` al messaggio

6. **Pusha sul branch corrente**
   - Esegui `git push` sul branch attivo
   - Se il branch non ha upstream, usa `git push -u origin <branch>`
   - Riporta l'esito del push all'utente

## Regole di sicurezza

- Non committare mai file che potrebbero contenere segreti: `.env`, `*.key`, `*.pem`, `application-prod.properties`, credenziali hardcoded
- Non usare `--no-verify` o `--force` salvo esplicita richiesta dell'utente
- Non fare force push su `main` o `master`
- Se ci sono conflitti o lo stato del repo è ambiguo, fermarsi e chiedere all'utente

## Note

- Se non ci sono modifiche da committare, informare l'utente e non procedere
- Se l'utente ha già file in staging (`git add` già eseguito), rispettare quella selezione senza aggiungere altri file
