package com.cityaihub.ai.rag.indexer;

import com.cityaihub.ai.dto.IndexRebuildResponse;

public interface AiVectorIndexService {

    IndexRebuildResponse rebuildAll();

    IndexRebuildResponse rebuildShop(Long shopId);

    int rebuildKnowledge();

    void ensureInitialized();
}
