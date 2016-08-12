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
package org.hawkular.metrics.component.publish;

import java.util.HashSet;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;

import org.hawkular.metrics.api.jaxrs.util.Eager;
import org.hawkular.metrics.model.MetricId;

/**
 * A table to store a cache of published metrics on the bus under hawkular-services context.
 * This is a PoC and this cache is only valid for single node sceanarios.
 * Clustered scenarios requires to transform the local HashMap into a ISPN cache.
 *
 * @author Lucas Ponce
 */
@ApplicationScoped
@Eager
public class PublishCommandTable {

    private HashSet<MetricId> published = new HashSet<>();

    public boolean isPublished(MetricId id) {
        return published.contains(id);
    }

    public synchronized void add(List<MetricId> ids) {
        if (ids != null) {
            published.addAll(ids);
        }
    }

    public synchronized void remove(List<MetricId> ids) {
        if (ids != null) {
            ids.removeAll(ids);
        }
    }
}
