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

import static org.hawkular.metrics.core.api.MetricType.AVAILABILITY;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.List;

import org.hawkular.metrics.core.api.fasterxml.jackson.AvailabilityTypeSerializer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonCreator.Mode;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.Lists;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import rx.Observable;

/**
 * @author John Sanda
 */
@ApiModel(description = "Consists of a timestamp and a textual value indicating a system availability")
public class AvailabilityDataPoint extends DataPoint<AvailabilityType> {

    @JsonCreator(mode = Mode.PROPERTIES)
    @org.codehaus.jackson.annotate.JsonCreator
    public AvailabilityDataPoint(
            @JsonProperty("timestamp")
            @org.codehaus.jackson.annotate.JsonProperty("timestamp")
            Long timestamp,
            @JsonProperty("value")
            @org.codehaus.jackson.annotate.JsonProperty("value")
            String value
    ) {
        super(timestamp, AvailabilityType.fromString(value));
        checkArgument(timestamp != null, "Data point timestamp is null");
        checkArgument(value != null, "Data point value is null");
    }

    public AvailabilityDataPoint(DataPoint<AvailabilityType> other) {
        super(other.getTimestamp(), other.getValue());
    }

    @ApiModelProperty(required = true)
    public long getTimestamp() {
        return timestamp;
    }

    @ApiModelProperty(required = true, dataType = "string", allowableValues = "up,down,unknown")
    @JsonSerialize(using = AvailabilityTypeSerializer.class)
    @org.codehaus.jackson.map.annotate.JsonSerialize(
            using = org.hawkular.metrics.core.api.codehause.jackson.AvailabilityTypeSerializer.class
    )
    public AvailabilityType getValue() {
        return value;
    }

    public static List<DataPoint<AvailabilityType>> asDataPoints(List<AvailabilityDataPoint> points) {
        return Lists.transform(points, p -> new DataPoint<>(p.getTimestamp(), p.getValue()));
    }

    public static Observable<Metric<AvailabilityType>> toObservable(String tenantId, String
            metricId, List<AvailabilityDataPoint> points) {
        List<DataPoint<AvailabilityType>> dataPoints = asDataPoints(points);
        Metric<AvailabilityType> metric = new Metric<>(new MetricId<>(tenantId, AVAILABILITY, metricId), dataPoints);
        return Observable.just(metric);
    }
}