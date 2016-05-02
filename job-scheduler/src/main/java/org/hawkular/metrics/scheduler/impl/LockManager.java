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
package org.hawkular.metrics.scheduler.impl;

import static java.util.Collections.singletonList;

import org.hawkular.rx.cassandra.driver.RxSession;

import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;

import rx.Observable;

/**
 * @author jsanda
 */
public class LockManager {

    private RxSession session;

    private PreparedStatement updateExclusiveLock;

    private PreparedStatement updateSharedLock;

    private PreparedStatement releaseSharedLock;

    public LockManager(RxSession session) {
        this.session = session;
        updateExclusiveLock = session.getSession().prepare(
                "UPDATE locks SET owner = ? WHERE name = ? IF owners = NULL");
        updateSharedLock = session.getSession().prepare(
                "UPDATE locks USING TTL ? SET owners = owners + ? WHERE name = ? IF owner = NULL");
        releaseSharedLock = session.getSession().prepare(
                "UPDATE locks SET owners = owners - ? WHERE name = ? IF owner = NULL");
    }

    public Observable<Boolean> acquireExclusiveShared(String name, String value) {
        return session.execute(updateExclusiveLock.bind(value, name)).map(ResultSet::wasApplied);
    }

    public Observable<Boolean> acquireSharedLock(String name, String value, int timeout) {
        return session.execute(updateSharedLock.bind(timeout, singletonList(value), name)).map(ResultSet::wasApplied);
    }

    public Observable<Boolean> releaseSharedLock(String name, String value) {
        return session.execute(releaseSharedLock.bind(singletonList(value), name)).map(ResultSet::wasApplied);
    }

}
