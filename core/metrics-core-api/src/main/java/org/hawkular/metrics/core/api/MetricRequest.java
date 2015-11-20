/*
 * Copyright 2014-2015 Red Hat, Inc. and/or its affiliates
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
package org.hawkular.metrics.core.api;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonCreator.Mode;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize.Inclusion;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import rx.Observable;

/**
 * A container for gauge data points to be persisted. Note that the tenant id is not included because it is obtained
 * from the tenant header in the HTTP request.
 *
 * @author John Sanda
 */
@ApiModel(description = "A gauge metric with one or more data points")
public class MetricRequest<S, T extends DataPoint<S>> {
    private final String id;
    private final List<DataPoint<S>> data;

    @JsonCreator(mode = Mode.PROPERTIES)
    @org.codehaus.jackson.annotate.JsonCreator
    public MetricRequest(
            @JsonProperty("id")
            @org.codehaus.jackson.annotate.JsonProperty("id")
            String id,
            @JsonProperty("data")
            @org.codehaus.jackson.annotate.JsonProperty("data")
            List<T> data
    ) {
        checkArgument(id != null, "Gauge id is null");
        this.id = id;
        this.data = data == null || data.isEmpty() ? emptyList() : unmodifiableList(data);
    }

    @ApiModelProperty(value = "Identifier of the metric", required = true)
    public String getId() {
        return id;
    }

    @ApiModelProperty("Gauge data points")
    @JsonSerialize(include = Inclusion.NON_EMPTY)
    @org.codehaus.jackson.map.annotate.JsonSerialize(
            include = org.codehaus.jackson.map.annotate.JsonSerialize.Inclusion.NON_EMPTY
    )
    public List<DataPoint<S>> getData() {
        return data;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MetricRequest gauge = (MetricRequest) o;
        return id.equals(gauge.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return com.google.common.base.Objects.toStringHelper(this)
                .add("id", id)
                .add("data", data)
                .omitNullValues()
                .toString();
    }

    public static <S, U extends DataPoint<S>, T extends MetricRequest<S, U>> Observable<Metric<S>> toObservable(
            String tenantId, List<T> gauges, MetricType<S> type) {
        return Observable.from(gauges).map(g -> {
            return new Metric<S>(new MetricId<S>(tenantId, type, g.getId()), g.getData());
        });
    }
}