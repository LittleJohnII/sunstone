package org.wildfly.extras.sunstone.api.impl.openshift;

import static org.wildfly.extras.sunstone.api.impl.openshift.OpenShiftConstants.API_VERSION;
import static org.wildfly.extras.sunstone.api.impl.openshift.OpenShiftConstants.CONTAINERS;
import static org.wildfly.extras.sunstone.api.impl.openshift.OpenShiftConstants.IMAGE;
import static org.wildfly.extras.sunstone.api.impl.openshift.OpenShiftConstants.KIND;
import static org.wildfly.extras.sunstone.api.impl.openshift.OpenShiftConstants.METADATA;
import static org.wildfly.extras.sunstone.api.impl.openshift.OpenShiftConstants.NAME;
import static org.wildfly.extras.sunstone.api.impl.openshift.OpenShiftConstants.NAMESPACE;
import static org.wildfly.extras.sunstone.api.impl.openshift.OpenShiftConstants.SPEC;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.common.base.Strings;
import com.openshift.internal.restclient.model.Pod;
import com.openshift.restclient.IClient;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.authorization.ResourceForbiddenException;
import com.openshift.restclient.images.DockerImageURI;
import com.openshift.restclient.model.IPod;
import com.openshift.restclient.model.IProject;
import com.openshift.restclient.model.project.IProjectRequest;
import com.openshift.restclient.model.serviceaccount.IServiceAccount;
import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.wildfly.extras.sunstone.api.CloudProvider;
import org.wildfly.extras.sunstone.api.ConfigProperties;
import org.wildfly.extras.sunstone.api.ExecResult;
import org.wildfly.extras.sunstone.api.Node;
import org.wildfly.extras.sunstone.api.OperationNotSupportedException;
import org.wildfly.extras.sunstone.api.PortOpeningException;
import org.wildfly.extras.sunstone.api.impl.Config;
import org.wildfly.extras.sunstone.api.impl.Constants;
import org.wildfly.extras.sunstone.api.impl.ObjectProperties;
import org.wildfly.extras.sunstone.api.impl.ObjectType;
import org.wildfly.extras.sunstone.api.impl.SunstoneCoreLogger;
import org.wildfly.extras.sunstone.api.ssh.SshClient;

public class OpenShiftNode implements Node {
    private static final Logger LOGGER = SunstoneCoreLogger.DEFAULT;
    private static final Object projectLock = new Object();

    protected final ObjectProperties objectProperties;
    protected final OpenShiftCloudProvider openShiftCloudProvider;
    protected final IClient client;

    private String podName;
    private String podNamespace; // OpenShift "Project" == Kubernetes "Namespace", these terms are often used interchangeably
    private String containerName;
    private String containerImage;

    public OpenShiftNode(OpenShiftCloudProvider openShiftCloudProvider, String name, Map<String, String> overrides) {
        this.objectProperties = new ObjectProperties(ObjectType.NODE, name, overrides);
        this.openShiftCloudProvider = openShiftCloudProvider;
        this.client = openShiftCloudProvider.getClient();

        createPod(name);
        openShiftCloudProvider.registerProject(podName, podNamespace);
    }

    @Override
    public String getName() {
        return objectProperties.getName();
    }

    @Override
    public String getImageName() {
        return containerImage == null ?
                objectProperties.getProperty(Config.Node.OpenShift.CONTAINER_IMAGE) : containerImage;
    }

    @Override
    public CloudProvider getCloudProvider() {
        return openShiftCloudProvider;
    }

    @Override
    public String getPublicAddress() {
        return null;
    }

    @Override
    public String getPrivateAddress() {
        return null;
    }

    @Override
    public int getPublicTcpPort(int tcpPort) {
        return 0;
    }

    @Override
    public boolean isPortOpen(int portNr) {
        return false;
    }

    @Override
    public void waitForPorts(long timeoutSeconds, int... portNrs) throws PortOpeningException {

    }

    @Override
    public boolean isRunning() throws OperationNotSupportedException {
        return false;
    }

    @Override
    public ExecResult exec(String... command) throws OperationNotSupportedException {
        throw new OperationNotSupportedException("copyFileFromNode() is not supported for " + this.getClass().getName());
    }

    @Override
    public void stop() throws OperationNotSupportedException {

    }

    @Override
    public void start() throws OperationNotSupportedException {

    }

    @Override
    public void kill() throws OperationNotSupportedException {

    }

    @Override
    public void copyFileFromNode(String remoteSrc, Path localTarget) throws OperationNotSupportedException {
        throw new OperationNotSupportedException("copyFileFromNode() is not supported for " + this.getClass().getName());
    }

    @Override
    public void copyFileToNode(Path localSrc, String remoteTarget) throws OperationNotSupportedException {
        throw new OperationNotSupportedException("copyFileToNode() is not supported for " + this.getClass().getName());
    }

    @Override
    public SshClient ssh() throws OperationNotSupportedException {
        throw new OperationNotSupportedException("ssh() is not supported for " + this.getClass().getName());
    }

    @Override
    public ConfigProperties config() {
        return objectProperties;
    }

    @Override
    public void close() {
        if (!objectProperties.getPropertyAsBoolean(Config.LEAVE_NODES_RUNNING, !OpenShiftConstants.DESTROY_POD_DEFAULT)) {
            client.delete(client.get(ResourceKind.POD, podName, podNamespace));
        }
        openShiftCloudProvider.unregisterNode(this);
    }

    /* ********************
    end of API methods
     ******************** */

    private void createPod(String name) {
        validateProperties();
        ensureProjectExists();
        ensureServiceAccountHasSecrets();
        ModelNode node = getPodModelNode();
        IPod pod = new Pod(node, client, null);

        LOGGER.debug("Creating pod with name {} on the server with full JSON: {}", name, pod.toJson());
        pod = client.create(pod);
        LOGGER.debug("Created pod with name {} on the server. Result JSON: {}", name, pod.toJson());
    }

    private void validateProperties() {
        podName = objectProperties.getProperty(Config.Node.OpenShift.POD_NAME);
        if (Strings.isNullOrEmpty(podName))
            podName = Constants.KUBERNETES_POD_NAME_PREFIX + UUID.randomUUID();
        podNamespace = objectProperties.getProperty(Config.Node.OpenShift.PROJECT);
        containerImage = objectProperties.getProperty(Config.Node.OpenShift.CONTAINER_IMAGE);
        containerName = objectProperties.getProperty(Config.Node.OpenShift.CONTAINER_NAME);
        if (Strings.isNullOrEmpty(containerName))
            containerName = Constants.KUBERNETES_CONTAINER_NAME_PREFIX + UUID.randomUUID();

        if (Strings.isNullOrEmpty(podNamespace))
            throw new NullPointerException("Project name has to be provided");
        if (Strings.isNullOrEmpty(containerImage))
            throw new NullPointerException("Container image has to be provided");
        if (Strings.isNullOrEmpty(containerName))
            throw new NullPointerException("Container name has to be provided");

        containerImage = new DockerImageURI(containerImage).toString();
        LOGGER.debug("Docker image URI has been converted from {} to {}",
                objectProperties.getProperty(Config.Node.OpenShift.CONTAINER_IMAGE), containerImage);
    }

    private void ensureProjectExists() {
        synchronized (projectLock) {
            try {
                client.get(ResourceKind.PROJECT, podNamespace, "");
                return;
            } catch (NullPointerException | ResourceForbiddenException e) {
                // the project does not exist, create?
                if (!objectProperties.getPropertyAsBoolean(Config.Node.OpenShift.PROJECT_CREATE, false))
                    throw new IllegalArgumentException(String.format("Project with name %s does not exist and flag project.create is set to false", podNamespace), e);
            }
            IProjectRequest projectRequest = client.getResourceFactory().stub(ResourceKind.PROJECT_REQUEST, podNamespace);
            LOGGER.debug("Creating project with name {} on the server with full JSON: {}", podNamespace, projectRequest);
            IProject project = (IProject) client.create(projectRequest);
            LOGGER.debug("Created project with name {} on the server. Result JSON: {}", podNamespace, project);
        }
    }

    private void ensureServiceAccountHasSecrets() {
        String defaultServiceAccount = "default";
        Long timeout = objectProperties.getPropertyAsLong(Config.Node.OpenShift.SERVICE_ACCOUNT_SECRETS_TIMEOUT, 10_000L);
        Long delay = 1_000L;
        Long deadline = System.currentTimeMillis() + timeout;

        boolean cont = getSecretsCountForSa(defaultServiceAccount) <= 0;
        while (cont) {
            cont = getSecretsCountForSa(defaultServiceAccount) <= 0 &&
                    System.currentTimeMillis() < deadline;
            try {
                Thread.sleep(Math.min(deadline - System.currentTimeMillis(), delay));
            } catch (InterruptedException e) {
                // we've been interrupted, just abort
                return;
            }
        }
    }

    private int getSecretsCountForSa(String serviceAccount) {
        return ((IServiceAccount) client.get(ResourceKind.SERVICE_ACCOUNT, serviceAccount, podNamespace))
                .getSecrets().size();
    }

    private ModelNode getPodModelNode() {
        ModelNode node = new ModelNode();
        node.get(API_VERSION).set(openShiftCloudProvider.config().getProperty(Config.CloudProvider.OpenShift.API_VERSION));
        node.get(KIND).set(ResourceKind.POD);

        ModelNode metadata = new ModelNode();
        metadata.get(NAME).set(podName);
        metadata.get(NAMESPACE).set(podNamespace);
        node.get(METADATA).set(metadata);

        ModelNode container = new ModelNode();
        container.get(NAME).set(containerName);
        container.get(IMAGE).set(containerImage);
        List<ModelNode> containers = Collections.singletonList(container);
        ModelNode spec = new ModelNode();
        spec.get(CONTAINERS).set(containers);
        node.get(SPEC).set(spec);

        return node;
    }
}
