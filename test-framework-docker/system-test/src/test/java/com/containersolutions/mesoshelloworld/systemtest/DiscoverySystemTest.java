package com.containersolutions.mesoshelloworld.systemtest;

import com.containersol.minimesos.docker.DockerContainersUtil;
import com.containersol.minimesos.mesos.ClusterArchitecture;
import com.containersol.minimesos.mesos.MesosContainer;
import com.containersolutions.mesoshelloworld.scheduler.Configuration;
import com.jayway.awaitility.Awaitility;
import org.apache.log4j.Logger;
import com.containersol.minimesos.MesosCluster;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/**
 * Tests REST node discovery
 */
public class DiscoverySystemTest {

    public static final Logger LOGGER = Logger.getLogger(DiscoverySystemTest.class);

    protected static final ClusterArchitecture CONFIG = new ClusterArchitecture.Builder()
            .withZooKeeper()
            .withMaster()
            .withAgent("ports(*):[8080-8082]")
            .withAgent("ports(*):[8080-8082]")
            .withAgent("ports(*):[8080-8082]")
            .build();

    @ClassRule
    public static final MesosCluster CLUSTER = new MesosCluster(CONFIG);

    private static SchedulerContainer scheduler = null;

    @BeforeClass
    public static void startScheduler() throws Exception {

        String ipAddress = CLUSTER.getMesosMasterContainer().getIpAddress();

        LOGGER.info("Starting Scheduler, connected to " + ipAddress);
        scheduler = new SchedulerContainer(CONFIG.dockerClient, ipAddress);

        // Cluster now has responsibility to shut down container
        CLUSTER.addAndStartContainer(scheduler, MesosContainer.DEFAULT_TIMEOUT_SEC);

        LOGGER.info("Started Scheduler on " + scheduler.getIpAddress());
    }

    @Test
    public void testNodeDiscoveryRest() {

        long timeout = 120;
        DockerContainersUtil util = new DockerContainersUtil(CONFIG.dockerClient);

        final Set<String> ipAddresses = new HashSet<>();
        Awaitility.await("9 expected executors did not come up").atMost(timeout, TimeUnit.SECONDS).until(() -> {
            ipAddresses.clear();
            ipAddresses.addAll(util.getContainers(false).filterByImage(Configuration.DEFAULT_EXECUTOR_IMAGE).getIpAddresses());
            LOGGER.info( String.format("%d executors are found", ipAddresses.size()) );
            return ipAddresses.size() == 9;
        });

        HelloWorldResponse helloWorldResponse = new HelloWorldResponse( ipAddresses, Arrays.asList(8080, 8081, 8082), timeout );
        assertTrue("Executors did not come up within " + timeout + " seconds", helloWorldResponse.isDiscoverySuccessful());

    }

    @AfterClass
    public static void removeExecutors() {

        // stop container, otherwise it keeps on scheduling new executors as soon as they are stopped
        CONFIG.dockerClient.killContainerCmd( scheduler.getContainerId() ).exec();

        DockerContainersUtil util = new DockerContainersUtil(CONFIG.dockerClient);
        util = util.getContainers(false).filterByImage(Configuration.DEFAULT_EXECUTOR_IMAGE);
        LOGGER.info( String.format("Found %d containers to stop and remove", util.size()) );
        util.kill().remove();
    }

}
