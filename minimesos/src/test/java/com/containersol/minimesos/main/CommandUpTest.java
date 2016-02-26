package com.containersol.minimesos.main;

import com.containersol.minimesos.mesos.ClusterArchitecture;
import com.containersol.minimesos.mesos.ClusterContainers;
import com.containersol.minimesos.mesos.MesosAgent;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;


public class CommandUpTest {

    @Test
    public void testDefaultClusterConfig() throws IOException {

        CommandUp commandUp = new CommandUp();

        ClusterArchitecture architecture = commandUp.getClusterArchitecture();
        assertNotNull("architecture is not loaded", architecture);

        ClusterContainers clusterContainers = architecture.getClusterContainers();
        assertNotNull("cluster containers are not loaded", clusterContainers);

        assertTrue("ZooKeeper is required component of cluster", clusterContainers.isPresent(ClusterContainers.Filter.zooKeeper()));
        assertTrue("Mesos Master is required component of cluster", clusterContainers.isPresent(ClusterContainers.Filter.mesosMaster()));

    }

    @Test
    public void testBasicClusterConfig() throws IOException {

        CommandUp commandUp = new CommandUp();
        commandUp.setClusterConfigPath("src/test/resources/clusterconfig/basic.groovy");

        ClusterArchitecture architecture = commandUp.getClusterArchitecture();
        assertNotNull("architecture is not loaded", architecture);

        ClusterContainers clusterContainers = architecture.getClusterContainers();
        assertNotNull("cluster containers are not loaded", clusterContainers);

        assertTrue("ZooKeeper is required component of cluster", clusterContainers.isPresent(ClusterContainers.Filter.zooKeeper()));
        assertTrue("Mesos Master is required component of cluster", clusterContainers.isPresent(ClusterContainers.Filter.mesosMaster()));

        List<MesosAgent> agents = clusterContainers.getContainers().stream().filter(ClusterContainers.Filter.mesosAgent()).map(c -> (MesosAgent) c).collect(Collectors.toList());
        assertEquals( 1, agents.size() );

    }

    @Test
    public void testTwoAgentsClusterConfig() throws IOException {

        CommandUp commandUp = new CommandUp();
        commandUp.setClusterConfigPath("src/test/resources/clusterconfig/two-agents.groovy");

        ClusterArchitecture architecture = commandUp.getClusterArchitecture();
        assertNotNull("architecture is not loaded", architecture);

        ClusterContainers clusterContainers = architecture.getClusterContainers();
        assertNotNull("cluster containers are not loaded", clusterContainers);

        assertTrue("ZooKeeper is required component of cluster", clusterContainers.isPresent(ClusterContainers.Filter.zooKeeper()));
        assertTrue("Mesos Master is required component of cluster", clusterContainers.isPresent(ClusterContainers.Filter.mesosMaster()));

        List<MesosAgent> agents = clusterContainers.getContainers().stream().filter(ClusterContainers.Filter.mesosAgent()).map(c -> (MesosAgent) c).collect(Collectors.toList());
        assertEquals( 2, agents.size() );

    }

}