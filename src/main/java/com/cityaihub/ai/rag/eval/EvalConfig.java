package com.cityaihub.ai.rag.eval;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个评估配置：(threshold, topK) 笛卡尔积之一。
 */
@Data
@AllArgsConstructor
public class EvalConfig {
    private final double threshold;
    private final int topK;

    public static List<EvalConfig> generateAll(List<Double> thresholds, List<Integer> topKList) {
        List<EvalConfig> configs = new ArrayList<>(thresholds.size() * topKList.size());
        for (Double t : thresholds) {
            for (Integer k : topKList) {
                configs.add(new EvalConfig(t, k));
            }
        }
        return configs;
    }

    public String label() {
        return String.format("threshold=%.2f, topK=%d", threshold, topK);
    }
}
