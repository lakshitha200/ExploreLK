package com.explorelk.destination.config;

import com.explorelk.destination.attraction.dto.AttractionResponse;
import com.explorelk.destination.category.dto.CategoryResponse;
import com.explorelk.destination.common.PageResponse;
import com.explorelk.destination.destination.dto.DestinationDetailResponse;
import com.explorelk.destination.destination.dto.DestinationSummaryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis, used as a cache and nothing else.
 *
 * <p>Everything stored here is derived data with a copy in Postgres. Nothing
 * lives <em>only</em> in Redis, so a flushed or missing cache costs latency and
 * never correctness — which is exactly what makes {@link #errorHandler()} a
 * safe thing to do rather than a way of hiding bugs.
 *
 * <table>
 *   <caption>The four caches</caption>
 *   <tr><th>Cache</th><th>Redis key</th><th>Holds</th><th>TTL</th></tr>
 *   <tr><td>{@code destination}</td><td>{@code dest:{idOrSlug}}</td>
 *       <td>{@code DestinationDetailResponse}</td><td>1 h</td></tr>
 *   <tr><td>{@code destinationList}</td><td>{@code destlist:{filters}:{page}:{size}}</td>
 *       <td>{@code PageResponse<DestinationSummaryResponse>}</td><td>10 m</td></tr>
 *   <tr><td>{@code attractionsOf}</td><td>{@code attr:dest:{idOrSlug}}</td>
 *       <td>{@code List<AttractionResponse>}</td><td>1 h</td></tr>
 *   <tr><td>{@code categories}</td><td>{@code cat:all}</td>
 *       <td>{@code List<CategoryResponse>}</td><td>24 h</td></tr>
 * </table>
 *
 * <p><strong>{@code /nearby} is deliberately not cached.</strong> Its key space
 * is every {@code (lat, lng, radius)} triple a phone GPS ever emits, so the hit
 * rate would be near zero while Redis filled with entries nobody reads twice.
 * The GiST index is already the fast path. If it ever needs caching, round the
 * coordinates to about three decimals first to create real buckets — but measure
 * before bothering.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CatalogCacheProperties.class)
@Slf4j
public class CacheConfig implements CachingConfigurer {

    public static final String DESTINATION = "destination";
    public static final String DESTINATION_LIST = "destinationList";
    public static final String ATTRACTIONS_OF = "attractionsOf";
    public static final String CATEGORIES = "categories";

    /**
     * One serializer per cache, bound to the exact type that cache holds.
     *
     * <p><strong>This is deliberate, and the obvious alternative is broken.</strong>
     * The generic serializer records each value's class in the JSON so it can be
     * rebuilt without knowing the target type — and it works for a single object,
     * but a <em>root-level collection</em> does not survive the round trip: the
     * writer emits a plain array with the element classes inside, while the
     * reader expects the array itself to be wrapped in a type marker, and the
     * read fails with "expected VALUE_STRING ... that contains type id". Found by
     * caching {@code /categories} and {@code /attractions}, both of which return
     * a bare list, and watching the second request 500 while the first succeeded.
     *
     * <p>Binding the type per cache sidesteps the whole question. Each cache
     * holds exactly one shape, the stored JSON is clean with no {@code @class}
     * noise, generics survive because the {@link JavaType} carries them, and
     * there is no polymorphic deserialization to have to restrict for safety.
     *
     * <p>The cost is that adding a cache means adding its type here. That is the
     * right kind of cost: it fails at startup, not at the first cache hit.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          CatalogCacheProperties properties) {

        TypeFactory types = TypeFactory.createDefaultInstance();

        Map<String, RedisCacheConfiguration> caches = new LinkedHashMap<>();
        caches.put(DESTINATION, cache("dest:", properties.destination(),
                types.constructType(DestinationDetailResponse.class)));
        caches.put(DESTINATION_LIST, cache("destlist:", properties.destinationList(),
                types.constructParametricType(PageResponse.class, DestinationSummaryResponse.class)));
        caches.put(ATTRACTIONS_OF, cache("attr:dest:", properties.attractionsOf(),
                types.constructCollectionType(java.util.List.class, AttractionResponse.class)));
        caches.put(CATEGORIES, cache("cat:", properties.categories(),
                types.constructCollectionType(java.util.List.class, CategoryResponse.class)));

        log.info("Cache TTLs — destination {}, destinationList {}, attractionsOf {}, categories {}",
                properties.destination(), properties.destinationList(),
                properties.attractionsOf(), properties.categories());

        return RedisCacheManager.builder(connectionFactory)
                .withInitialCacheConfigurations(caches)
                // Only the four caches above exist. An @Cacheable naming anything
                // else is a typo, and this makes it fail loudly instead of quietly
                // creating a fifth cache with default settings that nothing evicts.
                .disableCreateOnMissingCache()
                .transactionAware()
                .build();
    }

    /**
     * <p><strong>{@code transactionAware()} on the manager is the load-bearing
     * part of this design.</strong> It defers every cache write and eviction
     * until after the surrounding transaction commits. Evicting inside the
     * transaction instead opens a window where a concurrent read repopulates the
     * cache from the old, still uncommitted state — and that stale value then
     * lives for its full TTL, long outliving the write meant to invalidate it.
     *
     * <p>Each cache also gets its own key prefix, both so the keys read the way
     * §8 of the design says and because {@code Cache.clear()} deletes by prefix:
     * with no prefix, clearing the destination list would issue {@code DEL}
     * against every key in the database, other caches included.
     *
     * <p>Null values are not cached. The read paths throw {@code NotFoundException}
     * rather than returning null, so there is nothing to store — and a typed
     * serializer cannot represent Spring's {@code NullValue} marker anyway, so
     * being explicit here turns a would-be runtime surprise into a stated rule.
     */
    private static RedisCacheConfiguration cache(String keyPrefix, Duration ttl, JavaType valueType) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .computePrefixWith(cacheName -> keyPrefix)
                .disableCachingNullValues()
                // Plain string keys, so `redis-cli KEYS 'dest:*'` during a
                // debugging session shows something a person can read.
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new JacksonJsonRedisSerializer<>(
                                JsonMapper.builder().build(), valueType)));
    }

    /**
     * A Redis outage must make this service slower, not broken.
     *
     * <p>The default handler rethrows, which turns "the cache is down" into "the
     * catalog is down" — a hard dependency on Redis that the architecture never
     * intended to have. This one logs and returns, and the caller falls through
     * to Postgres.
     *
     * <p>Reads and writes log at WARN: a miss caused by an outage is degraded
     * service, not a fault, and paging someone for it is how alerts get ignored.
     * A failed <em>eviction</em> is logged at ERROR because it is genuinely
     * different — it means a stale value may now survive for its full TTL, and
     * somebody is going to report seeing old data with no other trace of why.
     *
     * <p><strong>This has to arrive through {@link CachingConfigurer}, not as a
     * plain {@code @Bean}.</strong> Spring's caching infrastructure reads the
     * error handler from the configurer and ignores a bare bean of this type
     * entirely — so declaring one and stopping there compiles, starts, passes
     * every test with Redis up, and then returns 500 on every cached endpoint
     * the first time Redis goes down. Verified by stopping the container.
     */
    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache read failed [{}::{}], falling through to the database: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache,
                                            Object key, Object value) {
                log.warn("Cache write failed [{}::{}]: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.error("Cache eviction FAILED [{}::{}] — a stale entry may survive its full TTL: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.error("Cache clear FAILED [{}] — stale entries may survive their full TTL: {}",
                        cache.getName(), exception.getMessage());
            }
        };
    }
}
