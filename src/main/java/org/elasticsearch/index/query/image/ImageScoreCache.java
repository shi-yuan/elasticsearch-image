package org.elasticsearch.index.query.image;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache document score for {@link org.elasticsearch.index.query.image.ImageHashQuery}
 */
public class ImageScoreCache {
    private Map<String, Float> scoreCache = new ConcurrentHashMap<>();

    public Float getScore(String key) {
        if (!scoreCache.containsKey(key)) {
            return null;
        }
        return scoreCache.get(key);
    }

    public void setScore(String key, Float score) {
        scoreCache.put(key, score);
    }
}
