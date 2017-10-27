/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.netflix.titus.master.loadbalancer.service;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.netflix.titus.api.connector.cloud.LoadBalancerClient;
import io.netflix.titus.api.jobmanager.model.job.Job;
import io.netflix.titus.api.jobmanager.model.job.TaskState;
import io.netflix.titus.api.jobmanager.service.V3JobOperations;
import io.netflix.titus.api.loadbalancer.model.JobLoadBalancer;
import io.netflix.titus.api.loadbalancer.model.LoadBalancerTarget;
import io.netflix.titus.api.loadbalancer.store.LoadBalancerStore;
import io.netflix.titus.common.util.CollectionsExt;
import io.netflix.titus.runtime.store.v3.memory.InMemoryLoadBalancerStore;
import org.junit.Before;
import org.junit.Test;
import rx.Completable;
import rx.observers.AssertableSubscriber;
import rx.schedulers.Schedulers;
import rx.schedulers.TestScheduler;

import static io.netflix.titus.master.loadbalancer.service.LoadBalancerTests.count;
import static io.netflix.titus.master.loadbalancer.service.LoadBalancerTests.mockConfiguration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DefaultLoadBalancerServiceTest {

    private LoadBalancerClient client;
    private V3JobOperations jobOperations;
    private LoadBalancerStore store;
    private TestScheduler testScheduler;

    @Before
    public void setUp() throws Exception {
        client = mock(LoadBalancerClient.class);
        store = new InMemoryLoadBalancerStore();
        jobOperations = mock(V3JobOperations.class);
        testScheduler = Schedulers.test();
    }

    @Test
    public void addLoadBalancerRegistersTasks() throws Exception {
        final String jobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();

        when(client.registerAll(any())).thenReturn(Completable.complete());
        when(client.deregisterAll(any())).thenReturn(Completable.complete());
        when(jobOperations.getJob(jobId)).thenReturn(Optional.of(Job.newBuilder().build()));
        when(jobOperations.getTasks(jobId)).thenReturn(CollectionsExt.merge(
                LoadBalancerTests.buildTasksStarted(5, jobId),
                LoadBalancerTests.buildTasks(2, jobId, TaskState.StartInitiated),
                LoadBalancerTests.buildTasks(2, jobId, TaskState.KillInitiated),
                LoadBalancerTests.buildTasks(3, jobId, TaskState.Finished),
                LoadBalancerTests.buildTasks(1, jobId, TaskState.Disconnected)
        ));

        LoadBalancerConfiguration configuration = mockConfiguration(5, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(configuration, client, store, jobOperations, testScheduler);

        final AssertableSubscriber<DefaultLoadBalancerService.Batch> testSubscriber = service.buildStream().test();

        assertTrue(service.addLoadBalancer(jobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(store.retrieveLoadBalancersForJob(jobId).toBlocking().first()).isEqualTo(loadBalancerId);
        verify(jobOperations).getTasks(jobId);

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client).registerAll(argThat(targets -> targets != null && targets.size() == 5));
        verify(client).deregisterAll(argThat(CollectionsExt::isNullOrEmpty));
    }

    @Test
    public void targetsAreBufferedUpToATimeout() throws Exception {
        final String jobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();

        when(client.registerAll(any())).thenReturn(Completable.complete());
        when(client.deregisterAll(any())).thenReturn(Completable.complete());
        when(jobOperations.getJob(jobId)).thenReturn(Optional.of(Job.newBuilder().build()));
        when(jobOperations.getTasks(jobId)).thenReturn(CollectionsExt.merge(
                LoadBalancerTests.buildTasksStarted(3, jobId),
                LoadBalancerTests.buildTasks(1, jobId, TaskState.StartInitiated),
                LoadBalancerTests.buildTasks(1, jobId, TaskState.KillInitiated),
                LoadBalancerTests.buildTasks(1, jobId, TaskState.Finished),
                LoadBalancerTests.buildTasks(1, jobId, TaskState.Disconnected)
        ));

        LoadBalancerConfiguration configuration = mockConfiguration(1000, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(configuration,
                client, store, jobOperations, testScheduler);

        final AssertableSubscriber<DefaultLoadBalancerService.Batch> testSubscriber = service.buildStream().test();
        assertTrue(service.addLoadBalancer(jobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(store.retrieveLoadBalancersForJob(jobId).toBlocking().first()).isEqualTo(loadBalancerId);
        verify(jobOperations).getTasks(jobId);

        testSubscriber.assertNoErrors().assertValueCount(0);
        verify(client, never()).registerAll(any());
        verify(client, never()).deregisterAll(any());

        testScheduler.advanceTimeBy(5_001, TimeUnit.MILLISECONDS);

        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client).registerAll(argThat(targets -> targets != null && targets.size() == 3));
        verify(client).deregisterAll(argThat(CollectionsExt::isNullOrEmpty));
    }

    @Test
    public void addSkipLoadBalancerOperationsOnErrors() throws Exception {
        final String firstJobId = UUID.randomUUID().toString();
        final String secondJobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();

        when(client.registerAll(any())).thenReturn(Completable.complete());
        when(client.deregisterAll(any())).thenReturn(Completable.complete());
        // first fails, second succeeds
        when(jobOperations.getJob(firstJobId)).thenThrow(RuntimeException.class);
        when(jobOperations.getJob(secondJobId)).thenReturn(Optional.of(Job.newBuilder().build()));
        when(jobOperations.getTasks(secondJobId)).thenReturn(LoadBalancerTests.buildTasksStarted(2, secondJobId));

        LoadBalancerConfiguration configuration = mockConfiguration(2, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(configuration, client, store, jobOperations, testScheduler);

        final AssertableSubscriber<DefaultLoadBalancerService.Batch> testSubscriber = service.buildStream().test();

        // first fails and gets skipped after being saved, so convergence can pick it up later
        assertTrue(service.addLoadBalancer(firstJobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(store.retrieveLoadBalancersForJob(firstJobId).toBlocking().first()).isEqualTo(loadBalancerId);

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertNoValues();
        verify(jobOperations, never()).getTasks(firstJobId);
        verify(client, never()).registerAll(any());
        verify(client, never()).deregisterAll(any());

        // second succeeds
        assertTrue(service.addLoadBalancer(secondJobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(store.retrieveLoadBalancersForJob(secondJobId).toBlocking().first()).isEqualTo(loadBalancerId);

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(jobOperations).getTasks(secondJobId);
        verify(client).registerAll(argThat(targets -> targets != null && targets.size() == 2));
        verify(client).deregisterAll(argThat(CollectionsExt::isNullOrEmpty));
    }

    // TODO: test batch sizes
    // TODO: test batch retries

    @Test
    public void removeLoadBalancerDeregisterKnownTargets() throws Exception {
        final String jobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();
        final JobLoadBalancer jobLoadBalancer = new JobLoadBalancer(jobId, loadBalancerId);

        when(client.registerAll(any())).thenReturn(Completable.complete());
        when(client.deregisterAll(any())).thenReturn(Completable.complete());
        assertTrue(store.addLoadBalancer(jobLoadBalancer).await(100, TimeUnit.MILLISECONDS));
        assertTrue(store.updateTargets(Arrays.asList(
                new LoadBalancerTarget(jobLoadBalancer, "Task-1", "1.1.1.1", LoadBalancerTarget.State.Registered),
                new LoadBalancerTarget(jobLoadBalancer, "Task-2", "2.2.2.2", LoadBalancerTarget.State.Registered),
                // should keep retrying targets that already have been marked to be deregistered
                new LoadBalancerTarget(jobLoadBalancer, "Task-3", "3.3.3.3", LoadBalancerTarget.State.Deregistered),
                new LoadBalancerTarget(jobLoadBalancer, "Task-4", "4.4.4.4", LoadBalancerTarget.State.Deregistered)
        )).await(100, TimeUnit.MILLISECONDS));
        assertEquals(count(store.retrieveTargets(jobLoadBalancer)), 4);

        LoadBalancerConfiguration configuration = mockConfiguration(4, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(configuration, client, store, jobOperations, testScheduler);

        final AssertableSubscriber<DefaultLoadBalancerService.Batch> testSubscriber = service.buildStream().test();

        assertTrue(service.removeLoadBalancer(jobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertFalse(store.retrieveLoadBalancersForJob(jobId).toBlocking().getIterator().hasNext());

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client).deregisterAll(argThat(targets -> targets != null && targets.size() == 4));
        verify(client).registerAll(argThat(CollectionsExt::isNullOrEmpty));

        // all successfully deregistered are gone
        assertEquals(count(store.retrieveTargets(jobLoadBalancer)), 0);
    }

    @Test
    public void removeSkipLoadBalancerOperationsOnErrors() throws Exception {
        final String firstJobId = UUID.randomUUID().toString();
        final String secondJobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();
        final JobLoadBalancer firstLoadBalancer = new JobLoadBalancer(firstJobId, loadBalancerId);
        final JobLoadBalancer secondLoadBalancer = new JobLoadBalancer(secondJobId, loadBalancerId);

        store = spy(store);
        when(store.retrieveTargets(new JobLoadBalancer(firstJobId, loadBalancerId))).thenThrow(RuntimeException.class);
        when(client.registerAll(any())).thenReturn(Completable.complete());
        when(client.deregisterAll(any())).thenReturn(Completable.complete());
        assertTrue(store.addLoadBalancer(firstLoadBalancer).await(100, TimeUnit.MILLISECONDS));
        assertTrue(store.addLoadBalancer(secondLoadBalancer).await(100, TimeUnit.MILLISECONDS));
        assertTrue(store.updateTargets(Arrays.asList(
                new LoadBalancerTarget(secondLoadBalancer, "Task-1", "1.1.1.1", LoadBalancerTarget.State.Registered),
                new LoadBalancerTarget(secondLoadBalancer, "Task-2", "2.2.2.2", LoadBalancerTarget.State.Registered)
        )).await(100, TimeUnit.MILLISECONDS));
        assertEquals(count(store.retrieveTargets(secondLoadBalancer)), 2);

        LoadBalancerConfiguration configuration = mockConfiguration(2, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(configuration, client, store, jobOperations, testScheduler);

        final AssertableSubscriber<DefaultLoadBalancerService.Batch> testSubscriber = service.buildStream().test();

        // first fails
        assertTrue(service.removeLoadBalancer(firstJobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertFalse(store.retrieveLoadBalancersForJob(firstJobId).toBlocking().getIterator().hasNext());

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertValueCount(0);
        verify(client, never()).deregisterAll(any());
        verify(client, never()).registerAll(any());
        assertEquals(count(store.retrieveTargets(secondLoadBalancer)), 2);

        // second succeeds
        assertTrue(service.removeLoadBalancer(secondJobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertFalse(store.retrieveLoadBalancersForJob(secondJobId).toBlocking().getIterator().hasNext());

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client).deregisterAll(argThat(targets -> targets != null && targets.size() == 2));
        verify(client).registerAll(argThat(CollectionsExt::isNullOrEmpty));
        // all successfully deregistered are gone
        assertEquals(count(store.retrieveTargets(secondLoadBalancer)), 0);
    }
}