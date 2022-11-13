package org.dependencytrack.util;

import alpine.common.logging.Logger;
import alpine.common.metrics.Metrics;
import com.google.common.util.concurrent.Striped;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * The purpose of this class is to prevent so called Cache-Stampede (see https://en.wikipedia.org/wiki/Cache_stampede).
 * It uses a partitioned read write lock to detect all threads trying to populate the cache for a given key, let a single key perform the computation and return the result for all threads.
 *
 * This stampede blocker allow configuring :
 *
 * - The number of partitions used by the striped lock to handle various degree of parallel workloads
 * - The waiting behavior of competing threads. In case, the loader directly populate the cache and returns no value, there is no need for competing threads to wait
 * - The number of retries to perform in case of any exception during cache loader execution
 *
 * @param <K>
 *          Key type
 *
 * @param <V>
 *          Value type
 */
public class CacheStampedeBlocker<K, V> {

    /**
     * Default TTL for cache loader entries.
     */
    private static final long DEFAULT_CACHE_LOADER_ENTRY_TTL_MS = Duration.ofMinutes(10).toMillis();

    /**
     * Maximum duration for retries.
     */
    private static final long MAX_RETRY_DURATION_MS = Duration.ofMinutes(10).toMillis();

    /**
     * Maximum number of retries.
     */
    private static final int MAX_RETRY_NUMBER = 3;

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(CacheStampedeBlocker.class);

    /**
     * Cache name.
     */
    private String cacheName;

    /**
     * Striped lock to have a fine-grain lock to reduce contention created by competing threads.
     */
    private Striped<ReadWriteLock> stripedLock;

    /**
     * Cache loaders map.
     */
    private Map<K, ExpirableCompletableFuture<V>> cacheLoaders;

    /**
     * Cache Loader entry TTL.
     */
    private long cacheLoaderEntryTTL;

    /**
     * Flag to block competing threads if computation is ongoing.
     *  False is helpful for loaders returning no value. As long as a thread pass through, there is no need for competing threads to wait.
     *  True is helpful for loaders returning a value.
     */
    private boolean blockCompetingThreads;

    /**
     * Maximum number of retry.
     */
    private int nbRetryMax;

    /**
     * Retry instance.
     */
    private Retry retry;

    /**
     * Timer instance.
     */
    private Timer timer = new Timer();

    public CacheStampedeBlocker(String cacheName, int nbBuckets, boolean blockCompetingThreads) {
        this(cacheName, nbBuckets, blockCompetingThreads, MAX_RETRY_NUMBER, DEFAULT_CACHE_LOADER_ENTRY_TTL_MS);
    }


    public CacheStampedeBlocker(String cacheName, int nbBuckets, boolean blockCompetingThreads, int nbRetryMax) {
        this(cacheName, nbBuckets, blockCompetingThreads, nbRetryMax, DEFAULT_CACHE_LOADER_ENTRY_TTL_MS);
    }

    public CacheStampedeBlocker(String cacheName, int nbBuckets, boolean blockCompetingThreads, int nbRetryMax, long cacheLoaderEntryTTL) {
        LOGGER.debug("Striped Lock is configured with "+nbBuckets+" buckets");
        this.cacheName = cacheName;
        stripedLock = Striped.lazyWeakReadWriteLock(nbBuckets);
        cacheLoaders = new ConcurrentHashMap<>();
        this.blockCompetingThreads = blockCompetingThreads;
        this.nbRetryMax = nbRetryMax;
        IntervalFunction intervalWithCustomExponentialBackoff = IntervalFunction
                .ofExponentialBackoff(IntervalFunction.DEFAULT_INITIAL_INTERVAL, 2d, MAX_RETRY_DURATION_MS);
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(this.nbRetryMax)
                .intervalFunction(intervalWithCustomExponentialBackoff)
                .failAfterMaxAttempts(true)
                .build();
        RetryRegistry registry = RetryRegistry.of(config);
        retry = registry.retry("cacheStampedeBlocker");
        this.cacheLoaderEntryTTL = cacheLoaderEntryTTL;
        timer.schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        cleanupExpirableFutureMap();
                    }
                },
                600_000,
                600_000
        );
    }

    /**
     * Read through/Populate the cache.
     *
     * @param key
     *          Cache key
     *
     * @param cacheLoader
     *          Callable to read through/populate the cache
     *
     * @return An optional containing the computed value or Optional.empty in case of any non transient exception
     */
    public Optional<V> readThroughOrPopulateCache(K key, Callable<V> cacheLoader) {
        LOGGER.debug("Trying to read through/populate cache "+cacheName+" for key "+key);
        ReadWriteLock rwLock = stripedLock.get(key);
        ExpirableCompletableFuture<V> cachePopulationFuture = null;

        LOGGER.debug("Acquiring readLock for cache "+cacheName+" and key "+key);
        rwLock.readLock().lock();
        LOGGER.debug("ReadLock acquired for cache "+cacheName+" and key "+key);
        cachePopulationFuture = cacheLoaders.get(key);
        rwLock.readLock().unlock();

        if (cachePopulationFuture == null || (System.currentTimeMillis() - cachePopulationFuture.getTimestamp()) > cacheLoaderEntryTTL) {
            LOGGER.debug("No ongoing population for cache "+cacheName+" and key "+key+" or entry has expired ! Trying to acquire writeLock");
            boolean locked = true;
            try {
                rwLock.writeLock().lock();
                LOGGER.debug("WriteLock acquired for cache "+cacheName+" and key "+key);
                cachePopulationFuture = cacheLoaders.get(key);
                if(cachePopulationFuture == null) {
                    LOGGER.debug("Populating cache "+cacheName+" for key "+key+" and returning value");
                    cachePopulationFuture = new ExpirableCompletableFuture(System.currentTimeMillis(), new CompletableFuture<V>());
                    cacheLoaders.put(key, cachePopulationFuture);
                    rwLock.writeLock().unlock();
                    locked = false;
                    V result = retry.executeCallable(cacheLoader);
                    cachePopulationFuture.getFuture().complete(result);
                    Counter.builder("cache_stampede_blocker_load")
                            .description("Total number of load event")
                            .tags("cache", cacheName)
                            .register(Metrics.getRegistry())
                            .increment();
                    // The completable future is not directly removed to account for the threads that detected that the cache
                    // should be (re)loaded but will be scheduled a bit later. We don't want them to go through the locking-loading phase again.
                    // Cleanup will be done by a dedicated thread
                    return Optional.ofNullable(result);
                }
            } catch (Exception e) {
                LOGGER.error("An error occurred while populating cache "+cacheName+" for key "+key+" : "+e.getMessage());
                if (cachePopulationFuture != null && !cachePopulationFuture.future.isDone()) {
                    cachePopulationFuture.getFuture().completeExceptionally(e);
                    cacheLoaders.remove(key);
                }
                return Optional.empty();
            } finally {
                if(locked) {
                    rwLock.writeLock().unlock();
                }
            }
        }

        if (cachePopulationFuture != null && blockCompetingThreads) {
            LOGGER.debug("Cache "+cacheName+" population is ongoing or already finished for key "+key+" - Waiting patiently completion to return the value");
            Counter.builder("cache_stampede_blocker_wait")
                    .description("Total number of wait event")
                    .tags("cache", cacheName)
                    .register(Metrics.getRegistry())
                    .increment();
            try {
                return Optional.ofNullable(cachePopulationFuture.getFuture().get());
            } catch (InterruptedException| ExecutionException e) {
                LOGGER.error("An error occurred while populating cache "+cacheName+" for key "+key+" : "+e.getMessage());
                return Optional.empty();
            }
        } else {
            LOGGER.debug("Cache "+cacheName+" population is ongoing or already finished for key "+key+" and stampede blocker configured to return immediately in this situation");
            return Optional.empty();
        }
    }

    public void cleanupExpirableFutureMap() {
        LOGGER.debug("Starting to cleanup "+cacheName+"'s loader map");
        List<K> keysToDelete =  cacheLoaders.entrySet().stream()
                .filter(entry -> (System.currentTimeMillis() - entry.getValue().getTimestamp()) > cacheLoaderEntryTTL)
                .map(entry -> entry.getKey())
                .toList();
        keysToDelete.forEach(key -> cacheLoaders.remove(key));
        LOGGER.debug("Cleanup of "+cacheName+"'s loader map finished");
    }

    class ExpirableCompletableFuture<V> {

        private Long timestamp;

        private CompletableFuture<V> future;

        public ExpirableCompletableFuture(Long timestamp, CompletableFuture<V> future) {
            this.timestamp = timestamp;
            this.future = future;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }

        public CompletableFuture<V> getFuture() {
            return future;
        }

        public void setFuture(CompletableFuture<V> future) {
            this.future = future;
        }
    }

}
