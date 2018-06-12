package org.wildfly.extras.sunstone.api.impl;

import com.google.common.base.Strings;
import com.openshift.restclient.ResourceKind;

public final class Constants {
    private Constants() {} // avoid instantiation

    /** The {@code sst-} prefix. */
    public static final String SUNSTONE_PREFIX = "sst-";

    /**
     * Default node group name which will be used for creating JClouds NodeMetadata.
     * Generated from current environment. That is: if running as a part of Jenkins job, it will contain the current
     * build tag, otherwise it will contain the current username.
     */
    public static final String JCLOUDS_NODEGROUP;

    /**
     * Default Kubernetes pod name prefix, for cases where the pod name is not specified by user. Generated the same
     * way {@link Constants#JCLOUDS_NODEGROUP} is.
     */
    public static final String KUBERNETES_POD_NAME_PREFIX;

    /**
     * Same as {@link Constants#KUBERNETES_POD_NAME_PREFIX} but for containers.
     */
    public static final String KUBERNETES_CONTAINER_NAME_PREFIX;

    static {
        // https://wiki.jenkins-ci.org/display/JENKINS/Building+a+software+project#Buildingasoftwareproject-JenkinsSetEnvironmentVariables
        String buildTag = System.getenv("BUILD_TAG");
        String userName = System.getProperty("user.name");

        String prefixPart = Strings.isNullOrEmpty(buildTag) ? userName : buildTag;
        JCLOUDS_NODEGROUP = SUNSTONE_PREFIX + prefixPart;
        KUBERNETES_POD_NAME_PREFIX = SUNSTONE_PREFIX + ResourceKind.POD.toLowerCase() + "-" + prefixPart;
        KUBERNETES_CONTAINER_NAME_PREFIX = SUNSTONE_PREFIX + "container-" + prefixPart;
    }
}
