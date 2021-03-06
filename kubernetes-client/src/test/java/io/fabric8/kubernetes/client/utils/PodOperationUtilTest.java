/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.kubernetes.client.utils;

import io.fabric8.kubernetes.api.model.DoneablePod;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.Deletable;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.Gettable;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Watchable;
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.dsl.internal.core.v1.PodOperationsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PodOperationUtilTest {
  private PodOperationsImpl podOperations;
  private OperationContext operationContext;

  @BeforeEach
  void setUp() {
    this.podOperations = Mockito.mock(PodOperationsImpl.class, Mockito.RETURNS_DEEP_STUBS);
    this.operationContext = Mockito.mock(OperationContext.class, Mockito.RETURNS_DEEP_STUBS);
  }

  @Test
  void testGetFilteredPodsForLogs() {
    // Given
    String controllerUid = "controller-uid-ejofjeofer";
    PodList podList = getMockPodList(controllerUid);

    // When
    List<PodResource<Pod, DoneablePod>> podResourceList = PodOperationUtil.getFilteredPodsForLogs(podOperations, podList, controllerUid);

    // Then
    assertNotNull(podResourceList);
    assertEquals(1, podResourceList.size());
    verify(podOperations, times(1)).withName(any());
  }

  @Test
  void testGetGenericPodOperations() {
    // When
    PodOperationsImpl podOperations = PodOperationUtil.getGenericPodOperations(operationContext, false, 5);

    // Then
    assertNotNull(podOperations);
    assertNull(podOperations.getName());
  }

  @Test
  void testWaitUntilReadyBeforeFetchingLogs() throws InterruptedException {
    // Given
    Pod pod = new PodBuilder()
      .withNewMetadata().withName("foo").endMetadata()
      .withNewStatus().withPhase("Pending").endStatus()
      .build();
    Gettable<Pod> gettablePod = () -> pod;
    when(podOperations.fromServer()).thenReturn(gettablePod);
    when(podOperations.waitUntilReady(5, TimeUnit.SECONDS)).thenReturn(pod);

    // When
    PodOperationUtil.waitUntilReadyBeforeFetchingLogs(podOperations, 5);

    // Then
    verify(podOperations, times(1)).waitUntilReady(5, TimeUnit.SECONDS);
  }

  @Test
  void testGetPodOperationsForController() {
    // Given
    String controllerUid = "some-uid";
    Map<String, String> selectorLabels = Collections.singletonMap("foo", "bar");
    FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> filterWatchListDeletable = getMockPodFilterOperation(controllerUid);
    when(podOperations.withLabels(any())).thenReturn(filterWatchListDeletable);

    // When
    List<PodResource<Pod, DoneablePod>> podResources = PodOperationUtil.getPodOperationsForController(podOperations, controllerUid, selectorLabels);

    // Then
    assertNotNull(podResources);
    assertEquals(1, podResources.size());
  }

  private PodList getMockPodList(String controllerUid) {
    return new PodListBuilder()
      .addToItems(
        new PodBuilder()
          .withNewMetadata()
          .withName("pod1")
          .addNewOwnerReference()
          .withController(true)
          .withUid(controllerUid)
          .endOwnerReference()
          .endMetadata()
          .build())
      .addToItems(new PodBuilder().withNewMetadata().withName("pod2").endMetadata().build())
      .build();
  }

  private FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> getMockPodFilterOperation(String controllerUid) {
    return new FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>>() {
      @Override
      public Deletable<Boolean> withGracePeriod(long gracePeriodSeconds) { return null; }

      @Override
      public Boolean delete() { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withLabels(Map<String, String> labels) { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withoutLabels(Map<String, String> labels) { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withLabelIn(String key, String... values) { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withLabelNotIn(String key, String... values) { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withLabel(String key, String value) { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withLabel(String key) { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withoutLabel(String key, String value) { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withoutLabel(String key) { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withFields(Map<String, String> labels) { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withField(String key, String value) { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withoutFields(Map<String, String> fields) { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withoutField(String key, String value) { return null; }

      @Override
      public FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withLabelSelector(LabelSelector selector) { return null; }

      @Override
      public PodList list() { return getMockPodList(controllerUid); }

      @Override
      public PodList list(Integer limitVal, String continueVal) { return null; }

      @Override
      public PodList list(ListOptions listOptions) { return null; }

      @Override
      public Pod updateStatus(Pod item) { return null; }

      @Override
      public Watchable<Watch, Watcher<Pod>> withResourceVersion(String resourceVersion) { return null; }

      @Override
      public Watch watch(Watcher<Pod> watcher) { return null; }

      @Override
      public Watch watch(ListOptions options, Watcher<Pod> watcher) { return null; }

      @Override
      public Watch watch(String resourceVersion, Watcher<Pod> watcher) { return null; }
    };
  }
}
