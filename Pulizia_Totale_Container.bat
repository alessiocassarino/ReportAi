@echo off
REM ============================================================================
REM Clean Slate Script (Windows)
REM Rimuove TUTTO (container, immagini, volumi) per un reset completo
REM ⚠️ ATTENZIONE: Questo cancella TUTTI i dati!
REM ============================================================================

echo.
echo ⚠️  ATTENZIONE: Questo comando rimuoverà:
echo    - Tutti i container (report-ai-*)
echo    - Tutti i volumi (database, ollama, ecc.)
echo    - Tutti i dati salvati
echo.

setlocal enabledelayedexpansion
set /p confirm="Sei sicuro? [s/n] "

if /i not "%confirm%"=="s" (
    echo ❌ Operazione annullata
    exit /b 1
)

echo.
echo 🔧 Clean Slate - Reset Completo
echo ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
echo.

REM Rimuovi tutto
echo 📍 Rimozione container e volumi...
docker-compose down -v --rmi all

echo.
echo 🗑️  Pulizia ulteriore (immagini dangling)...
docker image prune -f

echo.
echo ✅ Clean slate completato!
echo.
echo Adesso puoi fare:
echo    docker-compose up -d --build
echo.
pause
