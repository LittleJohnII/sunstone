package org.wildfly.extras.sunstone.tests.openshift;

import static org.junit.Assume.assumeFalse;

import java.util.Collections;
import java.util.Map;

import com.google.common.base.Strings;
import org.junit.BeforeClass;
import org.wildfly.extras.sunstone.api.CloudProviderType;
import org.wildfly.extras.sunstone.api.impl.Config;
import org.wildfly.extras.sunstone.tests.AbstractCloudProviderTest;
import org.wildfly.extras.sunstone.tests.TestedCloudProvider;

public class OpenShiftTest extends AbstractCloudProviderTest {
    public OpenShiftTest() {
        super(OpenShiftTCP);
    }

    @BeforeClass
    public static void setUpClass() {
        assumeFalse(Strings.isNullOrEmpty(System.getProperty("openshift.base-url")));
    }

    private static final TestedCloudProvider OpenShiftTCP = new TestedCloudProvider() {
        @Override
        public CloudProviderType type() {
            return CloudProviderType.OPENSHIFT;
        }

        @Override
        public boolean hasImages() {
            return true;
        }

        @Override
        public boolean hasPortMapping() {
            return true;
        }

        @Override
        public boolean commandExecutionSupported() {
            return true;
        }

        @Override
        public boolean execBuilderSupported() {
            return true;
        }

        @Override
        public boolean lifecycleControlSupported() {
            return true;
        }

        @Override
        public boolean fileCopyingSupported() {
            return true;
        }

        @Override
        public Map<String, String> overridesThatPreventCreatingNode() {
            return Collections.singletonMap(Config.CloudProvider.OpenShift.PASSWORD, "");
            // beware - on default Minishift config, any password and any username, except "", is accepted!
        }
    };
}