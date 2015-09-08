package org.apache.mesos.mini;

import org.apache.mesos.mini.mesos.MesosClusterConfig;
import org.junit.ClassRule;
import org.junit.Test;

public class ImageInjectionTest {
    private static final MesosClusterConfig config = MesosClusterConfig.builder()
            .numberOfSlaves(3)
            .privateRegistryPort(16000) // Currently you have to choose an available port by yourself
            .slaveResources(new String[]{"ports(*):[9200-9200,9300-9300]", "ports(*):[9201-9201,9301-9301]", "ports(*):[9202-9202,9302-9302]"})
            .build();

    @ClassRule
    public static MesosCluster cluster = new MesosCluster(config);

    @Test
    public void testInjectImage() {
        HelloWorldContainer container = new HelloWorldContainer(config.dockerClient);
        container.pullImage();

        cluster.injectImage("tutum/hello-world");
    }
}
