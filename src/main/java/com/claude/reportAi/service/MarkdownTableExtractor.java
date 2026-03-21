package com.claude.reportAi.service;

import com.claude.reportAi.dto.MarkdownTable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class MarkdownTableExtractor {

    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile(
            "^\\s*\\|(.+?)\\|\\s*$"
    );
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile(
            "^\\s*\\|\\s*[-\\s:]+\\|\\s*$"
    );

    public MarkdownTable extractTable(String content) {
        if (content == null || content.isBlank()) {
            log.debug("Content is null or blank, returning empty table");
            return new MarkdownTable(false, List.of(), List.of());
        }

        String[] lines = content.split("\n");
        List<Integer> tableLineIndices = findTableLineIndices(lines);

        if (tableLineIndices.isEmpty()) {
            log.debug("No Markdown table found in content");
            return new MarkdownTable(false, List.of(), List.of());
        }

        int headerIndex = tableLineIndices.get(0);
        List<String> headers = parseTableRow(lines[headerIndex]);

        if (headers.isEmpty()) {
            log.warn("Header row is empty");
            return new MarkdownTable(false, List.of(), List.of());
        }

        int separatorIndex = findSeparatorAfter(lines, headerIndex);
        if (separatorIndex < 0) {
            log.warn("No separator row found after header");
            return new MarkdownTable(false, List.of(), List.of());
        }

        if (!isSeparatorRow(lines[separatorIndex])) {
            log.warn("Invalid Markdown separator row at index {}", separatorIndex);
            return new MarkdownTable(false, List.of(), List.of());
        }

        // Extract data rows
        List<List<String>> rows = new ArrayList<>();
        for (int i = separatorIndex + 1; i < lines.length; i++) {
            if (!isTableRow(lines[i])) {
                break;
            }
            List<String> row = parseTableRow(lines[i]);
            if (!row.isEmpty()) {
                // Normalize row size to match header count
                while (row.size() < headers.size()) {
                    row.add("");
                }
                if (row.size() > headers.size()) {
                    row = new ArrayList<>(row.subList(0, headers.size()));
                }
                rows.add(row);
            }
        }

        if (rows.isEmpty()) {
            log.warn("No data rows found in Markdown table");
            return new MarkdownTable(false, List.of(), List.of());
        }

        log.info("Markdown table extracted successfully -> headers={}, rows={}",
                headers.size(), rows.size());

        return new MarkdownTable(true, headers, rows);
    }

    private List<Integer> findTableLineIndices(String[] lines) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (isTableRow(lines[i])) {
                indices.add(i);
            }
        }
        return indices;
    }

    private int findSeparatorAfter(String[] lines, int startIndex) {
        for (int i = startIndex + 1; i < Math.min(startIndex + 5, lines.length); i++) {
            if (isSeparatorRow(lines[i])) {
                return i;
            }
        }
        return -1;
    }

    private boolean isTableRow(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        return TABLE_ROW_PATTERN.matcher(line).matches();
    }

    private boolean isSeparatorRow(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        return SEPARATOR_PATTERN.matcher(line).matches();
    }

    private List<String> parseTableRow(String row) {
        Matcher matcher = TABLE_ROW_PATTERN.matcher(row);
        if (!matcher.matches()) {
            return List.of();
        }

        String content = matcher.group(1);
        String[] cells = content.split("\\|", -1);

        List<String> result = new ArrayList<>();
        for (String cell : cells) {
            String normalized = cell.trim();
            // Unescape pipes
            normalized = normalized.replace("\\|", "|");
            // Clean newlines
            normalized = normalized.replace("\n", " ").replace("\r", " ");
            result.add(normalized);
        }

        return result;
    }
}
