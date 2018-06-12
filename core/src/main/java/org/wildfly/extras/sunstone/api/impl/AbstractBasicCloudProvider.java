package org.wildfly.extras.sunstone.api.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.wildfly.extras.sunstone.api.CloudProvider;
import org.wildfly.extras.sunstone.api.ConfigProperties;
import org.wildfly.extras.sunstone.api.CreatedNodes;
import org.wildfly.extras.sunstone.api.Node;

/**
 * Abstract {@link CloudProvider} implementation which holds common logic. Independent of underlying cloud provider
 * implementation. Implements some, but not all methods from {@link CloudProvider}, the rest needs to be implemented by
 * extending classes.
 */
public abstract class AbstractBasicCloudProvider implements CloudProvider {
    private static final Logger LOGGER = SunstoneCoreLogger.DEFAULT;

    protected final ConcurrentMap<String, Node> nodes = new ConcurrentHashMap<>();
    protected final ObjectProperties objectProperties;

    public AbstractBasicCloudProvider(String name, Map<String, String> overrides) {
        if (Strings.isNullOrEmpty(name))
            throw new NullPointerException("CloudProvider name has to be specified");
        this.objectProperties = new ObjectProperties(ObjectType.CLOUD_PROVIDER, name, overrides);
    }

    @Override
    public String getName() {
        return objectProperties.getName();
    }

    @Override
    public Node createNode(String name) throws NullPointerException {
        return createNode(name, null);
    }

    @Override
    public CreatedNodes createNodes(String... nodeNames) throws NullPointerException, CompletionException, CancellationException {
        Objects.requireNonNull(nodeNames, "Node names have to be provided.");
        Arrays.stream(nodeNames).forEach(it -> Objects.requireNonNull(it, "Each node name must be not null"));
        CompletableFuture<Node>[] futures = Arrays.stream(nodeNames)
                .map(this::createNodeAsync)
                .toArray((IntFunction<CompletableFuture<Node>[]>) CompletableFuture[]::new);

        try {
            return new CreatedNodes(Arrays.stream(futures)
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            LOGGER.warn("Encountered exception while creating nodes => taking care of cleaning remaining nodes " +
                    "which might take a while, please be patient");
            for (CompletableFuture<Node> future : futures) {
                if (!future.isCompletedExceptionally()) {
                    try {
                        @SuppressWarnings("resource")
                        Node node = future.join();
                        node.close();
                    } catch (Exception e2) {
                        e.addSuppressed(e2);
                    }
                }
            }
            throw e;
        }
    }

    @Override
    public CompletableFuture<Node> createNodeAsync(String name) {
        return createNodeAsync(name, null, ForkJoinPool.commonPool());
    }

    @Override
    public CompletableFuture<Node> createNodeAsync(String name, Executor executor) {
        return createNodeAsync(name, null, executor);
    }

    @Override
    public CompletableFuture<Node> createNodeAsync(String name, Map<String, String> overrides) {
        return createNodeAsync(name, overrides, ForkJoinPool.commonPool());
    }

    @Override
    public CompletableFuture<Node> createNodeAsync(String name, Map<String, String> overrides, Executor executor)
            throws NullPointerException {
        return CompletableFuture.supplyAsync(() -> createNode(name, overrides), executor);
    }

    @Override
    public Node getNode(String name) throws NullPointerException {
        Objects.requireNonNull(name, "Node name has to be non-null");
        return nodes.get(name);
    }

    @Override
    public List<Node> getNodes() {
        return ImmutableList.copyOf(nodes.values());
    }

    @Override
    public ConfigProperties config() {
        return objectProperties;
    }
}
