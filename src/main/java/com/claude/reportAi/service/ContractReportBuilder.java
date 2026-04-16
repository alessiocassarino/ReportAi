package com.claude.reportAi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Genera un file DOCX professionale a partire dal JSON di analisi
 * prodotto da Claude, usando Apache POI localmente.
 */
@Component
@Slf4j
public class ContractReportBuilder {

    // Palette colori
    private static final String COLOR_NAVY      = "1F3864";
    private static final String COLOR_WHITE     = "FFFFFF";
    private static final String COLOR_ALTO      = "C00000";
    private static final String COLOR_MEDIO     = "C55A11";
    private static final String COLOR_BASSO     = "375623";
    private static final String COLOR_ALTO_BG   = "FFE7E7";
    private static final String COLOR_MEDIO_BG  = "FFF2CC";
    private static final String COLOR_BASSO_BG  = "E2EFDA";
    private static final String COLOR_ROW_ALT   = "EBF3FB";
    private static final String COLOR_GRAY_TEXT = "595959";

    // Dimensioni pagina (twips: 1440 = 1 inch)
    private static final int CONTENT_WIDTH = 9360; // A4 con margini 1 inch

    private final ObjectMapper objectMapper = new ObjectMapper();

    public byte[] build(String reportJson, String originalFilename) throws Exception {
        JsonNode root = objectMapper.readTree(cleanJson(reportJson));

        try (XWPFDocument doc = new XWPFDocument()) {
            addCoverPage(doc, originalFilename, root);
            addPageBreak(doc);
            addExecutiveSummary(doc, root);
            addPageBreak(doc);
            addRiskMatrix(doc, root);
            addPageBreak(doc);
            addDetailedAnalysis(doc, root);
            addPageBreak(doc);
            addTop5Clauses(doc, root);
            addMissingClauses(doc, root);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            log.info("DOCX generato localmente: {} bytes", out.size());
            return out.toByteArray();
        }
    }

    // -------------------------------------------------------------------------
    // Copertina
    // -------------------------------------------------------------------------

    private void addCoverPage(XWPFDocument doc, String filename, JsonNode root) {
        addSpacer(doc, 6);

        XWPFParagraph titlePara = doc.createParagraph();
        titlePara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = titlePara.createRun();
        titleRun.setText("REPORT DI ANALISI DEI RISCHI CONTRATTUALI");
        titleRun.setBold(true);
        titleRun.setFontSize(22);
        titleRun.setColor(COLOR_NAVY);
        titleRun.setFontFamily("Calibri");

        addSpacer(doc, 1);

        XWPFParagraph filePara = doc.createParagraph();
        filePara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun fileRun = filePara.createRun();
        fileRun.setText(filename);
        fileRun.setFontSize(13);
        fileRun.setColor(COLOR_GRAY_TEXT);
        fileRun.setItalic(true);
        fileRun.setFontFamily("Calibri");

        addSpacer(doc, 2);

        String valutazione = getTextSafe(root, "valutazione_complessiva", "N/D");
        XWPFParagraph valPara = doc.createParagraph();
        valPara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun valLabel = valPara.createRun();
        valLabel.setText("Valutazione complessiva:  ");
        valLabel.setFontSize(14);
        valLabel.setFontFamily("Calibri");
        XWPFRun valRun = valPara.createRun();
        valRun.setText(valutazione);
        valRun.setBold(true);
        valRun.setFontSize(16);
        valRun.setColor(getValutazioneColor(valutazione));
        valRun.setFontFamily("Calibri");

        addSpacer(doc, 1);

        String raccomandazione = getTextSafe(root, "raccomandazione_finale", "N/D");
        XWPFParagraph raccPara = doc.createParagraph();
        raccPara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun raccLabel = raccPara.createRun();
        raccLabel.setText("Raccomandazione:  ");
        raccLabel.setFontSize(14);
        raccLabel.setFontFamily("Calibri");
        XWPFRun raccRun = raccPara.createRun();
        raccRun.setText(raccomandazione);
        raccRun.setBold(true);
        raccRun.setFontSize(14);
        raccRun.setColor(COLOR_NAVY);
        raccRun.setFontFamily("Calibri");

        addSpacer(doc, 5);

        XWPFParagraph datePara = doc.createParagraph();
        datePara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun dateRun = datePara.createRun();
        dateRun.setText(LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)));
        dateRun.setFontSize(11);
        dateRun.setColor(COLOR_GRAY_TEXT);
        dateRun.setFontFamily("Calibri");
    }

    // -------------------------------------------------------------------------
    // Executive Summary
    // -------------------------------------------------------------------------

    private void addExecutiveSummary(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "1. EXECUTIVE SUMMARY");

        String summary = getTextSafe(root, "executive_summary", "");
        if (!summary.isBlank()) {
            addBodyText(doc, summary);
        }

        JsonNode criticalRisks = root.path("rischi_critici");
        if (criticalRisks.isArray() && !criticalRisks.isEmpty()) {
            addSpacer(doc, 1);
            addHeading2(doc, "Rischi più critici");

            for (JsonNode risk : criticalRisks) {
                String level    = getTextSafe(risk, "livello", "MEDIO");
                String clausola = getTextSafe(risk, "clausola", "");
                String sezione  = getTextSafe(risk, "sezione", "");
                String desc     = getTextSafe(risk, "descrizione", "");

                XWPFParagraph para = doc.createParagraph();
                para.setIndentationLeft(720);
                setSpacingBefore(para, 80);

                XWPFRun lvlRun = para.createRun();
                lvlRun.setText("[" + level + "]  ");
                lvlRun.setBold(true);
                lvlRun.setColor(getRiskColor(level));
                lvlRun.setFontSize(11);
                lvlRun.setFontFamily("Calibri");

                XWPFRun refRun = para.createRun();
                String ref = sezione + (clausola.isBlank() ? "" : " – " + clausola) + ": ";
                refRun.setText(ref);
                refRun.setBold(true);
                refRun.setFontSize(11);
                refRun.setFontFamily("Calibri");

                XWPFRun descRun = para.createRun();
                descRun.setText(desc);
                descRun.setFontSize(11);
                descRun.setFontFamily("Calibri");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Matrice dei Rischi
    // -------------------------------------------------------------------------

    private void addRiskMatrix(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "2. MATRICE DEI RISCHI");

        JsonNode risks = root.path("matrice_rischi");
        if (!risks.isArray() || risks.isEmpty()) {
            addBodyText(doc, "Nessun rischio identificato.");
            return;
        }

        int numRows = risks.size() + 1;
        XWPFTable table = doc.createTable(numRows, 5);
        setTableWidth(table, CONTENT_WIDTH);

        int[] colWidths = {1600, 900, 3100, 900, 2860};
        String[] headers = {"Sezione", "Clausola", "Rischio", "Livello", "Azione Richiesta"};

        XWPFTableRow headerRow = table.getRow(0);
        for (int i = 0; i < headers.length; i++) {
            XWPFTableCell cell = headerRow.getCell(i);
            setCellWidth(cell, colWidths[i]);
            setCellBackground(cell, COLOR_NAVY);
            setCellText(cell, headers[i], COLOR_WHITE, 10, true);
        }

        int rowIdx = 1;
        for (JsonNode risk : risks) {
            String level  = getTextSafe(risk, "livello", "MEDIO");
            String rowBg  = (rowIdx % 2 == 0) ? COLOR_ROW_ALT : "FFFFFF";
            XWPFTableRow row = table.getRow(rowIdx);

            setRowCell(row, 0, colWidths[0], rowBg,                 getTextSafe(risk, "sezione",   ""), "000000",         9, false);
            setRowCell(row, 1, colWidths[1], rowBg,                 getTextSafe(risk, "clausola",  ""), "000000",         9, false);
            setRowCell(row, 2, colWidths[2], rowBg,                 getTextSafe(risk, "rischio",   ""), "000000",         9, false);
            setRowCell(row, 3, colWidths[3], getRiskBgColor(level), level,                              getRiskColor(level), 9, true);
            setRowCell(row, 4, colWidths[4], rowBg,                 getTextSafe(risk, "azione",    ""), "000000",         9, false);

            rowIdx++;
        }

        addTableBorders(table);
    }

    // -------------------------------------------------------------------------
    // Analisi Dettagliata
    // -------------------------------------------------------------------------

    private void addDetailedAnalysis(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "3. ANALISI DETTAGLIATA PER SEZIONE");

        JsonNode sections = root.path("analisi_sezioni");
        if (!sections.isArray() || sections.isEmpty()) {
            addBodyText(doc, "Nessuna sezione analizzata.");
            return;
        }

        for (JsonNode section : sections) {
            addHeading2(doc, getTextSafe(section, "titolo", "Sezione"));

            String sommario = getTextSafe(section, "sommario", "");
            if (!sommario.isBlank()) {
                addBodyText(doc, sommario);
            }

            JsonNode sectionRisks = section.path("rischi");
            if (sectionRisks.isArray() && !sectionRisks.isEmpty()) {
                for (JsonNode risk : sectionRisks) {
                    String level         = getTextSafe(risk, "livello", "MEDIO");
                    String clausola      = getTextSafe(risk, "clausola", "");
                    String descrizione   = getTextSafe(risk, "descrizione", "");
                    String raccomandaz   = getTextSafe(risk, "raccomandazione", "");

                    XWPFParagraph riskPara = doc.createParagraph();
                    riskPara.setIndentationLeft(360);
                    setSpacingBefore(riskPara, 80);

                    XWPFRun lvlRun = riskPara.createRun();
                    lvlRun.setText("[" + level + "]  ");
                    lvlRun.setBold(true);
                    lvlRun.setColor(getRiskColor(level));
                    lvlRun.setFontSize(10);
                    lvlRun.setFontFamily("Calibri");

                    if (!clausola.isBlank()) {
                        XWPFRun clRun = riskPara.createRun();
                        clRun.setText("(" + clausola + ")  ");
                        clRun.setBold(true);
                        clRun.setFontSize(10);
                        clRun.setFontFamily("Calibri");
                    }

                    XWPFRun descRun = riskPara.createRun();
                    descRun.setText(descrizione);
                    descRun.setFontSize(10);
                    descRun.setFontFamily("Calibri");

                    if (!raccomandaz.isBlank()) {
                        XWPFParagraph raccPara = doc.createParagraph();
                        raccPara.setIndentationLeft(720);
                        setSpacingBefore(raccPara, 40);

                        XWPFRun raccLabel = raccPara.createRun();
                        raccLabel.setText("→ Raccomandazione: ");
                        raccLabel.setBold(true);
                        raccLabel.setItalic(true);
                        raccLabel.setFontSize(9);
                        raccLabel.setColor("2E4899");
                        raccLabel.setFontFamily("Calibri");

                        XWPFRun raccRun = raccPara.createRun();
                        raccRun.setText(raccomandaz);
                        raccRun.setItalic(true);
                        raccRun.setFontSize(9);
                        raccRun.setFontFamily("Calibri");
                    }
                }
            }

            JsonNode missingInSection = section.path("clausole_mancanti");
            if (missingInSection.isArray() && !missingInSection.isEmpty()) {
                XWPFParagraph missingLabel = doc.createParagraph();
                missingLabel.setIndentationLeft(360);
                setSpacingBefore(missingLabel, 80);
                XWPFRun labelRun = missingLabel.createRun();
                labelRun.setText("Clausole mancanti in questa sezione:");
                labelRun.setBold(true);
                labelRun.setFontSize(9);
                labelRun.setColor(COLOR_GRAY_TEXT);
                labelRun.setFontFamily("Calibri");

                for (JsonNode c : missingInSection) {
                    XWPFParagraph cp = doc.createParagraph();
                    cp.setIndentationLeft(720);
                    XWPFRun cr = cp.createRun();
                    cr.setText("• " + c.asText());
                    cr.setFontSize(9);
                    cr.setColor(COLOR_GRAY_TEXT);
                    cr.setFontFamily("Calibri");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Top 5 Clausole da Negoziare
    // -------------------------------------------------------------------------

    private void addTop5Clauses(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "4. TOP 5 CLAUSOLE DA NEGOZIARE");

        JsonNode clauses = root.path("top5_clausole");
        if (!clauses.isArray() || clauses.isEmpty()) {
            addBodyText(doc, "Nessuna clausola identificata.");
            return;
        }

        int idx = 1;
        for (JsonNode clause : clauses) {
            String ref      = getTextSafe(clause, "riferimento", "");
            String attuale  = getTextSafe(clause, "testo_attuale", "");
            String proposto = getTextSafe(clause, "testo_proposto", "");

            XWPFParagraph numPara = doc.createParagraph();
            setSpacingBefore(numPara, 240);
            XWPFRun numRun = numPara.createRun();
            numRun.setText(idx + ".  Clausola " + ref);
            numRun.setBold(true);
            numRun.setFontSize(12);
            numRun.setColor(COLOR_NAVY);
            numRun.setFontFamily("Calibri");

            int colW = CONTENT_WIDTH / 2;
            XWPFTable table = doc.createTable(2, 2);
            setTableWidth(table, CONTENT_WIDTH);

            XWPFTableRow hRow = table.getRow(0);
            setCellWidth(hRow.getCell(0), colW);
            setCellBackground(hRow.getCell(0), COLOR_ALTO);
            setCellText(hRow.getCell(0), "Testo Attuale", COLOR_WHITE, 10, true);
            setCellWidth(hRow.getCell(1), colW);
            setCellBackground(hRow.getCell(1), COLOR_BASSO);
            setCellText(hRow.getCell(1), "Testo Proposto", COLOR_WHITE, 10, true);

            XWPFTableRow cRow = table.getRow(1);
            setCellWidth(cRow.getCell(0), colW);
            setCellBackground(cRow.getCell(0), COLOR_ALTO_BG);
            setCellText(cRow.getCell(0), attuale, "000000", 9, false);
            setCellWidth(cRow.getCell(1), colW);
            setCellBackground(cRow.getCell(1), COLOR_BASSO_BG);
            setCellText(cRow.getCell(1), proposto, "000000", 9, false);

            addTableBorders(table);
            idx++;
        }
    }

    // -------------------------------------------------------------------------
    // Clausole Mancanti
    // -------------------------------------------------------------------------

    private void addMissingClauses(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "5. CLAUSOLE MANCANTI");

        JsonNode missing = root.path("clausole_mancanti_globali");
        if (!missing.isArray() || missing.isEmpty()) {
            addBodyText(doc, "Nessuna clausola mancante identificata.");
            return;
        }

        for (JsonNode clause : missing) {
            XWPFParagraph para = doc.createParagraph();
            para.setIndentationLeft(360);
            setSpacingBefore(para, 60);
            XWPFRun run = para.createRun();
            run.setText("• " + clause.asText());
            run.setFontSize(11);
            run.setFontFamily("Calibri");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers POI
    // -------------------------------------------------------------------------

    private void addHeading1(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        setSpacingBefore(para, 400);
        setSpacingAfter(para, 160);

        CTPPr pPr = para.getCTP().isSetPPr() ? para.getCTP().getPPr() : para.getCTP().addNewPPr();
        CTPBdr pBdr = pPr.isSetPBdr() ? pPr.getPBdr() : pPr.addNewPBdr();
        CTBorder bottom = pBdr.isSetBottom() ? pBdr.getBottom() : pBdr.addNewBottom();
        bottom.setVal(STBorder.SINGLE);
        bottom.setSz(BigInteger.valueOf(6));
        bottom.setColor(COLOR_NAVY);
        bottom.setSpace(BigInteger.valueOf(4));

        XWPFRun run = para.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(16);
        run.setColor(COLOR_NAVY);
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
        run.setColor(COLOR_NAVY);
        run.setFontFamily("Calibri");
    }

    private void addBodyText(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.BOTH);
        setSpacingAfter(para, 100);
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setFontSize(11);
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
        XWPFParagraph para = cell.getParagraphs().get(0);
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
        run.setText(text);
        run.setColor(color);
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setFontFamily("Calibri");
    }

    private void setMarginValue(CTTblWidth w, int value) {
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(value));
    }

    private void setRowCell(XWPFTableRow row, int col, int width, String bg, String text, String color, int fontSize, boolean bold) {
        XWPFTableCell cell = row.getCell(col);
        setCellWidth(cell, width);
        setCellBackground(cell, bg);
        setCellText(cell, text, color, fontSize, bold);
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
            b.setSz(BigInteger.valueOf(4));
            b.setColor("BFBFBF");
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

    // -------------------------------------------------------------------------
    // Utilities colore / testo
    // -------------------------------------------------------------------------

    private String getRiskColor(String level) {
        if (level == null) return "000000";
        return switch (level.toUpperCase()) {
            case "ALTO"  -> COLOR_ALTO;
            case "MEDIO" -> COLOR_MEDIO;
            case "BASSO" -> COLOR_BASSO;
            default      -> "000000";
        };
    }

    private String getRiskBgColor(String level) {
        if (level == null) return "FFFFFF";
        return switch (level.toUpperCase()) {
            case "ALTO"  -> COLOR_ALTO_BG;
            case "MEDIO" -> COLOR_MEDIO_BG;
            case "BASSO" -> COLOR_BASSO_BG;
            default      -> "FFFFFF";
        };
    }

    private String getValutazioneColor(String val) {
        if (val == null) return COLOR_NAVY;
        return switch (val.toUpperCase()) {
            case "SFAVOREVOLE" -> COLOR_ALTO;
            case "EQUILIBRATO" -> COLOR_MEDIO;
            case "FAVOREVOLE"  -> COLOR_BASSO;
            default            -> COLOR_NAVY;
        };
    }

    private String getTextSafe(JsonNode node, String field, String def) {
        JsonNode n = node.path(field);
        return (n.isMissingNode() || n.isNull()) ? def : n.asText(def);
    }

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

        // Se il JSON è completo lo restituiamo as-is, altrimenti lo ripariamo
        int end = s.lastIndexOf('}');
        if (end > 0 && end == s.length() - 1) {
            return s; // già terminato correttamente
        }

        return repairTruncatedJson(s);
    }

    /**
     * Chiude un JSON troncato aggiungendo le parentesi mancanti.
     * Gestisce stringhe con escape, array e oggetti annidati.
     */
    private String repairTruncatedJson(String json) {
        StringBuilder sb = new StringBuilder(json.stripTrailing());

        // Rimuove eventuale virgola finale prima di chiudere
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == ',') {
            sb.deleteCharAt(sb.length() - 1);
        }

        // Conta strutture aperte percorrendo il testo
        int braces = 0, brackets = 0;
        boolean inString = false, escaped = false;

        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString) {
                switch (c) {
                    case '{' -> braces++;
                    case '}' -> braces--;
                    case '[' -> brackets++;
                    case ']' -> brackets--;
                }
            }
        }

        // Chiude eventuale stringa aperta
        if (inString) sb.append('"');
        // Chiude array e oggetti aperti
        for (int i = 0; i < Math.max(0, brackets); i++) sb.append(']');
        for (int i = 0; i < Math.max(0, braces);   i++) sb.append('}');

        log.warn("JSON sintesi era troncato: riparato aggiungendo {} ']' e {} '}'", brackets, braces);
        return sb.toString();
    }
}