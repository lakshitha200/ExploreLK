package com.explorelk.destination.common;

import com.explorelk.destination.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Cache eviction, written out rather than annotated.
 *
 * <p>{@code @CacheEvict} handles "one method, one key". None of the rules here
 * are that:
 *
 * <ul>
 *   <li>A destination is cached under <strong>both</strong> its id and its slug,
 *       because the public endpoint resolves either. Editing it has to evict two
 *       keys, and SpEL that reaches into the result to find the slug is harder
 *       to read than the method below and fails silently when it is wrong.</li>
 *   <li>Publishing a <em>new</em> destination invalidates every cached list
 *       without touching any single list key — so lists are cleared wholesale,
 *       which is a {@code clear()}, not an {@code evict(key)}.</li>
 *   <li>Editing an attraction invalidates its destination's attraction list
 *       <em>and</em> the destination itself, in two different caches.</li>
 * </ul>
 *
 * <p><strong>Every call here is deferred until after the transaction commits</strong>,
 * because {@code CacheConfig} builds the manager {@code transactionAware()}.
 * That matters more than it looks: evicting while the transaction is still open
 * lets a concurrent read repopulate the cache from the old, uncommitted state,
 * and the stale value then survives for its full TTL.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CatalogCache {

    private final CacheManager cacheManager;

    /**
     * Evicts one destination under both the forms it is cached as, then clears
     * the lists — every list is a candidate to have contained it.
     */
    public void evictDestination(UUID id, String slug) {
        evict(CacheConfig.DESTINATION, id);
        evict(CacheConfig.DESTINATION, slug);
        clear(CacheConfig.DESTINATION_LIST);
    }

    /**
     * Evicts the attraction list of one destination, again under both forms.
     *
     * <p>The destination itself goes too. An attraction edit does not change the
     * destination row, but the two are shown together and a reader who sees the
     * old attractions beside the new place has no way to tell which half is
     * stale.
     */
    public void evictAttractionsOf(UUID destinationId, String destinationSlug) {
        evict(CacheConfig.ATTRACTIONS_OF, destinationId);
        evict(CacheConfig.ATTRACTIONS_OF, destinationSlug);
        evictDestination(destinationId, destinationSlug);
    }

    /**
     * The vocabulary changed. Lists go with it — a filter UI renders from the
     * category list, and a list cached against the old vocabulary is a page a
     * user cannot reproduce.
     */
    public void evictCategories() {
        clear(CacheConfig.CATEGORIES);
        clear(CacheConfig.DESTINATION_LIST);
    }

    private void evict(String cacheName, Object key) {
        if (key == null) {
            return;
        }
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key.toString());
        }
    }

    private void clear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }
}
