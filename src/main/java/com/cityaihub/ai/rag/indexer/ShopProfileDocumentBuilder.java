package com.hmdp.ai.rag.indexer;

import com.hmdp.ai.rag.AiMetadataConstants;
import com.hmdp.entity.Shop;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.Map;

/**
 * Plan J：共享的店铺画像 Document 构造器。
 *
 * <p>业务路径 ({@link AiVectorIndexServiceImpl#rebuildAllShopProfiles()}) 和评估路径
 * ({@code JsonlIngestService.ingestShopProfile()}) 都用这个静态方法，
 * 保证 Milvus 文档内容**字字相同**——这是 "业务-评估同源" 的锚点。
 *
 * <p>把它独立成 util class（不是 method on service）的理由：
 * <ol>
 *   <li>评估路径要 inject 到非业务 service，避免循环依赖
 *   <li>静态方法没状态，单测容易
 *   <li>未来加 Plan K (review 层) 同模式可复用
 * </ol>
 */
public final class ShopProfileDocumentBuilder {

    private ShopProfileDocumentBuilder() {
        // util
    }

    /**
     * 把 {@link Shop} 实体转成 Spring AI {@link Document}。
     * 文本拼接 + metadata 字段约定与 Plan B 之前业务路径保持一致，不改变向量内容。
     */
    public static Document fromShop(Shop shop) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(AiMetadataConstants.DOC_TYPE, AiMetadataConstants.DOC_TYPE_SHOP_PROFILE);
        metadata.put(AiMetadataConstants.SOURCE_ID, shop.getId());
        metadata.put(AiMetadataConstants.SHOP_ID, shop.getId());
        metadata.put(AiMetadataConstants.TYPE_ID, shop.getTypeId());
        metadata.put(AiMetadataConstants.AREA, defaultString(shop.getArea()));
        metadata.put(AiMetadataConstants.USER_ID, 0L);
        metadata.put(AiMetadataConstants.SHOP_NAME, shop.getName());
        metadata.put(AiMetadataConstants.ADDRESS, shop.getAddress());
        metadata.put(AiMetadataConstants.AVG_PRICE, shop.getAvgPrice() == null ? 0L : shop.getAvgPrice());
        metadata.put(AiMetadataConstants.RATING, shop.getScore() == null ? 0.0 : shop.getScore() / 10.0);
        metadata.put(AiMetadataConstants.COMMENTS, shop.getComments() == null ? 0 : shop.getComments());
        metadata.put(AiMetadataConstants.SOLD, shop.getSold() == null ? 0 : shop.getSold());
        metadata.put(AiMetadataConstants.OPEN_HOURS, defaultString(shop.getOpenHours()));
        metadata.put(AiMetadataConstants.X, shop.getX());
        metadata.put(AiMetadataConstants.Y, shop.getY());

        // 店铺画像文本尽量保留结构化字段，方便向量召回和结果解释共用一份数据。
        String text = """
                店铺名：%s
                分类ID：%s
                商圈：%s
                地址：%s
                均价：%s元
                评分：%s分
                评论数：%s
                销量：%s
                营业时间：%s
                """.formatted(
                shop.getName(),
                shop.getTypeId(),
                defaultString(shop.getArea()),
                shop.getAddress(),
                shop.getAvgPrice(),
                shop.getScore() == null ? "未知" : shop.getScore() / 10.0,
                shop.getComments(),
                shop.getSold(),
                defaultString(shop.getOpenHours())
        );
        return new Document(String.valueOf(shop.getId()), text, metadata);
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }
}
