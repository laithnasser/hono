/*******************************************************************************
 * Copyright (c) 2019, 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.hono.service.resourcelimits;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import io.opentracing.noop.NoopTracerFactory;
import org.eclipse.hono.cache.CacheProvider;
import org.eclipse.hono.cache.ExpiringValueCache;
import org.eclipse.hono.client.ServiceInvocationException;
import org.eclipse.hono.service.metric.MetricsTags;
import org.eclipse.hono.service.metric.MicrometerBasedMetrics;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.DataVolume;
import org.eclipse.hono.util.DataVolumePeriod;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.TenantConstants;
import org.eclipse.hono.util.TenantObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.log.Fields;
import io.opentracing.tag.Tags;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import io.vertx.ext.web.codec.BodyCodec;

/**
 * Resource limit checks which compare configured limits with live metrics retrieved
 * from a <em>Prometheus</em> server.
 */
public final class PrometheusBasedResourceLimitChecks implements ResourceLimitChecks {

    private static final String CONNECTIONS_METRIC_NAME = MicrometerBasedMetrics.METER_CONNECTIONS_AUTHENTICATED
            .replace(".", "_");
    private static final String MESSAGES_PAYLOAD_SIZE_METRIC_NAME = String.format("%s_bytes_sum",
            MicrometerBasedMetrics.METER_MESSAGES_PAYLOAD.replace(".", "_"));
    private static final String COMMANDS_PAYLOAD_SIZE_METRIC_NAME = String.format("%s_bytes_sum",
            MicrometerBasedMetrics.METER_COMMANDS_PAYLOAD.replace(".", "_"));
    private static final Logger log = LoggerFactory.getLogger(PrometheusBasedResourceLimitChecks.class);
    private static final String QUERY_URI = "/api/v1/query";
    private static final String LIMITS_CACHE_NAME = "resource-limits";
    private final Tracer tracer;
    private final WebClient client;
    private final PrometheusBasedResourceLimitChecksConfig config;
    private final ExpiringValueCache<Object, Object> limitsCache;

    /**
     * The mode of the data volume calculation.
     *
     */
    protected enum PeriodMode {
        /**
         * The mode of the data volume calculation in terms of days.
         */
        DAYS("days"),
        /**
         * The mode of the data volume calculation is monthly.
         */
        MONTHLY("monthly"),
        /**
         * The unknown mode.
         */
        UNKNOWN("unknown");

        private final String mode;

        PeriodMode(final String mode) {
            this.mode = mode;
        }

        /**
         * Construct a PeriodMode from a given value.
         *
         * @param value The value from which the PeriodMode needs to be constructed.
         * @return The PeriodMode as enum
         */
        static PeriodMode from(final String value) {
            if (value != null) {
                for (PeriodMode mode : values()) {
                    if (value.equalsIgnoreCase(mode.value())) {
                        return mode;
                    }
                }
            }
            return UNKNOWN;
        }

        /**
         * Gets the string equivalent of the mode.
         *
         * @return The value of the mode.
         */
        String value() {
            return this.mode;
        }
    }

    /**
     * Creates new checks.
     *
     * @param webClient The client to use for querying the Prometheus server.
     * @param config The PrometheusBasedResourceLimitChecks configuration object.
     * @param cacheProvider The cache provider to use for creating caches for metrics data retrieved
     *                      from the prometheus backend or {@code null} if they should not be cached.
     * @throws NullPointerException if any of the parameters except cacheProvider are {@code null}.
     */
    public PrometheusBasedResourceLimitChecks(final WebClient webClient,
            final PrometheusBasedResourceLimitChecksConfig config, final CacheProvider cacheProvider) {
        this(webClient, config, cacheProvider, NoopTracerFactory.create());
    }

    /**
     * Creates new checks.
     *
     * @param webClient The client to use for querying the Prometheus server.
     * @param config The PrometheusBasedResourceLimitChecks configuration object.
     * @param cacheProvider The cache provider to use for creating caches for metrics data retrieved
     *                      from the prometheus backend or {@code null} if they should not be cached.
     * @param tracer The tracer instance.
     * @throws NullPointerException if any of the parameters except cacheProvider are {@code null}.
     */
    public PrometheusBasedResourceLimitChecks(final WebClient webClient,
            final PrometheusBasedResourceLimitChecksConfig config, final CacheProvider cacheProvider, final Tracer tracer) {
        this.client = Objects.requireNonNull(webClient);
        this.config = Objects.requireNonNull(config);
        this.limitsCache = Optional.ofNullable(cacheProvider)
                .map(provider -> provider.getCache(LIMITS_CACHE_NAME))
                .orElse(null);
        this.tracer = Objects.requireNonNull(tracer);
    }

    private Span createSpan(final String name, final SpanContext parent, final TenantObject tenant) {
        return TracingHelper.buildChildSpan(tracer, parent, name)
                .ignoreActiveSpan()
                .withTag(Tags.COMPONENT.getKey(), getClass().getSimpleName())
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                .withTag(Tags.PEER_HOSTNAME.getKey(), config.getHost())
                .withTag(Tags.PEER_PORT.getKey(), config.getPort())
                .withTag(Tags.HTTP_URL.getKey(), QUERY_URI)
                .withTag(MessageHelper.APP_PROPERTY_TENANT_ID, tenant.getTenantId())
                .start();
    }

    @Override
    public Future<Boolean> isConnectionLimitReached(final TenantObject tenant) {
        return isConnectionLimitReached(tenant, null);
    }

    @Override
    public Future<Boolean> isConnectionLimitReached(final TenantObject tenant, final SpanContext spanContext) {

        Objects.requireNonNull(tenant);

        final Span span = createSpan("verify connection limit", spanContext, tenant);
        final Map<String, Object> items = new HashMap<>();

        final Promise<Boolean> result = Promise.promise();

        if (tenant.getResourceLimits() == null) {
            items.put(Fields.EVENT, "no resource limits configured");
            log.trace("no resource limits configured for tenant [{}]", tenant.getTenantId());
            result.complete(Boolean.FALSE);
        } else {
            final long maxConnections = tenant.getResourceLimits().getMaxConnections();
            items.put(TenantConstants.FIELD_MAX_CONNECTIONS, maxConnections);
            log.trace("connection limit for tenant [{}] is [{}]", tenant.getTenantId(), maxConnections);

            if (maxConnections == -1) {
                items.put(Fields.EVENT, "no connection limit configured");
                result.complete(Boolean.FALSE);
            } else {
                final String queryParams = String.format("sum(%s{tenant=\"%s\"})", CONNECTIONS_METRIC_NAME,
                        tenant.getTenantId());
                executeQuery(queryParams, span)
                    .map(currentConnections -> {
                        items.put("current-connections", currentConnections);
                        final boolean isExceeded = currentConnections >= maxConnections;
                        log.trace("connection limit {}exceeded [tenant: {}, current connections: {}, max-connections: {}]",
                                isExceeded ? "" : "not ", tenant.getTenantId(), currentConnections, maxConnections);
                        return isExceeded;
                    })
                    .otherwise(failure -> Boolean.FALSE)
                    .setHandler(result);
            }
        }

        return result.future().map(b -> {
            items.put("limit exceeded", b);
            span.log(items);
            span.finish();
            return b;
        });
    }

    @Override
    public Future<Boolean> isMessageLimitReached(final TenantObject tenant, final long payloadSize) {
        return isMessageLimitReached(tenant, payloadSize, null);
    }

    /**
     * Checks if the maximum data volume for the messages configured for a tenant
     * have been reached.
     * <p>
     * There are two supported modes of calculation which are <em>days</em> and 
     * <em>monthly</em> and can be set using {@link TenantConstants#FIELD_PERIOD_MODE}. 
     * <p>
     * In the <em>monthly</em> mode, the data usage is calculated from the 
     * beginning till the end of the current (Gregorian) calendar month and 
     * compared with the maxBytes already set using {@link TenantConstants#FIELD_MAX_BYTES}.
     * There is an exception to this calculation in the first month on which this
     * limit becomes effective. In this case, the maxBytes value is recalculated 
     * based on the remaining days in the month from the effectiveSince date.
     * The data usage for these remaining days is then compared with the above 
     * recalculated value.
     * <p>
     * If the mode is set as <em>days</em> then the data usage is calculated 
     * based on the number of days set using {@link TenantConstants#FIELD_PERIOD_NO_OF_DAYS} 
     * and compared with the maxBytes.
     * 
     * @param tenant The tenant configuration to check the limit against.
     * @param payloadSize The message payload size in bytes.
     * @param spanContext The currently active OpenTracing span context that is used to
     *                    trace the limits verification or {@code null}
     *                    if no span is currently active.
     * @throws NullPointerException if the tenant object is null.
     * @return A future indicating the outcome of the check.
     *         <p>
     *         The future will be failed with a {@link ServiceInvocationException}
     *         if the check could not be performed.
     */
    @Override
    public Future<Boolean> isMessageLimitReached(
            final TenantObject tenant,
            final long payloadSize,
            final SpanContext spanContext) {

        Objects.requireNonNull(tenant);

        final Span span = createSpan("verify message limit", spanContext, tenant);
        final Map<String, Object> items = new HashMap<>();
        items.put("payload-size", payloadSize);

        final Promise<Boolean> result = Promise.promise();

        if (tenant.getResourceLimits() == null) {
            items.put(Fields.EVENT, "no resource limits configured");
            log.trace("no resource limits configured for tenant [{}]", tenant.getTenantId());
            result.complete(Boolean.FALSE);
        } else if (tenant.getResourceLimits().getDataVolume() == null) {
            items.put(Fields.EVENT, "no message limits configured");
            log.trace("no message limits configured for tenant [{}]", tenant.getTenantId());
            result.complete(Boolean.FALSE);
        } else {
            checkMessageLimit(tenant, payloadSize, items, span, result);
        }
        return result.future()
                .map(b -> {
                    items.put("limit exceeded", b);
                    span.log(items);
                    span.finish();
                    return b;
                });
    }

    private void checkMessageLimit(
            final TenantObject tenant,
            final long payloadSize,
            final Map<String, Object> items,
            final Span span,
            final Promise<Boolean> result) {

        final DataVolume dataVolumeConfig = tenant.getResourceLimits().getDataVolume();
        final long maxBytes = dataVolumeConfig.getMaxBytes();
        final Instant effectiveSince = dataVolumeConfig.getEffectiveSince();
        final PeriodMode periodMode = PeriodMode.from(dataVolumeConfig.getPeriod().getMode());
        final long periodInDays = Optional.ofNullable(dataVolumeConfig.getPeriod())
                .map(DataVolumePeriod::getNoOfDays)
                .orElse(0);

        log.trace("message limit config for tenant [{}] are [{}:{}, {}:{}, {}:{}, {}:{}]", tenant.getTenantId(),
                TenantConstants.FIELD_MAX_BYTES, maxBytes,
                TenantConstants.FIELD_EFFECTIVE_SINCE, effectiveSince,
                TenantConstants.FIELD_PERIOD_MODE, periodMode,
                TenantConstants.FIELD_PERIOD_NO_OF_DAYS, periodInDays);

        if (maxBytes == -1 || effectiveSince == null || PeriodMode.UNKNOWN.equals(periodMode) || payloadSize <= 0) {
            result.complete(Boolean.FALSE);
        } else {

            final long allowedMaxBytes = getOrAddToCache(limitsCache,
                    String.format("%s_allowed_max_bytes", tenant.getTenantId()),
                    () -> calculateDataVolume(OffsetDateTime.ofInstant(effectiveSince, ZoneOffset.UTC),
                            OffsetDateTime.now(ZoneOffset.UTC), periodMode, maxBytes));
            final long dataUsagePeriod = getOrAddToCache(limitsCache,
                    String.format("%s_data_usage_period", tenant.getTenantId()),
                    () -> calculateDataUsagePeriod(OffsetDateTime.ofInstant(effectiveSince, ZoneOffset.UTC),
                            OffsetDateTime.now(ZoneOffset.UTC), periodMode, periodInDays));

            items.put("current period bytes limit", allowedMaxBytes);

            if (dataUsagePeriod <= 0) {
                result.complete(Boolean.FALSE);
            } else {

                final String queryParams = String.format(
                        "floor(sum(increase(%s{status=~\"%s|%s\", tenant=\"%s\"} [%sd]) or %s*0) + sum(increase(%s{status=~\"%s|%s\", tenant=\"%s\"} [%sd]) or %s*0))",
                        MESSAGES_PAYLOAD_SIZE_METRIC_NAME,
                        MetricsTags.ProcessingOutcome.FORWARDED.asTag().getValue(),
                        MetricsTags.ProcessingOutcome.UNPROCESSABLE.asTag().getValue(),
                        tenant.getTenantId(),
                        dataUsagePeriod,
                        COMMANDS_PAYLOAD_SIZE_METRIC_NAME,
                        COMMANDS_PAYLOAD_SIZE_METRIC_NAME,
                        MetricsTags.ProcessingOutcome.FORWARDED.asTag().getValue(),
                        MetricsTags.ProcessingOutcome.UNPROCESSABLE.asTag().getValue(),
                        tenant.getTenantId(),
                        dataUsagePeriod,
                        MESSAGES_PAYLOAD_SIZE_METRIC_NAME);
                final String key = String.format("%s_bytes_consumed", tenant.getTenantId());

                Optional.ofNullable(limitsCache)
                        .map(success -> limitsCache.get(key))
                        .map(cachedValue -> Future.succeededFuture((long) cachedValue))
                        .orElseGet(() -> executeQuery(queryParams, span).map(bytesConsumed -> addToCache(limitsCache, key, bytesConsumed)))
                        .map(bytesConsumed -> {
                            items.put("current period bytes consumed", bytesConsumed);
                            final boolean isExceeded = (bytesConsumed + payloadSize) > allowedMaxBytes;
                            log.trace(
                                    "data limit {}exceeded [tenant: {}, bytes consumed: {}, allowed max-bytes: {}, {}: {}, {}: {}, {}: {}]",
                                    isExceeded ? "" : "not ",
                                    tenant.getTenantId(), bytesConsumed, allowedMaxBytes,
                                    TenantConstants.FIELD_EFFECTIVE_SINCE, effectiveSince,
                                    TenantConstants.FIELD_PERIOD_MODE, periodMode,
                                    TenantConstants.FIELD_PERIOD_NO_OF_DAYS, periodInDays);
                            return isExceeded;
                        })
                        .otherwise(failed -> Boolean.FALSE)
                        .setHandler(result);
            }
        }
    }

    private Future<Long> executeQuery(final String query, final Span span) {

        final Promise<Long> result = Promise.promise();
        log.trace("running Prometheus query [URL: http://{}:{}{}, query: {}]",
                config.getHost(), config.getPort(), QUERY_URI, query);
        client.get(config.getPort(), config.getHost(), QUERY_URI)
        .addQueryParam("query", query)
        .expect(ResponsePredicate.SC_OK)
        .as(BodyCodec.jsonObject())
        .send(sendAttempt -> {
            if (sendAttempt.succeeded()) {
                final HttpResponse<JsonObject> response = sendAttempt.result();
                result.complete(extractLongValue(response.body(), span));
            } else {
                final Map<String, Object> items = Map.of(
                        Fields.EVENT, Tags.ERROR.getKey(),
                        Fields.MESSAGE, "failed to run Prometheus query",
                        "URL", String.format("http://%s:%d%s", config.getHost(), config.getPort(), QUERY_URI),
                        "query", query,
                        Fields.ERROR_KIND, "Exception",
                        Fields.ERROR_OBJECT, sendAttempt.cause());
                TracingHelper.logError(span, items);
                log.warn("failed to run Prometheus query [URL: http://{}:{}{}, query: {}]: {}",
                        config.getHost(), config.getPort(), QUERY_URI, query, sendAttempt.cause().getMessage());
                result.fail(sendAttempt.cause());
            }
        });
        return result.future();
    }

    /**
     * Extracts a long value from the JSON result returned by the Prometheus
     * server.
     * <p>
     * The result is expected to have the following structure:
     * <pre>
     * {
     *   "status": "success",
     *   "data": {
     *     "result": [
     *       {
     *         "value": [ $timestamp, "$value" ]
     *       }
     *     ]
     *   }
     * }
     * </pre>
     * 
     * @param response The response object.
     * @return The extracted value.
     * @see <a href="https://prometheus.io/docs/prometheus/latest/querying/api/">Prometheus HTTP API</a>
     */
    private Long extractLongValue(final JsonObject response, final Span span) {

        Objects.requireNonNull(response);

        try {
            final String status = response.getString("status");
            if ("error".equals(status)) {
                TracingHelper.logError(span, Map.of(Fields.MESSAGE, "error executing query",
                        "status", status,
                        "error-type", response.getString("errorType"),
                        "error", response.getString("error")));
                log.debug("error executing query [status: {}, error type: {}, error: {}]",
                        status, response.getString("errorType"), response.getString("error"));
                return 0L;
            } else {
                // success
                final JsonObject data = response.getJsonObject("data", new JsonObject());
                final JsonArray result = data.getJsonArray("result");
                if (result != null) {
                    if (result.size() == 0) {
                        // no metrics available yet
                        span.log("no metrics available (yet)");
                        return 0L;
                    } else if (result.size() == 1 && result.getJsonObject(0) != null) {
                        final JsonArray valueArray = result.getJsonObject(0).getJsonArray("value");
                        if (valueArray != null && valueArray.size() == 2) {
                            final String value = valueArray.getString(1);
                            if (value != null && !value.isEmpty()) {
                                return Long.parseLong(value);
                            }
                        }
                    }
                }
                final String jsonResponse = response.encodePrettily();
                TracingHelper.logError(span, Map.of(Fields.MESSAGE, "server returned malformed response",
                        "response", jsonResponse));
                log.debug("server returned malformed response: {}", jsonResponse);
            }
        } catch (Exception e) {
            final String jsonResponse = response.encodePrettily();
            TracingHelper.logError(span, Map.of(Fields.MESSAGE, "server returned malformed response",
                    "response", jsonResponse));
            log.debug("server returned malformed response: {}", jsonResponse);
        }
        return 0L;
    }

    /**
     * Calculates the data volume (in bytes) that is allowed for a tenant for 
     * the current period based on the mode defined by 
     * {@link TenantConstants#FIELD_PERIOD_MODE}.
     * <p>
     * In <em>monthly</em> mode, if the effectiveSince date doesn't fall on the 
     * first day of the month then the allowed data volume for the tenant is 
     * calculated as below. For rest of the months and the <em>days</em> mode,
     * the maxBytes defined by {@link TenantConstants#FIELD_MAX_BYTES} is used directly.
     * <pre>
     *             maxBytes 
     *   ---------------------------------- x No. of days from effectiveSince till lastDay of the targetDateMonth.
     *    No. of days in the current month
     * </pre>
     * <p>
     * 
     * @param effectiveSince The point of time on which the data volume limit 
     *                       came into effect.
     * @param targetDateTime The target point of time used for the data usage period 
     *                       calculation.
     * @param mode The mode of the period. 
     * @param maxBytes The maximum allowed bytes defined in configuration by 
     *                 {@link TenantConstants#FIELD_MAX_BYTES}. 
     * @return The allowed data-volume in bytes.
     */
    long calculateDataVolume(
            final OffsetDateTime effectiveSince,
            final OffsetDateTime targetDateTime,
            final PeriodMode mode,
            final long maxBytes) {
        if (PeriodMode.MONTHLY.equals(mode)
                && maxBytes > 0
                && !targetDateTime.isBefore(effectiveSince)
                && YearMonth.from(targetDateTime).equals(YearMonth.from(effectiveSince))
                && effectiveSince.getDayOfMonth() != 1) {
            final OffsetDateTime lastDayOfMonth = effectiveSince.with(TemporalAdjusters.lastDayOfMonth());
            final long daysBetween = ChronoUnit.DAYS
                    .between(effectiveSince, lastDayOfMonth) + 1;
            return Double.valueOf(Math.ceil(daysBetween * maxBytes / lastDayOfMonth.getDayOfMonth())).longValue();
        }
        return maxBytes;
    }

    /**
     * Calculates the period for which the data usage is to be retrieved from the
     * prometheus server based on the mode defined by 
     * {@link TenantConstants#FIELD_PERIOD_MODE}.
     *
     * @param effectiveSince The point of time on which the data volume limit came
     *                      into effect.
     * @param targetDateTime The target point of time used for the data usage period 
     *                       calculation.
     * @param mode The mode of the period defined by
     *                   {@link TenantConstants#FIELD_PERIOD_MODE}.
     * @param periodInDays The number of days defined by 
     *                     {@link TenantConstants#FIELD_PERIOD_NO_OF_DAYS}. 
     * @return The period in days for which the data usage is to be calculated.
     */
    long calculateDataUsagePeriod(
            final OffsetDateTime effectiveSince,
            final OffsetDateTime targetDateTime,
            final PeriodMode mode,
            final long periodInDays) {
        final long inclusiveDaysBetween = ChronoUnit.DAYS.between(effectiveSince, targetDateTime) + 1;
        switch (mode) {
        case DAYS:
            if (inclusiveDaysBetween > 0 && periodInDays > 0) {
                final long dataUsagePeriodInDays = inclusiveDaysBetween % periodInDays;
                return dataUsagePeriodInDays == 0 ? periodInDays : dataUsagePeriodInDays;
            }
            return 0L;
        case MONTHLY:
            if (YearMonth.from(targetDateTime).equals(YearMonth.from(effectiveSince))
                    && effectiveSince.getDayOfMonth() != 1) {
                return inclusiveDaysBetween;
            }
            return targetDateTime.getDayOfMonth();
        default:
            return 0L;
        }
    }

    private long getOrAddToCache(final ExpiringValueCache<Object, Object> cache, final String key,
            final Supplier<Long> valueSupplier) {
        return Optional.ofNullable(cache)
                .map(success -> cache.get(key))
                .map(cachedValue -> (long) cachedValue)
                .orElseGet(() -> addToCache(cache, key, valueSupplier.get()));
    }

    private long addToCache(final ExpiringValueCache<Object, Object> cache, final String key, final long result) {
        Optional.ofNullable(cache)
                .ifPresent(success -> cache.put(key, result,
                        Duration.ofSeconds(config.getCacheTimeout())));
        return result;
    }
}
