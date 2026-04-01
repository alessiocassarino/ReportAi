package com.claude.reportAi.service.report;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Converte le pagine di un PDF in immagini PNG per la lettura multimodale
 * (Gantt, grafici, schemi tecnici non estraibili come testo da Tika).
 *
 * Strategia di selezione pagine:
 *  - PDF ≤ 10 pagine → tutte le pagine
 *  - PDF > 10 pagine → prime 5 + ultime 5 (copre copertina, sommario e appendici)
 */
@Component
@Slf4j
public class PdfPageImageExtractor {

    private static final int DPI = 150;
    private static final int MAX_PAGES_SMALL = 10;
    private static final int PAGES_EACH_SIDE = 5;

    /**
     * Estrae le pagine rilevanti del PDF come immagini PNG raw (byte[]).
     * In caso di errore restituisce lista vuota per non bloccare la pipeline.
     */
    public List<byte[]> extractPageImages(byte[] pdfBytes) {
        List<byte[]> images = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            List<Integer> pageIndexes = selectPageIndexes(totalPages);
            PDFRenderer renderer = new PDFRenderer(document);

            for (int pageIndex : pageIndexes) {
                try {
                    BufferedImage image = renderer.renderImageWithDPI(pageIndex, DPI, ImageType.RGB);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(image, "PNG", baos);
                    images.add(baos.toByteArray());
                } catch (Exception e) {
                    log.warn("Impossibile renderizzare pagina {} del PDF: {}", pageIndex + 1, e.getMessage());
                }
            }
            log.info("Estratte {}/{} pagine PDF come immagini (totale pagine: {})",
                    images.size(), pageIndexes.size(), totalPages);

        } catch (Exception e) {
            log.warn("Impossibile caricare PDF per estrazione immagini: {}", e.getMessage());
        }
        return images;
    }

    private List<Integer> selectPageIndexes(int totalPages) {
        List<Integer> indexes = new ArrayList<>();
        if (totalPages <= MAX_PAGES_SMALL) {
            for (int i = 0; i < totalPages; i++) indexes.add(i);
        } else {
            for (int i = 0; i < PAGES_EACH_SIDE; i++) indexes.add(i);
            for (int i = totalPages - PAGES_EACH_SIDE; i < totalPages; i++) {
                if (!indexes.contains(i)) indexes.add(i);
            }
        }
        return indexes;
    }
}
