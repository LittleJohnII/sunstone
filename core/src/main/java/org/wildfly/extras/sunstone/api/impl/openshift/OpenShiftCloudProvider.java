package org.wildfly.extras.sunstone.api.impl.openshift;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.base.Strings;
import com.openshift.internal.restclient.OpenShiftAPIVersion;
import com.openshift.restclient.ClientBuilder;
import com.openshift.restclient.IClient;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.model.IProject;
import org.slf4j.Logger;
import org.wildfly.extras.sunstone.api.CloudProviderType;
import org.wildfly.extras.sunstone.api.Node;
import org.wildfly.extras.sunstone.api.impl.AbstractBasicCloudProvider;
import org.wildfly.extras.sunstone.api.impl.Config;
import org.wildfly.extras.sunstone.api.impl.ObjectProperties;
import org.wildfly.extras.sunstone.api.impl.ObjectType;
import org.wildfly.extras.sunstone.api.impl.SunstoneCoreLogger;

public class OpenShiftCloudProvider extends AbstractBasicCloudProvider {
    private static final Logger LOGGER = SunstoneCoreLogger.DEFAULT;

    protected final IClient client;

    protected Map<String, String> podNameToProjectMap = new HashMap<>();

    public OpenShiftCloudProvider(String name, Map<String, String> overrides) {
        this(name, overrides, null, true);
    }

    /**
     * This constructor exists for purposes where the OpenShift client requires configuration which is not handled by
     * Sunstone. Where possible, {@code OpenShiftCloudProvider(String, Map<String, String>)} should be used, so that it
     * is as easy as possible to migrate providers.
     *
     * @param name
     * @param overrides
     * @param client
     */
    public OpenShiftCloudProvider(String name, Map<String, String> overrides, IClient client) {
        this(name, overrides, client, false);
    }

    private OpenShiftCloudProvider(String name, Map<String, String> overrides, IClient client, boolean buildClientIfNotProvided) {
        super(name, overrides);
        if (!buildClientIfNotProvided) {
            Objects.requireNonNull(client, "Client has to be specified when using OpenShiftCloudProvider(String, Map, IClient)");
            this.client = client;
        } else {
            this.client = buildClient();
        }
    }

    @Override
    public CloudProviderType getCloudProviderType() {
        return CloudProviderType.OPENSHIFT;
    }

    @Override
    public Node createNode(String name, Map<String, String> overrides) throws NullPointerException, IllegalArgumentException {
        if (nodes.keySet().contains(name))
            throw new IllegalArgumentException(String.format("A node with name %s is already defined for provider %s", name, getName()));
        OpenShiftNode openshiftNode = new OpenShiftNode(this, name, overrides);
        nodes.put(name, openshiftNode);
        return openshiftNode;
    }

    @Override
    public void close() {
        // close all nodes
        nodes.values().forEach(Node::close);
        // close projects
        destroyProjects();
        new HashSet<>(podNameToProjectMap.entrySet()).forEach(this::unregisterProject);
    }

    /**
     * @return the underlying {@link IClient} that this implementation uses to work with OpenShift
     */
    public IClient getClient() {
        return client;
    }

    /* ********************
    end of API methods
     ******************** */

    private IClient buildClient() {
        validateProperties();

        ClientBuilder clientBuilder = new ClientBuilder(objectProperties.getProperty(Config.CloudProvider.OpenShift.BASE_URL));
        clientBuilder.withUserName(objectProperties.getProperty(Config.CloudProvider.OpenShift.USERNAME))
                .withPassword(objectProperties.getProperty(Config.CloudProvider.OpenShift.PASSWORD))
                .usingToken(objectProperties.getProperty(Config.CloudProvider.OpenShift.AUTHORIZATION_TOKEN));

        return clientBuilder.build();
    }

    private void validateProperties() {
        if (Strings.isNullOrEmpty(objectProperties.getProperty(Config.CloudProvider.OpenShift.BASE_URL)))
            throw new NullPointerException("Base URL has to be provided");

        try {
            OpenShiftAPIVersion.valueOf(objectProperties.getProperty(Config.CloudProvider.OpenShift.API_VERSION));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid OpenShift API version: {}, using the default of {}",
                    objectProperties.getProperty(Config.CloudProvider.OpenShift.API_VERSION),
                    OpenShiftAPIVersion.v1.name());
            objectProperties.applyOverrides(Collections.singletonMap(Config.CloudProvider.OpenShift.API_VERSION,
                    OpenShiftAPIVersion.v1.name()));
        }
    }

    void registerProject(String podName, String project) {
        podNameToProjectMap.put(podName, project);
    }

    void unregisterProject(Map.Entry<String, String> entry) {
        podNameToProjectMap.remove(entry.getKey(), entry.getValue());
    }

    void unregisterNode(OpenShiftNode node) {
        nodes.remove(node.getName(), node);
    }

    private void destroyProjects() {
        Set<IProject> projectsToDestroy = new HashSet<>();
        podNameToProjectMap.forEach((key, value) -> {
            if (new ObjectProperties(ObjectType.NODE, key)
                    .getPropertyAsBoolean(Config.Node.OpenShift.PROJECT_DESTROY, OpenShiftConstants.DESTROY_PROJECT_DEFAULT))
                projectsToDestroy.add(client.get(ResourceKind.PROJECT, value, ""));
        });
        projectsToDestroy.forEach(client::delete);
    }
}
