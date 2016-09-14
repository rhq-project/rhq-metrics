/*
 * Copyright 2014-2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.metrics.core.service.cache;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toMap;

import static org.hawkular.metrics.datetime.DateTimeService.currentMinute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.hawkular.metrics.model.Buckets;
import org.hawkular.metrics.model.DataPoint;
import org.hawkular.metrics.model.Metric;
import org.hawkular.metrics.model.Percentile;
import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.commons.util.concurrent.NotifyingFuture;
import org.infinispan.context.Flag;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntriesEvicted;
import org.infinispan.notifications.cachelistener.event.Event;
import org.jboss.logging.Logger;
import org.msgpack.MessagePack;
import org.msgpack.template.ListTemplate;
import org.msgpack.template.StringTemplate;

import com.codahale.metrics.MetricRegistry;

import rx.Completable;
import rx.Single;

/**
 * @author jsanda
 */
@Listener
public class CacheServiceImpl implements CacheService {

    private static final Logger logger = Logger.getLogger(CacheServiceImpl.class);

    protected EmbeddedCacheManager cacheManager;

    private MessagePack messagePack = new MessagePack();
    private ListTemplate<String> template;

//    AdvancedCache<DataPointKey, Double> rawDataCache;
    AdvancedCache<MetricKey, MetricValue> rawDataCache;

    private MetricRegistry metricRegistry;

    public void setMetricRegistry(MetricRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }

    public void init(){
        try {
            logger.info("Initializing caches");

            template = new ListTemplate<>(StringTemplate.getInstance());

            cacheManager = new DefaultCacheManager(CacheServiceImpl.class.getResourceAsStream(
                    "/metrics-infinispan.xml"));
            cacheManager.startCaches(cacheManager.getCacheNames().toArray(new String[0]));
            Cache<MetricKey, MetricValue> cache = cacheManager.getCache("rawData");
            rawDataCache = cache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES, Flag.SKIP_LOCKING);
            // Clear cache for now to reset it for perf tests
            rawDataCache.clear();
            rawDataCache.addListener(this);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @CacheEntriesEvicted
    public void onEviction(Event event) {
        logger.info("Entries evicted: " + event);
    }

    public void shutdown() {
        logger.info("Shutting down");
        cacheManager.stop();
    }

    @Override
    public AdvancedCache<MetricKey, MetricValue> getRawDataCache() {
        return rawDataCache;
    }

    @Override
    public Completable removeFromRawDataCache(long timeSlice) {
//        return Completable.fromAction(() -> {
//            String group = Long.toString(timeSlice / 1000);
//            FunctionalMapImpl<DataPointKey, Double> fmap = FunctionalMapImpl.create(rawDataCache);
//            FunctionalMap.WriteOnlyMap<DataPointKey, Double> writeOnlyMap = WriteOnlyMapImpl.create(fmap);
//            rawDataCache.removeGroup(group);
//        });
        return Completable.complete();
    }

//    @Override
//    public Single<DataPoint<? extends Number>> put(MetricId<? extends Number> metricId,
//            DataPoint<? extends Number> dataPoint) {
//        long timeSlice = DateTimeService.getTimeSlice(dataPoint.getTimestamp(), standardMinutes(1));
//        DataPointKey key = new DataPointKey(metricId.getTenantId(), metricId.getName(), dataPoint.getTimestamp(),
//                timeSlice);
//        NotifyingFuture<Double> future = rawDataCache.putAsync(key, dataPoint.getValue());
//        return from(future);
//    }


    @Override
    public <T> Completable update(Metric<T> metric) {
        try {
            List<String> src = new ArrayList<>(2);
            src.add(metric.getMetricId().getTenantId());
            src.add(metric.getMetricId().getName());
            MetricKey key = new MetricKey(messagePack.write(src, template));

            MetricValue value = rawDataCache.get(key);
            if (value == null) {
                Buckets buckets = Buckets.fromCount(currentMinute().getMillis(),
                        currentMinute().plusMinutes(1).getMillis(), 1);
                value = new MetricValue(buckets, asList(new Percentile("90", 90.0), new Percentile("95", 95.0),
                        new Percentile("99", 99.0)));
            } else if (isTimeSliceFinished(value)) {
                // TODO write stats to cassandra and reset collector
                Buckets buckets = Buckets.fromCount(currentMinute().getMillis(),
                        currentMinute().plusMinutes(1).getMillis(), 1);
                value.reset(buckets);
            }
            for (DataPoint<T> dataPoint : metric.getDataPoints()) {
                value.increment((DataPoint<Double>) dataPoint);
            }
            NotifyingFuture<MetricValue> future = rawDataCache.putAsync(key, value);
            return from(future).toCompletable();
        } catch (IOException e) {
            return Completable.error(e);
        }

//        try {
//            List<String> src = new ArrayList<>(2);
//            src.add(metric.getMetricId().getTenantId());
//            src.add(metric.getMetricId().getName());
//            MetricKey key = new MetricKey(messagePack.write(src, template));
//
//            MetricValue value = rawDataCache.get(key);
//            if (value == null) {
//                Buckets buckets = Buckets.fromCount(currentMinute().getMillis(),
//                        currentMinute().plusMinutes(1).getMillis(), 1);
//                value = new MetricValue(buckets, asList(new Percentile("90", 90.0), new Percentile("95", 95.0),
//                        new Percentile("99", 99.0)));
//            } else if (isTimeSliceFinished(value)) {
//                // TODO write stats to cassandra and reset collector
//                Buckets buckets = Buckets.fromCount(currentMinute().getMillis(),
//                        currentMinute().plusMinutes(1).getMillis(), 1);
//                value.reset(buckets);
//            }
//            for (DataPoint<T> dataPoint : metric.getDataPoints()) {
//                value.increment((DataPoint<Double>) dataPoint);
//            }
//            rawDataCache.put(key, value);
//
//            return Completable.complete();
//        } catch (IOException e) {
//            return Completable.error(e);
//        }
    }

    @Override
    public <T> Completable updateAll(List<Metric<T>> metrics) {
        Map<MetricKey, Metric<T>> metricsMap = metrics.stream().collect(toMap(this::getKey, Function.identity()));
//        Set<MetricKey> keys = metrics.stream().map(this::getKey).collect(toSet());
        Map<MetricKey, MetricValue> map = rawDataCache.getAll(metricsMap.keySet());
        metricsMap.entrySet().forEach(entry -> {
            MetricValue value = map.get(entry.getKey());
            entry.getValue().getDataPoints().forEach(dataPoint -> value.increment((DataPoint<Double>) dataPoint));
        });
        NotifyingFuture<Void> future = rawDataCache.putAllAsync(map);
        return from(future).toCompletable();
//        return Completable.complete();
    }

    private <T> MetricKey getKey(Metric<T> metric) {
        try {
            List<String> src = new ArrayList<>(2);
            src.add(metric.getMetricId().getTenantId());
            src.add(metric.getMetricId().getName());
            return new MetricKey(messagePack.write(src, template));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isTimeSliceFinished(MetricValue value) {
        return value.getBuckets().getStart() + value.getBuckets().getStep() >= System.currentTimeMillis();
    }

    @Override
    public <T> Completable putAll(List<Metric<T>> metrics) {
//        Stopwatch stopwatch = Stopwatch.createStarted();
//        Map<DataPointKey, Double> map = new HashMap<>();
//        metrics.forEach(metric -> {
//            metric.getDataPoints().forEach(dataPoint -> {
//                long minute = DateTimeService.getTimeSlice(dataPoint.getTimestamp(), standardMinutes(1));
//                String timeSlice = Long.toString(minute / 1000);
//                DataPointKey key = new DataPointKey(encode(metric.getMetricId().getTenantId(),
//                        metric.getMetricId().getName(), timeSlice), timeSlice);
//                map.put(key, (Double) dataPoint.getValue());
//            });
//        });
//        NotifyingFuture<Void> future = rawDataCache.putAllAsync(map);
//        return from(future).toCompletable();
        return Completable.complete();
    }

    private byte[] encode(String tenantId, String metric, String timeSlice) {
        try {
            List<String> src = new ArrayList<>(3);
            src.add(tenantId);
            src.add(metric);
            src.add(timeSlice);

            return messagePack.write(src, template);
//            return messagePack.write(src);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> Single<T> from(NotifyingFuture<T> notifyingFuture) {
        return Single.create(subscriber -> {
            notifyingFuture.attachListener(future -> {
                try {
                    subscriber.onSuccess(future.get());
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            });
        });
    }

}
