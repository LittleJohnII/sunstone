package org.wildfly.extras.sunstone.api.impl.docker;

import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.ComputeMetadata;
import org.junit.Assert;
import org.junit.Test;
import org.wildfly.extras.sunstone.api.CloudProperties;
import org.wildfly.extras.sunstone.api.CloudProvider;
import org.wildfly.extras.sunstone.api.impl.AbstractJCloudsCloudProvider;

import java.util.Optional;

public class NodeLeakOnBadPortsTest {

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
                Optional<? extends ComputeMetadata> latestNode = computeService.listNodes().stream().findFirst();
                if (latestNode.isPresent()) {
                    if (latestNode.get().toString().contains("status=RUNNING")) {
                        computeService.destroyNode(latestNode.get().getId());
                        Assert.fail("A node was leaked, and it had to be forcefully cleaned");
                    }
                }
            } else {
                throw new RuntimeException("Cloud provider " + cloudProvider.getName()
                        + " is not an implementation of AbstractJCloudsCloudProvider and does not support the methods needed for this test. "
                        + "Go clean up the docker image, it may still be running.");
            }
        }
    }

}
