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
package io.fabric8.kubernetes.client.dsl.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.builder.VisitableBuilder;
import io.fabric8.kubernetes.api.builder.Visitor;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.Handlers;
import io.fabric8.kubernetes.client.HasMetadataVisitiableBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import io.fabric8.kubernetes.client.ResourceHandler;
import io.fabric8.kubernetes.client.dsl.*;
import io.fabric8.kubernetes.client.dsl.base.OperationSupport;
import io.fabric8.kubernetes.client.handlers.KubernetesListHandler;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.fabric8.kubernetes.client.utils.KubernetesResourceUtil;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.kubernetes.client.utils.Utils;

import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl extends OperationSupport implements ParameterNamespaceListVisitFromServerGetDeleteRecreateWaitApplicable<HasMetadata, Boolean>,
Waitable<List<HasMetadata>, HasMetadata>, Readiable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl.class);
    protected static final String EXPRESSION = "expression";
    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String fallbackNamespace;
    private final String explicitNamespace;

    private final Boolean fromServer;
    private final Boolean deletingExisting;
    private final List<Visitor> visitors;
    private final long watchRetryInitialBackoffMillis;
    private final double watchRetryBackoffMultiplier;
    private final Object item;
    private final InputStream inputStream;

    private final long gracePeriodSeconds;
    private final DeletionPropagation propagationPolicy;
    private final Boolean cascading;

  @Override
  public List<HasMetadata> waitUntilReady(final long amount, final TimeUnit timeUnit) throws InterruptedException {
    List<HasMetadata> items = acceptVisitors(asHasMetadata(item, true), visitors);
    if (items.isEmpty()) {
      return Collections.emptyList();
    }

    final List<HasMetadata> result = new ArrayList<>();
    final List<HasMetadata> itemsWithConditionNotMatched = new ArrayList<>(items);
    final int size = items.size();
    final ExecutorService executor = Executors.newFixedThreadPool(size);

    try {
      final CountDownLatch latch = new CountDownLatch(size);
      for (final HasMetadata meta : items) {
        final ResourceHandler<HasMetadata, HasMetadataVisitiableBuilder> h = handlerOf(meta);
        if (!executor.isShutdown()) {
          executor.submit(() -> {
            try {
              result.add(h.waitUntilReady(client, config, meta.getMetadata().getNamespace(), meta, amount, timeUnit));
            } catch (InterruptedException | IllegalArgumentException interruptedException) {
              // We may get here if waiting is interrupted or resource doesn't support concept of readiness.
              // We don't want to wait for items that will never become ready
              // Skip that resource then.
              LOGGER.info("{} {} does not support readiness. skipping..", meta.getKind(), meta.getMetadata().getName());
              latch.countDown();
            } catch (IllegalStateException t) {
              logAsNotReady(t, meta);
            } finally {
              // Resource got ready and was returned properly
              latch.countDown();
            }
          });
        }
      }
      latch.await(amount, timeUnit);
      if (latch.getCount() == 0) {
        return result;
      } else {
        throw new KubernetesClientTimeoutException(itemsWithConditionNotMatched, amount, timeUnit);
      }
    } finally {
      executor.shutdown();
    }
  }

  @Override
  public List<HasMetadata> waitUntilCondition(Predicate<HasMetadata> condition, long amount,
    TimeUnit timeUnit) throws InterruptedException {
    List<HasMetadata> items = acceptVisitors(asHasMetadata(item, true), visitors);
    if (items.size() == 0) {
      return Collections.emptyList();
    }

    final List<HasMetadata> result = new ArrayList<>();
    final List<HasMetadata> itemsWithConditionNotMatched = new ArrayList<>(items);
    final int size = items.size();
    final AtomicInteger conditionMatched = new AtomicInteger(0);
    final ExecutorService executor = Executors.newFixedThreadPool(size);

    try {
      final CountDownLatch latch = new CountDownLatch(size);
      for (final HasMetadata meta : items) {
        final ResourceHandler<HasMetadata, HasMetadataVisitiableBuilder> h = handlerOf(meta);
        if (!executor.isShutdown()) {
          executor.submit(() -> {
            try {
              result.add(h.waitUntilCondition(client, config, meta.getMetadata().getNamespace(), meta, condition, amount, timeUnit));
              conditionMatched.incrementAndGet();
              itemsWithConditionNotMatched.remove(meta);
            } catch (Throwable t) {
              //consider all errors as not ready.
              logAsNotReady(t, meta);
            } finally {
              //We don't want to wait for items that will never become ready
              latch.countDown();
            }
          });
        }
      }
      if (checkConditionMetForAll(latch, size, conditionMatched, amount, timeUnit)) {
        return result;
      } else {
        throw new KubernetesClientTimeoutException(itemsWithConditionNotMatched, amount, timeUnit);
      }
    } finally {
      executor.shutdown();
    }

  }

  @Override
  public List<HasMetadata> waitUntilCondition(Predicate<HasMetadata> condition,
                                              String resourceVersion,
                                              Duration timeout) throws InterruptedException {
    List<HasMetadata> items = acceptVisitors(asHasMetadata(item, true), visitors);
    if (items.isEmpty()) {
      return Collections.emptyList();
    }

    final List<CompletableFuture<HasMetadata>> futures = new ArrayList<>(items.size());
    final ExecutorService executor = Executors.newFixedThreadPool(items.size());

    try {
      for (final HasMetadata meta : items) {
        final ResourceHandler<HasMetadata, HasMetadataVisitiableBuilder> h = handlerOf(meta);
        futures.add(CompletableFuture.supplyAsync(() -> {
          try {
            return h.waitUntilCondition(client, config, meta.getMetadata().getNamespace(), meta, condition, resourceVersion, timeout);
          } catch (InterruptedException e) {
            // Don't forget that this thread was interrupted.
            Thread.currentThread().interrupt();

            //consider all errors as not ready.
            logAsNotReady(e, meta);
            return null;
          } catch (Exception e) {
            //consider all errors as not ready.
            logAsNotReady(e, meta);
            return null;
          }
        }, executor));
      }

      // Wait for all futures to complete, remembering that every future
      // has been given the same timeout value.
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } finally {
      executor.shutdown();
    }

    final List<HasMetadata> results = new ArrayList<>();
    final List<HasMetadata> itemsWithConditionNotMatched = new ArrayList<>();

    // Iterate over the items because we don't know what kind of List it is.
    // But the futures use an ArrayList, so accessing by index is efficient.
    int i = 0;
    for (final HasMetadata meta : items) {
      try {
        HasMetadata result = futures.get(i).get();
        if (result != null) {
          results.add(result);
        } else {
          itemsWithConditionNotMatched.add(meta);
        }
      } catch (ExecutionException e) {
        itemsWithConditionNotMatched.add(meta);
        logAsNotReady(e.getCause(), meta);
      }
      ++i;
    }

    if (!itemsWithConditionNotMatched.isEmpty()) {
      throw new KubernetesClientTimeoutException(itemsWithConditionNotMatched, timeout.toMillis(), MILLISECONDS);
    }

    return results;
  }

  private static void logAsNotReady(Throwable t, HasMetadata meta) {
    LOGGER.warn("Error while waiting for: [{}] with name: [{}] in namespace: [{}]: {}. The resource will be considered not ready.", meta.getKind(), meta.getMetadata().getName(), meta.getMetadata().getNamespace(), t.getMessage());
    LOGGER.debug("The error stack trace:", t);
  }

  @Override
  public Boolean isReady() {
    for (final HasMetadata meta : acceptVisitors(get(), visitors)) {
      if (!isResourceReady(meta)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable<HasMetadata, Boolean> withParameters(Map<String, String> parameters) {
    return new NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(client, config, namespace, explicitNamespace, fromServer, deletingExisting, visitors, null, inputStream, parameters, -1, propagationPolicy, cascading, watchRetryInitialBackoffMillis, watchRetryBackoffMultiplier);
  }

  /**
     * We need to be able to either use an explicit namespace or fallback to the client default.
     * Either-way we need to update the object itself or the client will complain about a mismatch.
     * And this is how we its done.
     */
    private class ChangeNamespace extends TypedVisitor<ObjectMetaBuilder> {

        private final String explicitNamespace;
        private final String fallbackNamespace;

        private ChangeNamespace(String explicitNamespace, String fallbackNamespace) {
            this.explicitNamespace = explicitNamespace;
            this.fallbackNamespace = fallbackNamespace;
        }

        @Override
        public void visit(ObjectMetaBuilder builder) {
            if (Utils.isNotNullOrEmpty(explicitNamespace)) {
                builder.withNamespace(explicitNamespace);
            } else if (Utils.isNullOrEmpty(builder.getNamespace())) {
                builder.withNamespace(fallbackNamespace);
            }
        }
    }

    public NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(OkHttpClient client, Config config, String namespace, String explicitNamespace, Boolean fromServer, Boolean deletingExisting, List<Visitor> visitors, InputStream is, Map<String, String> parameters, Boolean cascading, DeletionPropagation propagationPolicy) {
        this(client, config, namespace, explicitNamespace, fromServer, deletingExisting, visitors, null, is, parameters, -1, propagationPolicy, cascading, Waitable.DEFAULT_INITIAL_BACKOFF_MILLIS, Waitable.DEFAULT_BACKOFF_MULTIPLIER);
    }

    public NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(OkHttpClient client, Config config, String namespace, String explicitNamespace, Boolean fromServer, Boolean deletingExisting, List<Visitor> visitors, Object item, Map<String, String> parameters, DeletionPropagation propagationPolicy, Boolean cascading) {
      this(client, config, namespace, explicitNamespace, fromServer, deletingExisting, visitors, item, null, parameters, -1, propagationPolicy, cascading, Waitable.DEFAULT_INITIAL_BACKOFF_MILLIS, Waitable.DEFAULT_BACKOFF_MULTIPLIER);
    }

    public NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(OkHttpClient client, Config config, String namespace, String explicitNamespace, Boolean fromServer, Boolean deletingExisting, List<Visitor> visitors, Object item, InputStream inputStream, Map<String, String> parameters, long gracePeriodSeconds, DeletionPropagation propagationPolicy, Boolean cascading, long watchRetryInitialBackoffMillis, double watchRetryBackoffMultiplier) {
        super(client, config);
        this.fallbackNamespace = namespace;
        this.explicitNamespace = explicitNamespace;
        this.fromServer = fromServer;
        this.deletingExisting = deletingExisting;
        this.visitors = visitors != null ? new ArrayList<>(visitors) : new ArrayList<>();
        this.watchRetryInitialBackoffMillis = watchRetryInitialBackoffMillis;
        this.watchRetryBackoffMultiplier = watchRetryBackoffMultiplier;

      if (item != null) {
          this.item = item;
        } else if (inputStream != null) {
          this.item = Serialization.unmarshal(inputStream, parameters);
        } else {
          throw new IllegalArgumentException("Need to either specify an Object or an InputStream.");
        }

        this.inputStream = inputStream;
        this.cascading = cascading;
        this.gracePeriodSeconds = gracePeriodSeconds;
        this.propagationPolicy = propagationPolicy;
        this.visitors.add(new ChangeNamespace(explicitNamespace, fallbackNamespace));
    }

  @Override
  public List<HasMetadata> apply() {
       return createOrReplace();
    }

  @Override
  public List<HasMetadata> createOrReplace() {
    List<HasMetadata> result = new ArrayList<>();
    for (HasMetadata meta : acceptVisitors(asHasMetadata(item, true), visitors)) {
      ResourceHandler<HasMetadata, HasMetadataVisitiableBuilder> h = handlerOf(meta);
      String namespaceToUse =  meta.getMetadata().getNamespace();

      String resourceVersion = KubernetesResourceUtil.getResourceVersion(meta);
      try {
        // Create
        KubernetesResourceUtil.setResourceVersion(meta, null);
        result.add(h.create(client, config, namespaceToUse, meta));
      } catch (KubernetesClientException exception) {
        if (exception.getCode() != HttpURLConnection.HTTP_CONFLICT) {
          throw exception;
        }

        // Conflict; check deleteExisting flag otherwise replace
        if (Boolean.TRUE.equals(deletingExisting)) {
          Boolean deleted = h.delete(client, config, namespaceToUse, propagationPolicy, meta);
          if (Boolean.FALSE.equals(deleted)) {
            throw new KubernetesClientException("Failed to delete existing item:" + meta);
          }
          result.add(h.create(client, config, namespaceToUse, meta));
        } else {
          KubernetesResourceUtil.setResourceVersion(meta, resourceVersion);
          result.add(h.replace(client, config, namespaceToUse, meta));
        }
      }
    }
    return result;
  }

  @Override
  public Waitable<List<HasMetadata>, HasMetadata> createOrReplaceAnd() {
    return new NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(client, config, fallbackNamespace, explicitNamespace, fromServer, deletingExisting, visitors, createOrReplace(), inputStream, null, gracePeriodSeconds, propagationPolicy, cascading, watchRetryInitialBackoffMillis, watchRetryBackoffMultiplier);
  }

  @Override
    public Boolean delete() {
        //First pass check before deleting
        for (HasMetadata meta : acceptVisitors(asHasMetadata(item, true), visitors)) {
            if (handlerOf(meta) == null) {
                return false;
            }
        }

        //Second pass do delete
        for (HasMetadata meta :  acceptVisitors(asHasMetadata(item, true), visitors)) {
            ResourceHandler<HasMetadata, HasMetadataVisitiableBuilder> h = handlerOf(meta);
            if (!h.delete(client, config, meta.getMetadata().getNamespace(), propagationPolicy, meta)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<HasMetadata> get() {
        if (fromServer) {
            List<HasMetadata> result = new ArrayList<>();
            for (HasMetadata meta : acceptVisitors(asHasMetadata(item, true), visitors)) {
                ResourceHandler<HasMetadata, ? extends VisitableBuilder> h = handlerOf(meta);
                HasMetadata reloaded = h.reload(client, config, meta.getMetadata().getNamespace(), meta);
                if (reloaded != null) {
                    HasMetadata edited = reloaded;
                    //Let's apply any visitor that might have been specified.
                    for (Visitor v : visitors) {
                        h.edit(edited).accept(v).build();
                    }
                    result.add(reloaded);
                }
            }
            return result;
        } else {
            return acceptVisitors(asHasMetadata(item, true), visitors);
        }
    }

    private static List<HasMetadata> acceptVisitors(List<HasMetadata> list, List<Visitor> visitors) {
        List<HasMetadata> result = new ArrayList<>();
        for (HasMetadata item : list) {
            ResourceHandler<HasMetadata, HasMetadataVisitiableBuilder> h = handlerOf(item);
            VisitableBuilder<HasMetadata, ?> builder = h.edit(item);

            //Let's apply any visitor that might have been specified.
            for (Visitor v : visitors) {
                builder.accept(v);
            }
            result.add(builder.build());
        }
        return result;
    }


    @Override
    public ListVisitFromServerGetDeleteRecreateWaitApplicable<HasMetadata, Boolean> inNamespace(String explicitNamespace) {
        return new NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(client, config, fallbackNamespace, explicitNamespace, fromServer, deletingExisting, visitors, item, null, null, gracePeriodSeconds, propagationPolicy, cascading, watchRetryInitialBackoffMillis, watchRetryBackoffMultiplier);
    }

    @Override
    public Gettable<List<HasMetadata>> fromServer() {
        return new NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(client, config, fallbackNamespace, explicitNamespace, true, deletingExisting, visitors, item, null, null, gracePeriodSeconds, propagationPolicy, cascading, watchRetryInitialBackoffMillis, watchRetryBackoffMultiplier);
    }

    @Override
    public Applicable<List<HasMetadata>> deletingExisting() {
        return new NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(client, config, fallbackNamespace, explicitNamespace, fromServer, true, visitors, item, null, null, gracePeriodSeconds, propagationPolicy, cascading, watchRetryInitialBackoffMillis, watchRetryBackoffMultiplier);
    }

    @Override
    public ListVisitFromServerGetDeleteRecreateWaitApplicable<HasMetadata, Boolean> accept(Visitor visitor) {
        List<Visitor> newVisitors = new ArrayList<>(visitors);
        newVisitors.add(visitor);
        return new NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(client, config, fallbackNamespace, explicitNamespace, fromServer, true, newVisitors, item, null, null, gracePeriodSeconds, propagationPolicy, cascading, watchRetryInitialBackoffMillis, watchRetryBackoffMultiplier);
    }

  @Override public CascadingDeletable<Boolean> withGracePeriod(long gracePeriodSeconds)
  {
    return new NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(client, config, fallbackNamespace, explicitNamespace, fromServer, true, visitors, item, null, null, gracePeriodSeconds, propagationPolicy, cascading, watchRetryInitialBackoffMillis, watchRetryBackoffMultiplier);
  }

  @Override public CascadingDeletable<Boolean> withPropagationPolicy(DeletionPropagation propagationPolicy)
  {
    return new NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(client, config, fallbackNamespace, explicitNamespace, fromServer, true, visitors, item, null, null, gracePeriodSeconds, propagationPolicy, cascading, watchRetryInitialBackoffMillis, watchRetryBackoffMultiplier);
  }

  @Override
  public Waitable<List<HasMetadata>, HasMetadata> withWaitRetryBackoff(long initialBackoff, TimeUnit backoffUnit, double backoffMultiplier) {
    long watchRetryInitialBackoffMillis = backoffUnit.toMillis(initialBackoff);
    return new NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(client, config, fallbackNamespace, explicitNamespace, fromServer, true, visitors, item, null, null, gracePeriodSeconds, propagationPolicy, cascading, watchRetryInitialBackoffMillis, backoffMultiplier);
  }

  @Override
  public Deletable<Boolean> cascading(boolean cascading) {
    return new NamespaceVisitFromServerGetWatchDeleteRecreateWaitApplicableListImpl(client, config, fallbackNamespace, explicitNamespace, fromServer, true, visitors, item, null, null, gracePeriodSeconds, propagationPolicy, cascading, watchRetryInitialBackoffMillis, watchRetryBackoffMultiplier);
  }

  protected boolean isResourceReady(HasMetadata meta) {
    return Readiness.isReady(meta);
  }

  protected <T> List<HasMetadata> asHasMetadata(T item, Boolean enableProccessing) {
    List<HasMetadata> result = new ArrayList<>();
    if (item instanceof KubernetesList) {
      result.addAll(((KubernetesList) item).getItems());
    } else if (item instanceof KubernetesResourceList) {
      result.addAll(((KubernetesResourceList) item).getItems());
    } else if (item instanceof HasMetadata) {
      result.add((HasMetadata) item);
    }  else if (item instanceof String) {
      try (InputStream is = new ByteArrayInputStream(((String)item).getBytes(StandardCharsets.UTF_8))) {
        return asHasMetadata(unmarshal(is), enableProccessing);
      } catch (IOException e) {
        throw KubernetesClientException.launderThrowable(e);
      }
    } else if (item instanceof Collection) {
      for (Object o : (Collection)item) {
        if (o instanceof HasMetadata) {
          result.add((HasMetadata) o);
        }
      }
    }
    return result;
  }

  private static <T> ResourceHandler handlerOf(T item) {
    if (item instanceof HasMetadata) {
      return Handlers.<HasMetadata, HasMetadataVisitiableBuilder>get(((HasMetadata) item).getKind(), ((HasMetadata) item).getApiVersion());
    } else if (item instanceof KubernetesList) {
      return new KubernetesListHandler();
    }  else {
      throw new IllegalArgumentException("Could not find a registered handler for item: [" + item + "].");
    }
  }

  /**
   * Waits until the latch reaches to zero and then checks if the expected result
   * @param latch       The latch.
   * @param expected    The expected number.
   * @param actual      The actual number.
   * @param amount      The amount of time to wait.
   * @param timeUnit    The timeUnit.
   * @return
   */
  private static boolean checkConditionMetForAll(CountDownLatch latch, int expected, AtomicInteger actual, long amount, TimeUnit timeUnit) {
    try {
      if (latch.await(amount, timeUnit)) {
        return actual.intValue() == expected;
      }
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

}
