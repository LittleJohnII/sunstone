package org.wildfly.extras.sunstone.api.impl.docker;

import org.jclouds.compute.ComputeService;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.wildfly.extras.sunstone.api.CloudProperties;
import org.wildfly.extras.sunstone.api.CloudProvider;
import org.wildfly.extras.sunstone.api.impl.AbstractJCloudsCloudProvider;
import org.wildfly.extras.sunstone.api.impl.SunstoneCoreLogger;

public class NodeLeakOnBadPortsTest {

    private static final Logger LOGGER = SunstoneCoreLogger.DEFAULT;

    @Test
    public void test() {

        CloudProperties.getInstance().reset().load(this.getClass());
        CloudProvider cloudProvider = CloudProvider.create("provider0");

        try {
            cloudProvider.createNode("node0");
            Assert.fail("Port 2468 was unexpectedly opened");
        } catch (Exception e) {
            if (cloudProvider instanceof AbstractJCloudsCloudProvider) {
                ComputeService computeService = ((AbstractJCloudsCloudProvider) cloudProvider).getComputeServiceContext().getComputeService();
                computeService.listNodes().forEach(computeMetadata -> {
                    if (computeMetadata.toString().contains("status=RUNNING")) {
                        computeService.destroyNode(computeMetadata.getId());
                        Assert.fail("A node was leaked, and it had to be forcefully cleaned");
                    }
                });
            } else {
                throw new RuntimeException("Cloud provider " + cloudProvider.getName()
                        + " is not an implementation of AbstractJCloudsCloudProvider and does not support the methods needed for this test. "
                        + "Go clean up the docker image, it may still be running.");
            }
        }
    }

}
