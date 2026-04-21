package com.claude.reportAi.service.pricecomparison;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Genera il documento Word di valutazione fornitori / confronto offerte.
 *
 * Struttura del report:
 *  1. Cover page
 *  2. Executive Summary
 *  3. Tabella Comparativa per Criteri
 *  4. Analisi Dettagliata per Fornitore
 *  5. Matrice di Valutazione con Punteggi
 *  6. Analisi dei Rischi
 *  7. Raccomandazione Finale
 */
@Component
@Slf4j
public class PriceComparisonReportBuilder {

    // ── Palette Light Theme (allineata al tema "Light" dell'app) ──────
    private static final String C_NAVY      = "111827";  // near-black
    private static final String C_ORANGE    = "6366F1";  // indigo accent
    private static final String C_WHITE     = "FFFFFF";
    private static final String C_LIGHT_BG  = "F9FAFB";  // surface chiara
    private static final String C_MED_BG    = "EEF2FF";  // indigo tenue
    private static final String C_DARK_TEXT = "111827";
    private static final String C_GRAY_TEXT = "6B7280";
    private static final String C_SEPARATOR = "E5E7EB";
    private static final String C_RED       = "DC2626";
    private static final String C_WARN      = "D97706";
    private static final String C_GREEN     = "059669";
    private static final String C_GOLD      = "D97706";  // amber
    private static final String C_ALTO_BG   = "FEF2F2";
    private static final String C_MEDIO_BG  = "FFFBEB";
    private static final String C_BASSO_BG  = "F0FDF4";
    private static final String C_RANK1_BG  = "D1FAE5";  // green tint
    private static final String C_RANK2_BG  = "FEF9C3";  // yellow tint
    private static final String C_RANK3_BG  = "FEE2E2";  // red tint

    private static final int CONTENT_WIDTH = 9360;

    private final ObjectMapper objectMapper;

    @Value("${app.report.logo-path:}")
    private String logoPath;

    @Value("${app.report.company-name:Azienda S.p.A.}")
    private String companyName;

    @Autowired
    public PriceComparisonReportBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────
    // Metodo principale
    // ─────────────────────────────────────────────────────────────────

    public byte[] build(String reportJson, List<String> filenames) throws Exception {
        JsonNode root = objectMapper.readTree(cleanJson(reportJson));

        try (XWPFDocument doc = new XWPFDocument()) {
            addCoverPage(doc, root, filenames);
            addPageBreak(doc);
            addExecutiveSummary(doc, root);
            addPageBreak(doc);
            addComparativeTable(doc, root);
            addPageBreak(doc);
            addSupplierAnalysis(doc, root);
            addPageBreak(doc);
            addScoringMatrix(doc, root);
            addPageBreak(doc);
            addRisksTable(doc, root);
            addPageBreak(doc);
            addRecommendation(doc, root);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            log.info("DOCX confronto fornitori generato: {} bytes", out.size());
            return out.toByteArray();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. Cover Page
    // ─────────────────────────────────────────────────────────────────

    private void addCoverPage(XWPFDocument doc, JsonNode root, List<String> filenames) {
        addSpacer(doc, 1);

        // Logo
        if (logoPath != null && !logoPath.isBlank()) {
            File logoFile = new File(logoPath);
            if (logoFile.exists()) {
                try (FileInputStream fis = new FileInputStream(logoFile)) {
                    XWPFParagraph logoPara = doc.createParagraph();
                    logoPara.setAlignment(ParagraphAlignment.CENTER);
                    XWPFRun logoRun = logoPara.createRun();
                    String ext = logoPath.toLowerCase().endsWith(".png") ? "PNG" : "JPEG";
                    int picType = ext.equals("PNG") ? XWPFDocument.PICTURE_TYPE_PNG : XWPFDocument.PICTURE_TYPE_JPEG;
                    logoRun.addPicture(fis, picType, "logo", Units.toEMU(142), Units.toEMU(155));
                } catch (Exception e) {
                    log.warn("Impossibile inserire logo: {}", e.getMessage());
                }
            }
        }

        addSpacer(doc, 1);

        // Titolo principale
        XWPFParagraph titlePara = doc.createParagraph();
        titlePara.setAlignment(ParagraphAlignment.CENTER);
        setSpacingBefore(titlePara, 400);
        XWPFRun titleRun = titlePara.createRun();
        titleRun.setText("VALUTAZIONE FORNITORI");
        titleRun.setBold(true);
        titleRun.setFontSize(28);
        titleRun.setColor(C_NAVY);
        titleRun.setFontFamily("Calibri");

        XWPFParagraph subtitlePara = doc.createParagraph();
        subtitlePara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun subtitleRun = subtitlePara.createRun();
        subtitleRun.setText("Confronto e Analisi Comparativa Offerte");
        subtitleRun.setBold(false);
        subtitleRun.setFontSize(16);
        subtitleRun.setColor(C_ORANGE);
        subtitleRun.setFontFamily("Calibri");

        addSpacer(doc, 2);

        // Linea decorativa
        XWPFTable lineTable = doc.createTable(1, 1);
        setTableWidth(lineTable, CONTENT_WIDTH);
        setCellBackground(lineTable.getRow(0).getCell(0), C_ORANGE);
        setCellText(lineTable.getRow(0).getCell(0), "", C_WHITE, 4, false);

        addSpacer(doc, 2);

        // Tabella informazioni progetto
        String titolo   = getTextSafe(root, "titolo_progetto", "Confronto Offerte Moduli Oil & Gas");
        String dataVal  = getTextSafe(root, "data_valutazione",
                LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        int numForn     = getIntNode(root, "numero_fornitori", filenames.size());

        String[][] infoRows = {
                {"Titolo",             titolo},
                {"Data Valutazione",   dataVal},
                {"N° Fornitori",       String.valueOf(numForn)},
                {"Preparato da",       companyName},
                {"Classificazione",    "CONFIDENZIALE"}
        };

        XWPFTable infoTable = doc.createTable(infoRows.length, 2);
        setTableWidth(infoTable, CONTENT_WIDTH * 2 / 3);
        int half = CONTENT_WIDTH / 3;

        for (int i = 0; i < infoRows.length; i++) {
            XWPFTableRow row = infoTable.getRow(i);
            setCellWidth(row.getCell(0), half);
            setCellBackground(row.getCell(0), C_MED_BG);
            setCellText(row.getCell(0), infoRows[i][0], C_DARK_TEXT, 10, true);
            setCellWidth(row.getCell(1), half);
            setCellBackground(row.getCell(1), i % 2 == 0 ? C_LIGHT_BG : C_WHITE);
            setCellText(row.getCell(1), infoRows[i][1], C_DARK_TEXT, 10, false);
        }
        addTableBorders(infoTable);

        addSpacer(doc, 2);

        // Lista fornitori valutati
        XWPFParagraph listTitle = doc.createParagraph();
        XWPFRun listTitleRun = listTitle.createRun();
        listTitleRun.setText("Fornitori Valutati:");
        listTitleRun.setBold(true);
        listTitleRun.setFontSize(12);
        listTitleRun.setColor(C_NAVY);
        listTitleRun.setFontFamily("Calibri");

        for (int i = 0; i < filenames.size(); i++) {
            XWPFParagraph fp = doc.createParagraph();
            fp.setIndentationLeft(360);
            XWPFRun fr = fp.createRun();
            fr.setText((i + 1) + ". " + filenames.get(i));
            fr.setFontSize(11);
            fr.setColor(C_GRAY_TEXT);
            fr.setFontFamily("Calibri");
        }

        addSpacer(doc, 3);

        // Footer cover
        XWPFTable footerTable = doc.createTable(1, 1);
        setTableWidth(footerTable, CONTENT_WIDTH);
        XWPFTableCell footerCell = footerTable.getRow(0).getCell(0);
        setCellBackground(footerCell, C_ORANGE);
        setCellText(footerCell, companyName + " — Documento riservato — Non distribuire", C_WHITE, 9, false);
        footerCell.getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. Executive Summary
    // ─────────────────────────────────────────────────────────────────

    private void addExecutiveSummary(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "1. EXECUTIVE SUMMARY");

        String summary = getTextSafe(root, "executive_summary", "");
        if (!summary.isBlank()) {
            // Box evidenziato
            XWPFTable summaryBox = doc.createTable(1, 1);
            setTableWidth(summaryBox, CONTENT_WIDTH);
            XWPFTableCell cell = summaryBox.getRow(0).getCell(0);
            setCellBackground(cell, C_MED_BG);

            while (cell.getParagraphs().size() > 1) {
                cell.removeParagraph(cell.getParagraphs().size() - 1);
            }
            XWPFParagraph firstPara = cell.getParagraphs().getFirst();
            for (int i = firstPara.getRuns().size() - 1; i >= 0; i--) {
                firstPara.removeRun(i);
            }
            XWPFRun run = firstPara.createRun();
            run.setText(summary);
            run.setFontSize(11);
            run.setFontFamily("Calibri");
            run.setColor(C_DARK_TEXT);
        }

        // Raccomandazione in primo piano
        JsonNode raccomandazione = root.path("raccomandazione");
        if (!raccomandazione.isMissingNode() && !raccomandazione.isNull()) {
            addSpacer(doc, 1);
            addHeading2(doc, "Fornitore Raccomandato");

            String fornitoreConsigliato = getTextSafe(raccomandazione, "fornitore_consigliato", "N/D");
            XWPFTable recBox = doc.createTable(1, 1);
            setTableWidth(recBox, CONTENT_WIDTH);
            XWPFTableCell recCell = recBox.getRow(0).getCell(0);
            setCellBackground(recCell, C_RANK1_BG);

            while (recCell.getParagraphs().size() > 1) {
                recCell.removeParagraph(recCell.getParagraphs().size() - 1);
            }
            XWPFParagraph recPara = recCell.getParagraphs().getFirst();
            for (int i = recPara.getRuns().size() - 1; i >= 0; i--) {
                recPara.removeRun(i);
            }
            XWPFRun recRun = recPara.createRun();
            recRun.setText("★ " + fornitoreConsigliato);
            recRun.setBold(true);
            recRun.setFontSize(14);
            recRun.setFontFamily("Calibri");
            recRun.setColor(C_GREEN);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. Tabella Comparativa per Criteri
    // ─────────────────────────────────────────────────────────────────

    private void addComparativeTable(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "2. TABELLA COMPARATIVA");

        JsonNode tabella = root.path("tabella_comparativa");
        if (!tabella.isArray() || tabella.isEmpty()) {
            addBodyText(doc, "Nessun dato comparativo disponibile.");
            return;
        }

        // Determina i nomi dei fornitori dall'array dei valori del primo criterio
        JsonNode firstCriterio = tabella.get(0);
        JsonNode firstValues   = firstCriterio.path("valori_fornitori");
        int numFornitori = firstValues.isArray() ? firstValues.size() : 0;

        if (numFornitori == 0) {
            addBodyText(doc, "Nessun fornitore nel confronto.");
            return;
        }

        // Calcola larghezze colonne dinamicamente
        int critCol    = 2200;
        int pesoCol    = 900;
        int fornWidth  = (CONTENT_WIDTH - critCol - pesoCol) / numFornitori;
        int totalCols  = 2 + numFornitori;

        XWPFTable table = doc.createTable(tabella.size() + 1, totalCols);
        setTableWidth(table, CONTENT_WIDTH);

        // Header row
        XWPFTableRow headerRow = table.getRow(0);
        setCellWidth(headerRow.getCell(0), critCol);
        setCellBackground(headerRow.getCell(0), C_NAVY);
        setCellText(headerRow.getCell(0), "Criterio", C_WHITE, 10, true);

        setCellWidth(headerRow.getCell(1), pesoCol);
        setCellBackground(headerRow.getCell(1), C_NAVY);
        setCellText(headerRow.getCell(1), "Peso %", C_WHITE, 10, true);
        headerRow.getCell(1).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);

        // Nomi fornitori nell'header
        for (int f = 0; f < numFornitori; f++) {
            JsonNode fNode = firstValues.get(f);
            String fname   = fNode != null ? getTextSafe(fNode, "fornitore", "Fornitore " + (f + 1)) : "Fornitore " + (f + 1);
            setCellWidth(headerRow.getCell(2 + f), fornWidth);
            setCellBackground(headerRow.getCell(2 + f), C_ORANGE);
            setCellText(headerRow.getCell(2 + f), fname, C_WHITE, 9, true);
            headerRow.getCell(2 + f).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);
        }

        // Righe criteri
        int rowIdx = 1;
        boolean alt = false;
        for (JsonNode criterio : tabella) {
            String critName = getTextSafe(criterio, "criterio", "");
            int peso        = getIntNode(criterio, "peso_percentuale", 0);
            String rowBg    = alt ? C_LIGHT_BG : C_WHITE;
            JsonNode values = criterio.path("valori_fornitori");

            XWPFTableRow row = table.getRow(rowIdx);
            setCellWidth(row.getCell(0), critCol);
            setCellBackground(row.getCell(0), rowBg);
            setCellText(row.getCell(0), critName, C_DARK_TEXT, 10, false);

            setCellWidth(row.getCell(1), pesoCol);
            setCellBackground(row.getCell(1), C_MED_BG);
            setCellText(row.getCell(1), peso + "%", C_NAVY, 9, true);
            row.getCell(1).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);

            if (values.isArray()) {
                int maxScore = -1;
                // Trovare il punteggio massimo per evidenziare il migliore
                for (JsonNode v : values) {
                    int score = getIntNode(v, "punteggio", 0);
                    if (score > maxScore) maxScore = score;
                }
                for (int f = 0; f < numFornitori && f < values.size(); f++) {
                    JsonNode v      = values.get(f);
                    String valore   = getTextSafe(v, "valore", "N/D");
                    int punteggio   = getIntNode(v, "punteggio", 0);
                    String note     = getTextSafe(v, "note", "");
                    boolean isBest  = (punteggio == maxScore && maxScore > 0);

                    String cellBg   = isBest ? C_RANK1_BG : rowBg;
                    String cellFg   = isBest ? C_GREEN : C_DARK_TEXT;
                    String text     = valore + " (" + punteggio + "/100)";

                    XWPFTableCell tCell = row.getCell(2 + f);
                    setCellWidth(tCell, fornWidth);
                    setCellBackground(tCell, cellBg);
                    setCellText(tCell, text, cellFg, 9, isBest);
                    if (!note.isBlank()) {
                        XWPFParagraph notePara = tCell.addParagraph();
                        XWPFRun noteRun = notePara.createRun();
                        noteRun.setText(note);
                        noteRun.setFontSize(7);
                        noteRun.setFontFamily("Calibri");
                        noteRun.setColor(C_GRAY_TEXT);
                        noteRun.setItalic(true);
                    }
                }
            }

            rowIdx++;
            alt = !alt;
        }
        addTableBorders(table);

        addSpacer(doc, 1);
        addBodyText(doc, "★ Punteggio più alto per criterio evidenziato in verde.");
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. Analisi Dettagliata per Fornitore
    // ─────────────────────────────────────────────────────────────────

    private void addSupplierAnalysis(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "3. ANALISI DETTAGLIATA PER FORNITORE");

        JsonNode suppliers = root.path("analisi_fornitori");
        if (!suppliers.isArray() || suppliers.isEmpty()) {
            addBodyText(doc, "Nessuna analisi disponibile.");
            return;
        }

        for (JsonNode supplier : suppliers) {
            String nome     = getTextSafe(supplier, "nome", "Fornitore N/D");
            int rank        = getIntNode(supplier, "rank", 0);
            int scoreTotal  = getIntNode(supplier, "score_totale", 0);
            String conformita = getTextSafe(supplier, "conformita_requisiti", "N/D").toUpperCase();
            String prezzo   = getTextSafe(supplier, "prezzo_totale_offerto", "N/D");
            String leadTime = getTextSafe(supplier, "lead_time", "N/D");

            // Header fornitore con rank
            String rankLabel = switch (rank) {
                case 1 -> "★ 1° CLASSIFICATO";
                case 2 -> "▲ 2° CLASSIFICATO";
                case 3 -> "● 3° CLASSIFICATO";
                default -> rank + "° CLASSIFICATO";
            };
            String rankBg = switch (rank) {
                case 1 -> C_RANK1_BG;
                case 2 -> C_RANK2_BG;
                default -> C_RANK3_BG;
            };

            addHeading2(doc, "3." + rank + " — " + nome + " [" + rankLabel + "]");

            // Box sintesi
            XWPFTable synthTable = doc.createTable(1, 4);
            setTableWidth(synthTable, CONTENT_WIDTH);
            int qw = CONTENT_WIDTH / 4;

            String[][] synthData = {
                    {"Rank",      rankLabel},
                    {"Score",     scoreTotal + "/100"},
                    {"Prezzo",    prezzo},
                    {"Lead Time", leadTime}
            };
            for (int i = 0; i < 4; i++) {
                setCellWidth(synthTable.getRow(0).getCell(i), qw);
                setCellBackground(synthTable.getRow(0).getCell(i), i == 0 ? C_ORANGE : rankBg);
                setCellText(synthTable.getRow(0).getCell(i), synthData[i][0] + ": " + synthData[i][1],
                        i == 0 ? C_WHITE : C_DARK_TEXT, 10, i == 0);
            }
            addTableBorders(synthTable);

            // Conformità
            addSpacer(doc, 1);
            String confColor = switch (conformita) {
                case "CONFORME"              -> C_GREEN;
                case "PARZIALMENTE_CONFORME" -> C_WARN;
                default                      -> C_RED;
            };
            String confLabel = switch (conformita) {
                case "CONFORME"              -> "CONFORME";
                case "PARZIALMENTE_CONFORME" -> "PARZIALMENTE CONFORME";
                default                      -> "NON CONFORME";
            };
            XWPFTable confTable = doc.createTable(1, 1);
            setTableWidth(confTable, CONTENT_WIDTH / 2);
            XWPFTableCell confCell = confTable.getRow(0).getCell(0);
            setCellBackground(confCell, switch (conformita) {
                case "CONFORME"              -> C_BASSO_BG;
                case "PARZIALMENTE_CONFORME" -> C_MEDIO_BG;
                default                      -> C_ALTO_BG;
            });
            setCellText(confCell, "Conformità ai Requisiti: " + confLabel, confColor, 10, true);
            addTableBorders(confTable);

            // Punti di forza / debolezza
            addSpacer(doc, 1);
            int[] colWidths = {CONTENT_WIDTH / 2 - 180, CONTENT_WIDTH / 2 - 180};
            XWPFTable swTable = doc.createTable(1, 2);
            setTableWidth(swTable, CONTENT_WIDTH);

            // Forza
            XWPFTableCell forteCell = swTable.getRow(0).getCell(0);
            setCellBackground(forteCell, C_BASSO_BG);
            buildBulletList(forteCell, "✔ Punti di Forza", supplier.path("punti_di_forza"), C_GREEN, C_DARK_TEXT);
            setCellWidth(forteCell, colWidths[0]);

            // Debolezza
            XWPFTableCell deboCell = swTable.getRow(0).getCell(1);
            setCellBackground(deboCell, C_ALTO_BG);
            buildBulletList(deboCell, "✘ Punti di Debolezza", supplier.path("punti_di_debolezza"), C_RED, C_DARK_TEXT);
            setCellWidth(deboCell, colWidths[1]);

            addTableBorders(swTable);

            // Rischi
            JsonNode rischi = supplier.path("rischi_principali");
            if (rischi.isArray() && !rischi.isEmpty()) {
                addSpacer(doc, 1);
                XWPFTable riskBox = doc.createTable(1, 1);
                setTableWidth(riskBox, CONTENT_WIDTH);
                XWPFTableCell riskCell = riskBox.getRow(0).getCell(0);
                setCellBackground(riskCell, C_MEDIO_BG);
                buildBulletList(riskCell, "⚠ Rischi Principali", rischi, C_WARN, C_DARK_TEXT);
                addTableBorders(riskBox);
            }

            // Opportunità di negoziazione
            JsonNode opport = supplier.path("opportunita_negoziazione");
            if (opport.isArray() && !opport.isEmpty()) {
                addSpacer(doc, 1);
                XWPFTable negBox = doc.createTable(1, 1);
                setTableWidth(negBox, CONTENT_WIDTH);
                XWPFTableCell negCell = negBox.getRow(0).getCell(0);
                setCellBackground(negCell, C_MED_BG);
                buildBulletList(negCell, "◎ Opportunità di Negoziazione", opport, C_NAVY, C_DARK_TEXT);
                addTableBorders(negBox);
            }

            // Note tecniche
            String noteTecniche = getTextSafe(supplier, "note_tecniche", "");
            if (!noteTecniche.isBlank()) {
                addSpacer(doc, 1);
                addBodyText(doc, "Note tecniche: " + noteTecniche);
            }

            addSpacer(doc, 1);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 5. Matrice di Valutazione con Punteggi
    // ─────────────────────────────────────────────────────────────────

    private void addScoringMatrix(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "4. MATRICE DI VALUTAZIONE");

        JsonNode matrix = root.path("matrice_valutazione");
        if (!matrix.isArray() || matrix.isEmpty()) {
            addBodyText(doc, "Matrice di valutazione non disponibile.");
            return;
        }

        // Conta criteri dal primo fornitore
        JsonNode firstSupplier = matrix.get(0);
        JsonNode firstCriteri  = firstSupplier.path("criteri");
        int numCriteri = firstCriteri.isArray() ? firstCriteri.size() : 0;
        int numFornitori = matrix.size();

        if (numCriteri == 0 || numFornitori == 0) {
            addBodyText(doc, "Dati insufficienti per la matrice.");
            return;
        }

        int fornWidth  = 1800;
        int critWidth  = (CONTENT_WIDTH - fornWidth) / (numCriteri + 1);
        int totalCols  = 1 + numCriteri + 1; // fornitore + criteri + totale

        XWPFTable table = doc.createTable(numFornitori + 1, totalCols);
        setTableWidth(table, CONTENT_WIDTH);

        // Header
        XWPFTableRow hr = table.getRow(0);
        setCellWidth(hr.getCell(0), fornWidth);
        setCellBackground(hr.getCell(0), C_NAVY);
        setCellText(hr.getCell(0), "Fornitore", C_WHITE, 10, true);

        for (int c = 0; c < numCriteri; c++) {
            JsonNode crit  = firstCriteri.get(c);
            String cName   = getTextSafe(crit, "nome", "Criterio " + (c + 1));
            int peso        = getIntNode(crit, "peso", 0);
            setCellWidth(hr.getCell(1 + c), critWidth);
            setCellBackground(hr.getCell(1 + c), C_NAVY);
            setCellText(hr.getCell(1 + c), cName + "\n(" + peso + "%)", C_WHITE, 9, true);
            hr.getCell(1 + c).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);
        }
        setCellWidth(hr.getCell(totalCols - 1), critWidth);
        setCellBackground(hr.getCell(totalCols - 1), C_ORANGE);
        setCellText(hr.getCell(totalCols - 1), "TOTALE PONDERATO", C_WHITE, 9, true);
        hr.getCell(totalCols - 1).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);

        // Righe fornitori
        // Prima trovare il max totale per evidenziare il vincitore
        double maxTotale = -1;
        for (JsonNode s : matrix) {
            double tot = getDoubleNode(s, "totale_ponderato", 0.0);
            if (tot > maxTotale) maxTotale = tot;
        }

        int rowIdx = 1;
        for (JsonNode supplier : matrix) {
            String nome    = getTextSafe(supplier, "fornitore", "N/D");
            double totale  = getDoubleNode(supplier, "totale_ponderato", 0.0);
            int rankS      = getIntNode(supplier, "rank", 0);
            boolean isWinner = (totale == maxTotale && maxTotale > 0);

            String rowBg   = isWinner ? C_RANK1_BG : (rowIdx % 2 == 0 ? C_LIGHT_BG : C_WHITE);
            String rowFg   = isWinner ? C_GREEN : C_DARK_TEXT;

            XWPFTableRow row = table.getRow(rowIdx);
            setCellWidth(row.getCell(0), fornWidth);
            setCellBackground(row.getCell(0), rowBg);
            setCellText(row.getCell(0), (isWinner ? "★ " : "") + nome, rowFg, 10, isWinner);

            JsonNode criteri = supplier.path("criteri");
            if (criteri.isArray()) {
                for (int c = 0; c < numCriteri && c < criteri.size(); c++) {
                    JsonNode crit  = criteri.get(c);
                    int score      = getIntNode(crit, "punteggio", 0);
                    double pond    = getDoubleNode(crit, "ponderato", 0.0);
                    String cellTxt = score + "/100\n(" + String.format(Locale.US, "%.1f", pond) + ")";
                    setCellWidth(row.getCell(1 + c), critWidth);
                    setCellBackground(row.getCell(1 + c), score >= 80 ? C_BASSO_BG : (score >= 60 ? C_MEDIO_BG : C_ALTO_BG));
                    setCellText(row.getCell(1 + c), cellTxt, C_DARK_TEXT, 9, false);
                    row.getCell(1 + c).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);
                }
            }

            String totaleStr = String.format(Locale.US, "%.1f", totale);
            setCellWidth(row.getCell(totalCols - 1), critWidth);
            setCellBackground(row.getCell(totalCols - 1), isWinner ? C_RANK1_BG : C_MED_BG);
            setCellText(row.getCell(totalCols - 1), totaleStr, isWinner ? C_GREEN : C_NAVY, 11, true);
            row.getCell(totalCols - 1).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);

            rowIdx++;
        }
        addTableBorders(table);

        addSpacer(doc, 1);
        addBodyText(doc, "Legenda: Verde = punteggio ≥ 80 | Giallo = 60-79 | Rosso = < 60. " +
                "Il totale ponderato è la somma dei punteggi moltiplicati per il peso percentuale di ciascun criterio.");
    }

    // ─────────────────────────────────────────────────────────────────
    // 6. Analisi dei Rischi
    // ─────────────────────────────────────────────────────────────────

    private void addRisksTable(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "5. ANALISI DEI RISCHI");

        JsonNode risks = root.path("analisi_rischi");
        if (!risks.isArray() || risks.isEmpty()) {
            addBodyText(doc, "Nessun rischio identificato nel confronto.");
            return;
        }

        int[] colWidths = {1600, 1800, 2400, 800, 800, 1960};
        String[] headers = {"Fornitore", "Rischio", "Mitigazione", "Impatto", "Prob.", "Note"};

        XWPFTable table = doc.createTable(risks.size() + 1, 6);
        setTableWidth(table, CONTENT_WIDTH);

        // Header
        XWPFTableRow hr = table.getRow(0);
        for (int i = 0; i < headers.length; i++) {
            setCellWidth(hr.getCell(i), colWidths[i]);
            setCellBackground(hr.getCell(i), C_NAVY);
            setCellText(hr.getCell(i), headers[i], C_WHITE, 10, true);
        }

        int rowIdx = 1;
        for (JsonNode risk : risks) {
            String impatto     = getTextSafe(risk, "impatto",     "MEDIO").toUpperCase();
            String probabilita = getTextSafe(risk, "probabilita", "MEDIA").toUpperCase();

            String impatBg = switch (impatto) {
                case "ALTO"  -> C_ALTO_BG;
                case "BASSO" -> C_BASSO_BG;
                default      -> C_MEDIO_BG;
            };
            String impatFg = switch (impatto) {
                case "ALTO"  -> C_RED;
                case "BASSO" -> C_GREEN;
                default      -> C_WARN;
            };
            String rowBg = (rowIdx % 2 == 0) ? C_LIGHT_BG : C_WHITE;

            XWPFTableRow row = table.getRow(rowIdx);
            setCellWidth(row.getCell(0), colWidths[0]); setCellBackground(row.getCell(0), rowBg);
            setCellText(row.getCell(0), getTextSafe(risk, "fornitore", ""), C_DARK_TEXT, 9, false);

            setCellWidth(row.getCell(1), colWidths[1]); setCellBackground(row.getCell(1), rowBg);
            setCellText(row.getCell(1), getTextSafe(risk, "rischio", ""), C_DARK_TEXT, 9, false);

            setCellWidth(row.getCell(2), colWidths[2]); setCellBackground(row.getCell(2), rowBg);
            setCellText(row.getCell(2), getTextSafe(risk, "mitigazione", ""), C_DARK_TEXT, 9, false);

            setCellWidth(row.getCell(3), colWidths[3]); setCellBackground(row.getCell(3), impatBg);
            setCellText(row.getCell(3), impatto, impatFg, 8, true);
            row.getCell(3).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);

            String probBg = switch (probabilita) {
                case "ALTA"  -> C_ALTO_BG;
                case "BASSA" -> C_BASSO_BG;
                default      -> C_MEDIO_BG;
            };
            String probFg = switch (probabilita) {
                case "ALTA"  -> C_RED;
                case "BASSA" -> C_GREEN;
                default      -> C_WARN;
            };
            setCellWidth(row.getCell(4), colWidths[4]); setCellBackground(row.getCell(4), probBg);
            setCellText(row.getCell(4), probabilita, probFg, 8, true);
            row.getCell(4).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);

            setCellWidth(row.getCell(5), colWidths[5]); setCellBackground(row.getCell(5), rowBg);
            setCellText(row.getCell(5), "", C_DARK_TEXT, 9, false);

            rowIdx++;
        }
        addTableBorders(table);
    }

    // ─────────────────────────────────────────────────────────────────
    // 7. Raccomandazione Finale
    // ─────────────────────────────────────────────────────────────────

    private void addRecommendation(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "6. RACCOMANDAZIONE FINALE");

        JsonNode rec = root.path("raccomandazione");
        if (rec.isMissingNode() || rec.isNull()) {
            addBodyText(doc, "Nessuna raccomandazione disponibile.");
            return;
        }

        String fornitoreConsigliato  = getTextSafe(rec, "fornitore_consigliato", "N/D");
        String motivazione           = getTextSafe(rec, "motivazione", "");
        String saving                = getTextSafe(rec, "saving_stimato_vs_media", "");
        String fornitoreAlternativo  = getTextSafe(rec, "fornitore_alternativo", "");
        String motivazioneAlt        = getTextSafe(rec, "motivazione_alternativo", "");

        // Box raccomandazione principale
        XWPFTable recTable = doc.createTable(1, 1);
        setTableWidth(recTable, CONTENT_WIDTH);
        XWPFTableCell recCell = recTable.getRow(0).getCell(0);
        setCellBackground(recCell, C_RANK1_BG);

        while (recCell.getParagraphs().size() > 1) {
            recCell.removeParagraph(recCell.getParagraphs().size() - 1);
        }
        XWPFParagraph recPara = recCell.getParagraphs().getFirst();
        for (int i = recPara.getRuns().size() - 1; i >= 0; i--) {
            recPara.removeRun(i);
        }
        XWPFRun recRun = recPara.createRun();
        recRun.setText("FORNITORE SELEZIONATO: " + fornitoreConsigliato.toUpperCase());
        recRun.setBold(true);
        recRun.setFontSize(14);
        recRun.setFontFamily("Calibri");
        recRun.setColor(C_GREEN);
        addTableBorders(recTable);

        // Motivazione
        addSpacer(doc, 1);
        addHeading2(doc, "Motivazione della Scelta");
        if (!motivazione.isBlank()) {
            addBodyText(doc, motivazione);
        }

        // Saving stimato
        if (!saving.isBlank()) {
            addSpacer(doc, 1);
            XWPFTable savingTable = doc.createTable(1, 2);
            setTableWidth(savingTable, CONTENT_WIDTH / 2);
            setCellWidth(savingTable.getRow(0).getCell(0), CONTENT_WIDTH / 4);
            setCellBackground(savingTable.getRow(0).getCell(0), C_ORANGE);
            setCellText(savingTable.getRow(0).getCell(0), "Saving vs Media Mercato", C_WHITE, 10, true);
            setCellWidth(savingTable.getRow(0).getCell(1), CONTENT_WIDTH / 4);
            setCellBackground(savingTable.getRow(0).getCell(1), C_BASSO_BG);
            setCellText(savingTable.getRow(0).getCell(1), saving, C_GREEN, 10, true);
            addTableBorders(savingTable);
        }

        // Condizioni per accettazione
        JsonNode condizioni = rec.path("condizioni_per_accettazione");
        if (condizioni.isArray() && !condizioni.isEmpty()) {
            addSpacer(doc, 1);
            addHeading2(doc, "Condizioni per l'Accettazione dell'Offerta");
            for (JsonNode c : condizioni) {
                XWPFParagraph p = doc.createParagraph();
                p.setIndentationLeft(360);
                XWPFRun r = p.createRun();
                r.setText("✔ " + c.asText());
                r.setFontSize(11);
                r.setFontFamily("Calibri");
                r.setColor(C_DARK_TEXT);
            }
        }

        // Punti da negoziare
        JsonNode negoziare = rec.path("punti_da_negoziare");
        if (negoziare.isArray() && !negoziare.isEmpty()) {
            addSpacer(doc, 1);
            addHeading2(doc, "Punti da Negoziare Prima dell'Ordine");
            XWPFTable negTable = doc.createTable(negoziare.size() + 1, 2);
            setTableWidth(negTable, CONTENT_WIDTH);
            setCellWidth(negTable.getRow(0).getCell(0), 400);
            setCellBackground(negTable.getRow(0).getCell(0), C_ORANGE);
            setCellText(negTable.getRow(0).getCell(0), "#", C_WHITE, 10, true);
            setCellWidth(negTable.getRow(0).getCell(1), CONTENT_WIDTH - 400);
            setCellBackground(negTable.getRow(0).getCell(1), C_ORANGE);
            setCellText(negTable.getRow(0).getCell(1), "Punto di Negoziazione", C_WHITE, 10, true);

            int ni = 1;
            boolean alt = false;
            for (JsonNode punto : negoziare) {
                XWPFTableRow row = negTable.getRow(ni);
                String bg = alt ? C_LIGHT_BG : C_WHITE;
                setCellWidth(row.getCell(0), 400); setCellBackground(row.getCell(0), bg);
                setCellText(row.getCell(0), String.valueOf(ni), C_NAVY, 9, true);
                setCellWidth(row.getCell(1), CONTENT_WIDTH - 400); setCellBackground(row.getCell(1), bg);
                setCellText(row.getCell(1), punto.asText(), C_DARK_TEXT, 9, false);
                ni++;
                alt = !alt;
            }
            addTableBorders(negTable);
        }

        // Fornitore alternativo
        if (!fornitoreAlternativo.isBlank()) {
            addSpacer(doc, 1);
            addHeading2(doc, "Fornitore Alternativo");
            XWPFTable altTable = doc.createTable(1, 1);
            setTableWidth(altTable, CONTENT_WIDTH);
            XWPFTableCell altCell = altTable.getRow(0).getCell(0);
            setCellBackground(altCell, C_RANK2_BG);
            setCellText(altCell, "▲ " + fornitoreAlternativo + (motivazioneAlt.isBlank() ? "" : ": " + motivazioneAlt),
                    C_WARN, 10, true);
            addTableBorders(altTable);
        }

        // Note finali
        String noteFinali = getTextSafe(root, "note_finali", "");
        if (!noteFinali.isBlank()) {
            addSpacer(doc, 1);
            addHeading2(doc, "Note Finali");
            addBodyText(doc, noteFinali);
        }

        // Footer documento
        addSpacer(doc, 2);
        XWPFTable footerTable = doc.createTable(1, 1);
        setTableWidth(footerTable, CONTENT_WIDTH);
        XWPFTableCell footerCell = footerTable.getRow(0).getCell(0);
        setCellBackground(footerCell, C_ORANGE);
        setCellText(footerCell,
                companyName + " — Documento di Valutazione Fornitori — Generato il " +
                LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                C_WHITE, 9, false);
        footerCell.getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);
    }

    // ─────────────────────────────────────────────────────────────────
    // Helper: bullet list in cella
    // ─────────────────────────────────────────────────────────────────

    private void buildBulletList(XWPFTableCell cell, String title,
                                  JsonNode items, String titleColor, String itemColor) {
        while (cell.getParagraphs().size() > 1) {
            cell.removeParagraph(cell.getParagraphs().size() - 1);
        }
        XWPFParagraph titlePara = cell.getParagraphs().getFirst();
        for (int i = titlePara.getRuns().size() - 1; i >= 0; i--) {
            titlePara.removeRun(i);
        }

        CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTTcMar mar = tcPr.isSetTcMar() ? tcPr.getTcMar() : tcPr.addNewTcMar();
        setMarginValue(mar.isSetTop() ? mar.getTop() : mar.addNewTop(), 60);
        setMarginValue(mar.isSetBottom() ? mar.getBottom() : mar.addNewBottom(), 60);
        setMarginValue(mar.isSetLeft() ? mar.getLeft() : mar.addNewLeft(), 120);
        setMarginValue(mar.isSetRight() ? mar.getRight() : mar.addNewRight(), 120);

        XWPFRun titleRun = titlePara.createRun();
        titleRun.setText(title);
        titleRun.setBold(true);
        titleRun.setFontSize(10);
        titleRun.setFontFamily("Calibri");
        titleRun.setColor(titleColor);

        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                XWPFParagraph p = cell.addParagraph();
                XWPFRun r = p.createRun();
                r.setText("• " + item.asText());
                r.setFontSize(9);
                r.setFontFamily("Calibri");
                r.setColor(itemColor);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Helper methods POI
    // ─────────────────────────────────────────────────────────────────

    private void addHeading1(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        setSpacingBefore(para, 400);
        setSpacingAfter(para, 160);

        CTPPr pPr = para.getCTP().isSetPPr() ? para.getCTP().getPPr() : para.getCTP().addNewPPr();
        CTPBdr pBdr = pPr.isSetPBdr() ? pPr.getPBdr() : pPr.addNewPBdr();
        CTBorder bottom = pBdr.isSetBottom() ? pBdr.getBottom() : pBdr.addNewBottom();
        bottom.setVal(STBorder.SINGLE);
        bottom.setSz(BigInteger.valueOf(6));
        bottom.setColor(C_ORANGE);
        bottom.setSpace(BigInteger.valueOf(4));

        XWPFRun run = para.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(16);
        run.setColor(C_NAVY);
        run.setFontFamily("Calibri");
    }

    private void addHeading2(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        setSpacingBefore(para, 240);
        setSpacingAfter(para, 100);
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(12);
        run.setColor(C_NAVY);
        run.setFontFamily("Calibri");
    }

    private void addBodyText(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.BOTH);
        setSpacingAfter(para, 100);
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setFontSize(11);
        run.setColor(C_DARK_TEXT);
        run.setFontFamily("Calibri");
    }

    private void addSpacer(XWPFDocument doc, int lines) {
        for (int i = 0; i < lines; i++) {
            doc.createParagraph();
        }
    }

    private void addPageBreak(XWPFDocument doc) {
        XWPFParagraph para = doc.createParagraph();
        para.createRun().addBreak(BreakType.PAGE);
    }

    private void setTableWidth(XWPFTable table, int widthTwips) {
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) tblPr = table.getCTTbl().addNewTblPr();
        CTTblWidth w = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(widthTwips));
    }

    private void setCellWidth(XWPFTableCell cell, int widthTwips) {
        CTTcPr tcPr = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr()
                : cell.getCTTc().addNewTcPr();
        CTTblWidth w = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(widthTwips));
    }

    private void setCellBackground(XWPFTableCell cell, String hexColor) {
        CTTcPr tcPr = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr()
                : cell.getCTTc().addNewTcPr();
        CTShd shd = tcPr.isSetShd() ? tcPr.getShd() : tcPr.addNewShd();
        shd.setFill(hexColor);
        shd.setColor("auto");
        shd.setVal(STShd.CLEAR);
    }

    private void setCellText(XWPFTableCell cell, String text, String color, int fontSize, boolean bold) {
        while (cell.getParagraphs().size() > 1) {
            cell.removeParagraph(cell.getParagraphs().size() - 1);
        }
        XWPFParagraph para = cell.getParagraphs().getFirst();
        for (int i = para.getRuns().size() - 1; i >= 0; i--) {
            para.removeRun(i);
        }

        CTTcPr tcPr = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr()
                : cell.getCTTc().addNewTcPr();
        CTTcMar mar = tcPr.isSetTcMar() ? tcPr.getTcMar() : tcPr.addNewTcMar();
        setMarginValue(mar.isSetTop()    ? mar.getTop()    : mar.addNewTop(),    60);
        setMarginValue(mar.isSetBottom() ? mar.getBottom() : mar.addNewBottom(), 60);
        setMarginValue(mar.isSetLeft()   ? mar.getLeft()   : mar.addNewLeft(),   120);
        setMarginValue(mar.isSetRight()  ? mar.getRight()  : mar.addNewRight(),  120);

        XWPFRun run = para.createRun();
        run.setText(text != null ? text : "");
        run.setColor(color);
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setFontFamily("Calibri");
    }

    private void setMarginValue(CTTblWidth w, int value) {
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(value));
    }

    private void addTableBorders(XWPFTable table) {
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) tblPr = table.getCTTbl().addNewTblPr();
        CTTblBorders borders = tblPr.isSetTblBorders()
                ? tblPr.getTblBorders()
                : tblPr.addNewTblBorders();

        CTBorder[] allBorders = {
                borders.isSetTop()     ? borders.getTop()     : borders.addNewTop(),
                borders.isSetBottom()  ? borders.getBottom()  : borders.addNewBottom(),
                borders.isSetLeft()    ? borders.getLeft()    : borders.addNewLeft(),
                borders.isSetRight()   ? borders.getRight()   : borders.addNewRight(),
                borders.isSetInsideH() ? borders.getInsideH() : borders.addNewInsideH(),
                borders.isSetInsideV() ? borders.getInsideV() : borders.addNewInsideV()
        };
        for (CTBorder b : allBorders) {
            b.setVal(STBorder.SINGLE);
            b.setSz(BigInteger.valueOf(2));
            b.setColor(C_SEPARATOR);
        }
    }

    private void setSpacingBefore(XWPFParagraph para, int twips) {
        CTPPr pPr = para.getCTP().isSetPPr() ? para.getCTP().getPPr() : para.getCTP().addNewPPr();
        CTSpacing sp = pPr.isSetSpacing() ? pPr.getSpacing() : pPr.addNewSpacing();
        sp.setBefore(BigInteger.valueOf(twips));
    }

    private void setSpacingAfter(XWPFParagraph para, int twips) {
        CTPPr pPr = para.getCTP().isSetPPr() ? para.getCTP().getPPr() : para.getCTP().addNewPPr();
        CTSpacing sp = pPr.isSetSpacing() ? pPr.getSpacing() : pPr.addNewSpacing();
        sp.setAfter(BigInteger.valueOf(twips));
    }

    // ─────────────────────────────────────────────────────────────────
    // Data accessors
    // ─────────────────────────────────────────────────────────────────

    private String getTextSafe(JsonNode node, String field, String def) {
        JsonNode n = node.path(field);
        return (n.isMissingNode() || n.isNull()) ? def : n.asText(def);
    }

    private double getDoubleNode(JsonNode node, String field, double def) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) return def;
        try { return n.asDouble(def); } catch (Exception e) { return def; }
    }

    private int getIntNode(JsonNode node, String field, int def) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) return def;
        try { return n.asInt(def); } catch (Exception e) { return def; }
    }

    // ─────────────────────────────────────────────────────────────────
    // JSON helpers
    // ─────────────────────────────────────────────────────────────────

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.strip();
        if (s.startsWith("```")) {
            s = s.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
        }
        int start = s.indexOf('{');
        if (start < 0) return "{}";
        s = s.substring(start);

        // Tronca qualsiasi testo dopo l'ultima } (es. postamble aggiunto da Gemini)
        int lastClose = s.lastIndexOf('}');
        if (lastClose >= 0) {
            s = s.substring(0, lastClose + 1);
        }

        int end = s.lastIndexOf('}');
        if (end > 0 && end == s.length() - 1) {
            return s;
        }
        return repairTruncatedJson(s);
    }

    private String repairTruncatedJson(String json) {
        long opens  = json.chars().filter(c -> c == '{').count();
        long closes = json.chars().filter(c -> c == '}').count();
        long arrO   = json.chars().filter(c -> c == '[').count();
        long arrC   = json.chars().filter(c -> c == ']').count();

        // Chiudi stringhe aperte
        long quotes = json.chars().filter(c -> c == '"').count();
        StringBuilder sb = new StringBuilder(json.stripTrailing());
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.deleteCharAt(sb.length() - 1);
        }
        if (quotes % 2 != 0) sb.append("\"");

        for (long i = 0; i < arrO - arrC; i++) sb.append("]");
        for (long i = 0; i < opens - closes; i++) sb.append("}");
        return sb.toString();
    }
}
