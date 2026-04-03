# 🚀 Docker Development Workflow

Questa guida spiega come aggiornare il backend senza perdere i dati (Ollama, DB, ecc).

## 📋 Il Problema

Quando fai `docker-compose down -v`, viene rimosso il flag `-v` che cancella **tutti i volumi**:
- ❌ Modello Ollama scaricato di nuovo
- ❌ Database ricreato (ma le tabelle Liquibase ricreate)
- ❌ Tutti i dati temporanei persi

## ✅ La Soluzione

### 👨‍💻 Durante lo Sviluppo (CONSIGLIATO)

Usa questo comando ogni volta che modifichi il backend:

**Windows:**
```bash
.\dev-build.bat
```

**macOS/Linux:**
```bash
./be-build.sh
```

Questo fa:
1. ✅ Ferma i container (senza rimuovere volumi)
2. ✅ Ricompila solo l'immagine del backend
3. ✅ Riavvia tutti i servizi
4. ✅ **Ollama rimane intatto** ⚡
5. ✅ **Database rimane intatto** ⚡

### 🔄 Workflow Completo di Sviluppo

```bash
# 1. Primo avvio (una sola volta)
docker-compose up -d --build

# 2. Modifica il codice backend

# 3. Aggiorna il backend (mantieni tutto)
.\dev-build.bat

# 4. Modifica il codice frontend e compila in dist/
cd frontend
npm run build
cd ..

# 5. Reload automatico di Nginx (il volume è montato)

# 6. Ripeti dal punto 2...
```

### 🗑️ Reset Completo (Solo se necessario)

Se vuoi una **clean slate** (cancella TUTTO):

**Windows:**
```bash
.\clean-slate.bat
```

**macOS/Linux:**
```bash
./Pulizia_Totale_Container.sh
```

Questo:
- ❌ Rimuove container
- ❌ Rimuove volumi
- ❌ Rimuove immagini
- ✅ Libera tantissimo spazio disco

## 📊 Comandi Manuali

Se preferisci farlo manualmente:

| Comando | Effetto |
|---------|---------|
| `docker-compose down` | ✅ Ferma senza rimuovere volumi |
| `docker-compose down -v` | ❌ Ferma e rimuove volumi |
| `docker-compose build spring-boot` | Ricompila solo il backend |
| `docker-compose build` | Ricompila tutto |
| `docker-compose up -d` | Avvia (preserva volumi) |

## 🎯 Tempo di Build

- **Prima volta**: ~2-3 minuti (scarica dipendenze Maven)
- **Subsequent**: ~30-60 secondi (cache Maven)
- **Solo backend changed**: ~15-20 secondi

## 🔍 Debugging

Se il container non si avvia, controlla i log:

```bash
# Backend logs
docker logs report-ai-backend

# Database logs
docker logs report-ai-db

# Ollama logs
docker logs report-ai-ollama

# Vedi tutti i container
docker ps -a

# Vedi tutti i volumi
docker volume ls
```

## 💡 Pro Tips

1. **Fast iteration**: Modifica il codice, esegui `dev-build.bat`, test
2. **Database queries**: Puoi connetterti a `localhost:5400` con PgAdmin
3. **Ollama cache**: Il modello rimane dopo il restart (~7GB)
4. **Frontend**: Modifica e compila in `frontend/dist/` - il reload è automatico

---

**Tl;dr**: Usa `dev-build.bat` quando modifichi il backend. 🚀
