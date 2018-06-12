package org.wildfly.extras.sunstone.api.impl;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.inject.Injector;
import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.util.OpenSocketFinder;
import org.slf4j.Logger;
import org.wildfly.extras.sunstone.api.CloudProviderType;
import org.wildfly.extras.sunstone.api.ConfigProperties;
import org.wildfly.extras.sunstone.api.Node;
import org.wildfly.extras.sunstone.api.jclouds.JCloudsCloudProvider;
import org.wildfly.extras.sunstone.api.jclouds.JCloudsNode;

/**
 * Abstract {@link JCloudsCloudProvider} implementation which holds common logic. Assumes the underlying implementation
 * is based on JClouds.
 */
public abstract class AbstractJCloudsCloudProvider extends AbstractBasicCloudProvider implements JCloudsCloudProvider {
    private static final Logger LOGGER = SunstoneCoreLogger.DEFAULT;

    protected final CloudProviderType cloudProviderType;
    protected final ComputeServiceContext computeServiceContext;
    protected final OpenSocketFinder socketFinder;
    protected final Injector guiceInjector;

    // overrides the nodes field at AbstractBasicCloudProvider, since this one holds JCloudsNode
    private final ConcurrentMap<String, JCloudsNode> nodes = new ConcurrentHashMap<>();

    /**
     * Constructor which takes name and map of overrides.
     *
     * @param name      provider name (must not be {@code null})
     * @param overrides configuration overrides (may be {@code null})
     * @throws NullPointerException when {@code name} is {@code null}
     */
    public AbstractJCloudsCloudProvider(String name, CloudProviderType cloudProviderType, Map<String, String> overrides,
                                        Function<ObjectProperties, ContextBuilder> contextBuilderCreator) {
        super(name, overrides);

        this.cloudProviderType = cloudProviderType;

        LOGGER.debug("Creating {} ComputeServiceContext", cloudProviderType.getHumanReadableName());
        ContextBuilder contextBuilder = contextBuilderCreator.apply(objectProperties);
        // TODO the following builds a Guice injector twice, which is wasteful
        this.guiceInjector = contextBuilder.buildInjector();
        this.socketFinder = guiceInjector.getInstance(OpenSocketFinder.class);
        this.computeServiceContext = contextBuilder.buildView(ComputeServiceContext.class);
        LOGGER.info("Started {} cloud provider '{}'", cloudProviderType.getHumanReadableName(), name);
    }


    @Override
    public final CloudProviderType getCloudProviderType() {
        return cloudProviderType;
    }

    @Override
    public final JCloudsNode createNode(String name, Map<String, String> overrides) {
        Objects.requireNonNull(name, "Node name has to be provided.");
        // the ConcurrentHashMap.compute method will block other threads trying to call it if there's a hash collision
        // (see its javadoc); an alternative solution that would avoid this problem would be to use a dummy value:
        //
        // old = nodes.putIfAbsent(name, DUMMY_VALUE)
        // if (old != null) ... node with this name already exists ...
        // nodes.put(name, createNodeInternal(name, overrides))
        return nodes.compute(name, (k, v) -> {
            if (v != null) {
                throw new IllegalArgumentException("There already exist node with given name \"" + k + "\"; "
                        + "You are not allowed to create two nodes with the same name under same provider");
            } else {
                final AbstractJCloudsNode<?> createdNode = (AbstractJCloudsNode<?>) createNodeInternal(name, overrides);
                LOGGER.debug("Node '{}' can be reached now on address {}", createdNode.getName(), createdNode.getPublicAddress());
                try {
                    createdNode.handleBootScript();
                    createdNode.waitForStartPorts(null);
                    LOGGER.debug("Node '{}' is successfully started", createdNode.getName());
                } catch (Exception e) {
                    if (nodeRequiresDestroy()) {
                        computeServiceContext.getComputeService().destroyNode(createdNode.getInitialNodeMetadata().getId());
                    }
                    if (e instanceof RuntimeException) {
                        throw (RuntimeException) e;
                    } else {
                        throw new RuntimeException("Processing boot script failed for "
                                + cloudProviderType.getHumanReadableName() + " node '" + name + "'", e);
                    }
                }
                return createdNode;
            }
        });
    }

    protected abstract JCloudsNode createNodeInternal(String name, Map<String, String> overrides);

    @Override
    public final JCloudsNode getNode(String name) {
        Objects.requireNonNull(name, "Node name has to be provided.");
        return nodes.get(name);
    }

    @Override
    public final List<Node> getNodes() {
        return ImmutableList.copyOf(nodes.values());
    }

    final void destroyNode(JCloudsNode node) {
        LOGGER.info("Destroying {} node '{}'", cloudProviderType.getHumanReadableName(), node.getName());
        if (nodeRequiresDestroy()) {
            computeServiceContext.getComputeService().destroyNode(node.getInitialNodeMetadata().getId());
            LOGGER.info("Destroyed {} node '{}'", cloudProviderType.getHumanReadableName(), node.getName());
        } else {
            LOGGER.info("The {} node '{}' ({}) was configured to be kept running. The node is not destroyed.",
                    cloudProviderType.getHumanReadableName(), node.getName());
        }
        nodes.remove(node.getName());
    }

    @Override
    public final void close() {
        LOGGER.info("Destroying {} cloud provider '{}'", cloudProviderType.getHumanReadableName(), getName());

        for (Iterator<Map.Entry<String, JCloudsNode>> it = nodes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, JCloudsNode> nodeEntry = it.next();
            JCloudsNode node = nodeEntry.getValue();
            try {
                if (nodeRequiresDestroy()) {
                    computeServiceContext.getComputeService().destroyNode(node.getInitialNodeMetadata().getId());
                }
                it.remove();
                LOGGER.info("Destroyed {} node '{}'", cloudProviderType.getHumanReadableName(), node.getName());
            } catch (RuntimeException e) {
                LOGGER.error("Failed to destroy node '{}'", node.getName(), e);
            }
        }
        computeServiceContext.close();
        LOGGER.info("Destroyed {} cloud provider '{}'", cloudProviderType.getHumanReadableName(), getName());
    }

    // typically shouldn't be overridden
    public boolean nodeRequiresDestroy() {
        if (objectProperties.getPropertyAsBoolean(Config.LEAVE_NODES_RUNNING, false)) {
            return false;
        }

        return true;
    }

    @Override
    public final ComputeServiceContext getComputeServiceContext() {
        return computeServiceContext;
    }

    public final ObjectProperties getObjectProperties() {
        return objectProperties;
    }

    /**
     * Should be overridden if the cloud provider imposes some limits on the node group name. For example,
     * Azure imposes a length limit on the node group name, so the Azure implementation overrides this method
     * to make sure that the node group name isn't too long. Note that the {@code nodeGroup} param is only a prefix
     * that doesn't have to be unique. JClouds will add a unique suffix later on.
     */
    protected String postProcessNodeGroupWhenCreatingNode(String nodeGroup) {
        return nodeGroup;
    }

    public String getProviderSpecificPropertyName(ConfigProperties configProperties, String sharedName) {
        final String providerSpecificName = getCloudProviderType().getLabel() + "." + sharedName;
        return hasProviderSpecificPropertyName(configProperties, sharedName) ? providerSpecificName : sharedName;
    }

    public boolean hasProviderSpecificPropertyName(ConfigProperties configProperties, String sharedName) {
        final String providerSpecificName = getCloudProviderType().getLabel() + "." + sharedName;
        return configProperties.getProperty(providerSpecificName) != null;
    }
}
