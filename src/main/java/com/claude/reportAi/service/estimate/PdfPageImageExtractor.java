package com.claude.reportAi.service.estimate;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Crea JPEG compatti per la lettura multimodale.
 *
 * Strategia:
 * - prova a estrarre immagini/figure reali dal PDF e le impagina in tavole composite;
 * - se non trova immagini significative, renderizza pagine rappresentative e le accorpa;
 * - restituisce al massimo app.pdf-images.max-output-images allegati.
 */
@Component
@Slf4j
public class PdfPageImageExtractor {

    private static final int MAX_RECURSION_DEPTH = 4;
    private static final int SHEET_WIDTH = 1800;
    private static final int PADDING = 24;
    private static final int GUTTER = 20;
    private static final int LABEL_HEIGHT = 30;

    private final int dpi;
    private final float jpegQuality;
    private final int maxSourcePages;
    private final int maxOutputImages;
    private final int maxEmbeddedImages;
    private final int minEmbeddedWidth;
    private final int minEmbeddedHeight;
    private final int minEmbeddedArea;

    public PdfPageImageExtractor(
            @Value("${app.pdf-images.dpi:100}") int dpi,
            @Value("${app.pdf-images.jpeg-quality:0.72}") float jpegQuality,
            @Value("${app.pdf-images.max-source-pages:10}") int maxSourcePages,
            @Value("${app.pdf-images.max-output-images:5}") int maxOutputImages,
            @Value("${app.pdf-images.max-embedded-images:40}") int maxEmbeddedImages,
            @Value("${app.pdf-images.min-embedded-width:250}") int minEmbeddedWidth,
            @Value("${app.pdf-images.min-embedded-height:120}") int minEmbeddedHeight,
            @Value("${app.pdf-images.min-embedded-area:100000}") int minEmbeddedArea) {

        this.dpi = Math.max(72, dpi);
        this.jpegQuality = Math.max(0.1f, Math.min(1.0f, jpegQuality));
        this.maxSourcePages = Math.max(1, maxSourcePages);
        this.maxOutputImages = Math.max(1, maxOutputImages);
        this.maxEmbeddedImages = Math.max(1, maxEmbeddedImages);
        this.minEmbeddedWidth = Math.max(1, minEmbeddedWidth);
        this.minEmbeddedHeight = Math.max(1, minEmbeddedHeight);
        this.minEmbeddedArea = Math.max(1, minEmbeddedArea);
    }

    /**
     * Estrae visual significativi del PDF come JPEG compositi.
     * In caso di errore restituisce lista vuota per non bloccare la pipeline.
     */
    public List<byte[]> extractPageImages(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();

            List<VisualImage> embeddedImages = extractEmbeddedImages(document);
            if (!embeddedImages.isEmpty()) {
                List<byte[]> sheets = buildContactSheets(embeddedImages);
                log.info("Estratte {} immagini/figure PDF e impaginate in {} tavole composite (pagine totali: {})",
                        embeddedImages.size(), sheets.size(), totalPages);
                return sheets;
            }

            List<VisualImage> renderedPages = renderRepresentativePages(document);
            List<byte[]> sheets = buildContactSheets(renderedPages);
            log.info("Estratte {} pagine rappresentative PDF e impaginate in {} tavole composite (pagine totali: {})",
                    renderedPages.size(), sheets.size(), totalPages);
            return sheets;

        } catch (Exception e) {
            log.warn("Impossibile caricare PDF per estrazione immagini: {}", e.getMessage());
            return List.of();
        }
    }

    private List<VisualImage> extractEmbeddedImages(PDDocument document) {
        List<VisualImage> images = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            if (images.size() >= maxEmbeddedImages) break;
            try {
                PDResources resources = document.getPage(pageIndex).getResources();
                collectEmbeddedImages(resources, pageIndex + 1, images, new HashSet<>(), 0);
            } catch (Exception e) {
                log.debug("Errore estrazione immagini da pagina {}: {}", pageIndex + 1, e.getMessage());
            }
        }
        return images;
    }

    private void collectEmbeddedImages(
            PDResources resources,
            int pageNumber,
            List<VisualImage> images,
            Set<Integer> visitedResources,
            int depth) throws IOException {

        if (resources == null || depth > MAX_RECURSION_DEPTH || images.size() >= maxEmbeddedImages) {
            return;
        }

        int resourceKey = System.identityHashCode(resources);
        if (!visitedResources.add(resourceKey)) {
            return;
        }

        for (COSName name : resources.getXObjectNames()) {
            if (images.size() >= maxEmbeddedImages) break;
            PDXObject xObject = resources.getXObject(name);
            if (xObject instanceof PDImageXObject imageObject) {
                BufferedImage image = imageObject.getImage();
                if (isMeaningfulEmbeddedImage(image)) {
                    images.add(new VisualImage("Pag. " + pageNumber + " - figura", image, false));
                }
            } else if (xObject instanceof PDFormXObject formObject) {
                collectEmbeddedImages(formObject.getResources(), pageNumber, images, visitedResources, depth + 1);
            }
        }
    }

    private boolean isMeaningfulEmbeddedImage(BufferedImage image) {
        if (image == null) return false;
        int width = image.getWidth();
        int height = image.getHeight();
        return width >= minEmbeddedWidth
                && height >= minEmbeddedHeight
                && (width * height) >= minEmbeddedArea;
    }

    private List<VisualImage> renderRepresentativePages(PDDocument document) {
        List<VisualImage> pages = new ArrayList<>();
        PDFRenderer renderer = new PDFRenderer(document);
        for (int pageIndex : selectPageIndexes(document.getNumberOfPages())) {
            try {
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
                pages.add(new VisualImage("Pag. " + (pageIndex + 1), image, true));
            } catch (Exception e) {
                log.warn("Impossibile renderizzare pagina {} del PDF: {}", pageIndex + 1, e.getMessage());
            }
        }
        return pages;
    }

    private List<Integer> selectPageIndexes(int totalPages) {
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        int pagesToRender = Math.min(totalPages, maxSourcePages);
        if (pagesToRender <= 1) {
            indexes.add(0);
        } else {
            for (int k = 0; k < pagesToRender; k++) {
                int idx = Math.round(k * (totalPages - 1) / (float) (pagesToRender - 1));
                indexes.add(idx);
            }
        }
        return new ArrayList<>(indexes);
    }

    private List<byte[]> buildContactSheets(List<VisualImage> images) throws IOException {
        if (images.isEmpty()) return List.of();

        int sheetCount = Math.min(maxOutputImages, images.size());
        int imagesPerSheet = (int) Math.ceil(images.size() / (double) sheetCount);
        List<byte[]> sheets = new ArrayList<>(sheetCount);

        for (int start = 0; start < images.size(); start += imagesPerSheet) {
            int end = Math.min(start + imagesPerSheet, images.size());
            sheets.add(toJpeg(buildContactSheet(images.subList(start, end))));
        }
        return sheets;
    }

    private BufferedImage buildContactSheet(List<VisualImage> chunk) {
        int columns = chunk.size() == 1 ? 1 : 2;
        int cellWidth = (SHEET_WIDTH - (PADDING * 2) - (GUTTER * (columns - 1))) / columns;
        int[] scaledWidths = new int[chunk.size()];
        int[] scaledHeights = new int[chunk.size()];
        int[] rowHeights = new int[(int) Math.ceil(chunk.size() / (double) columns)];

        for (int i = 0; i < chunk.size(); i++) {
            VisualImage item = chunk.get(i);
            int maxCellHeight = item.renderedPage() ? 1200 : 800;
            double scale = Math.min(cellWidth / (double) item.image().getWidth(),
                    maxCellHeight / (double) item.image().getHeight());
            scale = Math.min(1.0d, scale);
            scaledWidths[i] = Math.max(1, (int) Math.round(item.image().getWidth() * scale));
            scaledHeights[i] = Math.max(1, (int) Math.round(item.image().getHeight() * scale));
            int row = i / columns;
            rowHeights[row] = Math.max(rowHeights[row], LABEL_HEIGHT + scaledHeights[i]);
        }

        int height = PADDING;
        for (int rowHeight : rowHeights) {
            height += rowHeight + GUTTER;
        }
        height += PADDING - GUTTER;

        BufferedImage sheet = new BufferedImage(SHEET_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, SHEET_WIDTH, height);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));

            int y = PADDING;
            for (int row = 0; row < rowHeights.length; row++) {
                for (int col = 0; col < columns; col++) {
                    int i = row * columns + col;
                    if (i >= chunk.size()) break;

                    int x = PADDING + col * (cellWidth + GUTTER);
                    VisualImage item = chunk.get(i);
                    g.setColor(new Color(40, 40, 40));
                    g.drawString(item.label(), x, y + 21);
                    g.drawImage(item.image(), x, y + LABEL_HEIGHT, scaledWidths[i], scaledHeights[i], null);
                    g.setColor(new Color(220, 220, 220));
                    g.drawRect(x, y + LABEL_HEIGHT, scaledWidths[i], scaledHeights[i]);
                }
                y += rowHeights[row] + GUTTER;
            }
        } finally {
            g.dispose();
        }
        return sheet;
    }

    private byte[] toJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(jpegQuality);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    private record VisualImage(String label, BufferedImage image, boolean renderedPage) {
    }
}
