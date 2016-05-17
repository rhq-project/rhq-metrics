/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
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

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.hawkular.metrics.datetime.DateTimeService;
import org.hawkular.metrics.scheduler.api.JobDetails;
import org.hawkular.metrics.scheduler.api.SingleExecutionTrigger;
import org.hawkular.metrics.scheduler.api.Trigger;
import org.jboss.logging.Logger;
import org.joda.time.DateTime;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.datastax.driver.core.PreparedStatement;
import com.google.common.collect.ImmutableMap;

import rx.Observable;
import rx.Subscriber;
import rx.schedulers.Schedulers;
import rx.schedulers.TestScheduler;
import rx.subjects.PublishSubject;

/**
 * @author jsanda
 */
public class JobExecutionTest extends JobSchedulerTest {

    private static Logger logger = Logger.getLogger(JobExecutionTest.class);

    private TestScheduler tickScheduler;

    private PublishSubject<Date> finishedTimeSlices;
    private Date finishedTimeSlice;
    private CountDownLatch timeSliceFinished;

    private PreparedStatement insertJob;
    private PreparedStatement updateJobQueue;

    @BeforeClass
    public void initClass() {
        tickScheduler = Schedulers.test();
        tickScheduler.advanceTimeTo(DateTimeService.currentMinute().getMillis(), TimeUnit.MILLISECONDS);

        finishedTimeSlices = PublishSubject.create();

        insertJob = session.prepare("INSERT INTO jobs (id, type, name, params, trigger) VALUES (?, ?, ?, ?, ?)");
        updateJobQueue = session.prepare("INSERT INTO jobs_status (time_slice, job_id) VALUES (?, ?)");

        setActiveQueue(new DateTime(tickScheduler.now()));

        jobScheduler.setTickScheduler(tickScheduler);
        jobScheduler.setTimeSlicesSubject(finishedTimeSlices);
        finishedTimeSlices.subscribe(timeSlice -> {
            logger.debug("Finished time slice [" + timeSlice + "]");

            if (finishedTimeSlice != null && timeSlice.getTime() >= finishedTimeSlice.getTime()) {
                logger.debug("Count down");
                timeSliceFinished.countDown();
            }
        });
        jobScheduler.start();
    }

    @BeforeMethod
    public void initTest(Method method) {
        timeSliceFinished = new CountDownLatch(1);
        logger.debug("Starting [" + method.getName() + "]");
    }

    /**
     * This test runs the scheduler when no jobs are scheduled. The test verifies that the active-queue setting is
     * updated to the next time slice.
     */
    @Test
    public void advanceClockWhenNoJobsAreScheduled() throws Exception {
        DateTime timeSlice = new DateTime(tickScheduler.now()).plusMinutes(1);
        finishedTimeSlice = timeSlice.toDate();

        setActiveQueue(timeSlice);
        tickScheduler.advanceTimeTo(timeSlice.getMillis(), TimeUnit.MILLISECONDS);

        waitForTimeSliceToFinish(timeSlice, 5, TimeUnit.SECONDS);

        assertEquals(getActiveQueue(), timeSlice.plusMinutes(1));

        assertEquals(getScheduledJobs(timeSlice), emptySet());
        assertEquals(getFinishedJobs(timeSlice), emptySet());
    }

    @Test(dependsOnMethods = "advanceClockWhenNoJobsAreScheduled")
    public void executeSingleExecutionJob() throws Exception {
        DateTime timeSlice = new DateTime(tickScheduler.now()).plusMinutes(1);
        finishedTimeSlice = timeSlice.toDate();

        Trigger trigger = new SingleExecutionTrigger.Builder().withTriggerTime(timeSlice.getMillis()).build();
        JobDetails jobDetails = new JobDetails(UUID.randomUUID(), "Test Type", "Test Job 1", emptyMap(), trigger);
        AtomicInteger executionCountRef = new AtomicInteger();

        jobScheduler.registerJobCreator(jobDetails.getJobType(), details -> Observable.create(subscriber -> {
            logger.debug("Executing " + details);
            executionCountRef.incrementAndGet();
            subscriber.onCompleted();
        }));

        session.execute(insertJob.bind(jobDetails.getJobId(), jobDetails.getJobType(), jobDetails.getJobName(),
                jobDetails.getParameters(), SchedulerImpl.getTriggerValue(rxSession, trigger)));
        session.execute(updateJobQueue.bind(timeSlice.toDate(), jobDetails.getJobId()));

        logger.debug("Job scheduled to execute at " + new Date(trigger.getTriggerTime()));

        setActiveQueue(timeSlice);

        tickScheduler.advanceTimeTo(timeSlice.getMillis(), TimeUnit.MILLISECONDS);

        waitForTimeSliceToFinish(timeSlice, 5, TimeUnit.SECONDS);

        assertEquals(executionCountRef.get(), 1, jobDetails + " should have been executed once");
        logger.info("Verify active queue");
        assertEquals(getActiveQueue(), timeSlice.plusMinutes(1));

        assertEquals(getScheduledJobs(timeSlice), emptySet());
        assertEquals(getFinishedJobs(timeSlice), emptySet());
    }

    @Test(dependsOnMethods = "executeSingleExecutionJob")
    public void executeMultipleSingleExecutionJobs() throws Exception {
        DateTime timeSlice = new DateTime(tickScheduler.now()).plusMinutes(1);
        finishedTimeSlice = timeSlice.toDate();

        Trigger trigger = new SingleExecutionTrigger.Builder().withTriggerTime(timeSlice.getMillis()).build();
        String jobType = "Test Type";
        Map<String, Integer> executionCounts = new HashMap<>();
        List<JobDetails> jobDetailsList = new ArrayList<>();

        logger.debug("Scheduling jobs for time slice [" + timeSlice.toLocalDateTime() + "]");

        for (int i = 0; i < 3; ++i) {
            JobDetails details = new JobDetails(UUID.randomUUID(), jobType, "Test Job " + i, emptyMap(), trigger);
            jobDetailsList.add(details);
            executionCounts.put(details.getJobName(), 0);

            session.execute(insertJob.bind(details.getJobId(), details.getJobType(), details.getJobName(),
                    details.getParameters(), SchedulerImpl.getTriggerValue(rxSession, trigger)));
            session.execute(updateJobQueue.bind(timeSlice.toDate(), details.getJobId()));
        }

        jobScheduler.registerJobCreator(jobType, details -> Observable.create(subscriber -> {
            logger.debug("Executing " + details);
            Integer count = executionCounts.get(details.getJobName());
            executionCounts.put(details.getJobName(), ++count);
            logger.debug("Execution Counts = " + executionCounts);
            subscriber.onCompleted();
        }));

        setActiveQueue(timeSlice);

        tickScheduler.advanceTimeTo(timeSlice.getMillis(), TimeUnit.MILLISECONDS);

        waitForTimeSliceToFinish(timeSlice, 10, TimeUnit.SECONDS);
        logger.debug("TIME SLICE DONE");

        logger.debug("Execution Counts = " + executionCounts);

        assertEquals(executionCounts, ImmutableMap.of("Test Job 0", 1, "Test Job 1", 1, "Test Job 2", 1));
        assertEquals(getActiveQueue(), timeSlice.plusMinutes(1));
        assertEquals(getScheduledJobs(timeSlice), emptySet());
        assertEquals(getFinishedJobs(timeSlice), emptySet());
    }

    @Test(dependsOnMethods = "executeMultipleSingleExecutionJobs")
    public void executeLongRunningSingleExecutionJob() throws Exception {
        DateTime timeSlice = new DateTime(tickScheduler.now()).plusMinutes(2);
        finishedTimeSlice = timeSlice.plusMinutes(1).toDate();

        JobDetails job1 = new JobDetails(UUID.randomUUID(), "Long Test Job", "Long Test Job", emptyMap(),
                new SingleExecutionTrigger.Builder().withTriggerTime(timeSlice.getMillis()).build());
        CountDownLatch job1Finished = new CountDownLatch(1);
        CountDownLatch firstTimeSliceFinished = new CountDownLatch(1);

        jobScheduler.registerJobCreator(job1.getJobType(), details -> Observable.create(subscriber -> {
            try {
                logger.debug("First time slice finished!");
                firstTimeSliceFinished.countDown();
                job1Finished.await();
            } catch (InterruptedException e) {
                subscriber.onError(e);
            }
            subscriber.onCompleted();
        }));

        JobDetails job2 = new JobDetails(UUID.randomUUID(), "Test Type", "Test Job", emptyMap(),
                new SingleExecutionTrigger.Builder().withTriggerTime(timeSlice.plusMinutes(1).getMillis()).build());

        jobScheduler.registerJobCreator(job2.getJobType(), details -> Observable.create(Subscriber::onCompleted));

        scheduleJob(job1);
        scheduleJob(job2);

        setActiveQueue(timeSlice);

        tickScheduler.advanceTimeTo(job1.getTrigger().getTriggerTime(), TimeUnit.MILLISECONDS);
        firstTimeSliceFinished.await(10, TimeUnit.SECONDS);

        tickScheduler.advanceTimeTo(job2.getTrigger().getTriggerTime(), TimeUnit.MILLISECONDS);

        job1Finished.countDown();

        waitForTimeSliceToFinish(timeSlice.plusMinutes(1), 10, TimeUnit.SECONDS);

        assertEquals(getActiveQueue(), timeSlice.plusMinutes(2));
        assertEquals(getScheduledJobs(timeSlice), emptySet());
        assertEquals(getScheduledJobs(timeSlice.plusMinutes(1)), emptySet());
        assertEquals(getFinishedJobs(timeSlice), emptySet());
        assertEquals(getFinishedJobs(timeSlice.plusMinutes(1)), emptySet());
    }

    private void waitForTimeSliceToFinish(DateTime timeSlice, long timeout, TimeUnit timeUnit) throws Exception {
        assertTrue(timeSliceFinished.await(timeout, timeUnit), "Timed out while waiting for time slice [" +
                timeSlice.toLocalDateTime() + "] to finish. Last finished time slice is [" + finishedTimeSlice + "]");
    }

    private void scheduleJob(JobDetails job) {
        Date timeSlice = new Date(job.getTrigger().getTriggerTime());
        logger.debug("Scheduling " + job + " for execution at " + timeSlice);
        session.execute(insertJob.bind(job.getJobId(), job.getJobType(), job.getJobName(),
                job.getParameters(), SchedulerImpl.getTriggerValue(rxSession, job.getTrigger())));
        session.execute(updateJobQueue.bind(timeSlice, job.getJobId()));
    }

}
