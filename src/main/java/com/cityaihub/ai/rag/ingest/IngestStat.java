package com.cityaihub.ai.rag.ingest;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 灌库统计：每个 collection 的灌入结果汇总。
 */
@Data
@AllArgsConstructor
public class IngestStat {
    private final String collection;
    private final int total;
    private final int succeeded;
    private final int failed;
    private final long elapsedMs;
    private final List<String> failedIds;

    public double successRate() {
        return total == 0 ? 0.0 : (double) succeeded / total;
    }
}
