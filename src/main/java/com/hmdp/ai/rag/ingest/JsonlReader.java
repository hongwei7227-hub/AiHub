package com.hmdp.ai.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用 JSONL 读取器：一行一个 JSON 对象，反序列化到指定 DTO。
 * 容错：空行、# 开头的注释行跳过；解析失败的行打 warning，不中断流程。
 */
@Slf4j
@Component
public class JsonlReader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public <T> List<T> readAll(Path file, Class<T> clazz) {
        List<T> result = new ArrayList<>();
        int lineNum = 0;
        int parseFails = 0;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                try {
                    result.add(objectMapper.readValue(trimmed, clazz));
                } catch (Exception e) {
                    parseFails++;
                    if (parseFails <= 3) {
                        log.warn("Failed to parse {} line {}: {}", file.getFileName(), lineNum, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read JSONL file: " + file, e);
        }

        log.info("[JsonlReader] {} -> {} records (parsed) / {} failed (out of {} lines)",
                file.getFileName(), result.size(), parseFails, lineNum);
        return result;
    }
}
