package com.claude.reportAi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ContractSectionExtractor {

    private static final int CHUNK_SIZE = 5000;
    private static final int MIN_SECTIONS = 3;

    // Regex per header di sezione (Articolo X, SECTION X, ecc.)
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "(?im)^\\s*(article|articolo|section|sezione|clause|clausola)\\s+\\d+[.:]?\\s*.{0,80}$"
    );

    public record ContractSection(String title, String content, int index) {}

    public List<ContractSection> extractSections(String fullText) {
        List<ContractSection> sections = extractByHeaders(fullText);

        if (sections.size() < MIN_SECTIONS) {
            log.warn("Solo {} header trovati, uso fallback a chunk fissi", sections.size());
            sections = splitIntoChunks(fullText);
        }

        return sections;
    }

    private List<ContractSection> extractByHeaders(String text) {
        List<ContractSection> sections = new ArrayList<>();
        Matcher matcher = HEADER_PATTERN.matcher(text);

        List<int[]> headerPositions = new ArrayList<>();
        List<String> headerTitles = new ArrayList<>();

        while (matcher.find()) {
            headerPositions.add(new int[]{matcher.start(), matcher.end()});
            headerTitles.add(matcher.group().strip());
        }

        for (int i = 0; i < headerPositions.size(); i++) {
            int contentStart = headerPositions.get(i)[1];
            int contentEnd = (i + 1 < headerPositions.size())
                    ? headerPositions.get(i + 1)[0]
                    : text.length();

            String content = text.substring(contentStart, contentEnd).strip();
            if (!content.isBlank()) {
                sections.add(new ContractSection(headerTitles.get(i), content, i));
            }
        }

        return sections;
    }

    private List<ContractSection> splitIntoChunks(String text) {
        List<ContractSection> chunks = new ArrayList<>();
        int index = 0;
        int chunkIndex = 0;

        while (index < text.length()) {
            int end = Math.min(index + CHUNK_SIZE, text.length());

            // Cerca il prossimo spazio per non tagliare parole
            if (end < text.length()) {
                int spacePos = text.lastIndexOf(' ', end);
                if (spacePos > index) {
                    end = spacePos;
                }
            }

            String chunk = text.substring(index, end).strip();
            if (!chunk.isBlank()) {
                chunks.add(new ContractSection("Sezione " + (chunkIndex + 1), chunk, chunkIndex));
                chunkIndex++;
            }

            index = end;
        }

        log.info("Fallback: {} chunk generati", chunks.size());
        return chunks;
    }
}