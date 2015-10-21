package com.containersol.minimesos;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Link;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.jayway.awaitility.Awaitility;
import com.jayway.awaitility.Duration;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.containersol.minimesos.mesos.MesosClusterConfig;
import com.containersol.minimesos.mesos.MesosSlave;
import org.json.JSONObject;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.Assert.*;

public class MesosClusterTest {

    @ClassRule
    public static final MesosCluster cluster = new MesosCluster(
        MesosClusterConfig.builder()
            .zkUrl("mesos")
            .slaveResources(new String[]{
                    "ports(*):[9201-9201, 9301-9301]; cpus(*):0.2; mem(*):256; disk(*):200",
                    "ports(*):[9202-9202, 9302-9302]; cpus(*):0.2; mem(*):256; disk(*):200",
                    "ports(*):[9203-9203, 9303-9303]; cpus(*):0.2; mem(*):256; disk(*):200"
            })
            .build()
    );

    @Test
    public void mesosClusterStateInfoJSONMatchesSchema() throws UnirestException, JsonParseException, JsonMappingException {
        cluster.getStateInfo();
    }

    @Test
    public void mesosClusterCanBeStarted() throws Exception {
        JSONObject stateInfo = cluster.getStateInfoJSON();

        assertEquals(3, stateInfo.getInt("activated_slaves"));
    }

    @Test
    public void mesosResourcesCorrect() throws Exception {
        JSONObject stateInfo = cluster.getStateInfoJSON();
        for (int i = 0; i < 3; i++) {
            assertEquals((long) 0.2, stateInfo.getJSONArray("slaves").getJSONObject(0).getJSONObject("resources").getLong("cpus"));
            assertEquals(256, stateInfo.getJSONArray("slaves").getJSONObject(0).getJSONObject("resources").getInt("mem"));
        }
    }

    @Test
    public void dockerExposeResourcesPorts() {
        DockerClient docker = cluster.getMesosMasterContainer().getOuterDockerClient();
        List<MesosSlave> containers = Arrays.asList(cluster.getSlaves());
        ArrayList<Integer> ports = new ArrayList<>();
        for (MesosSlave container : containers) {
            try {
                ports = container.parsePortsFromResource(container.getResources());
            } catch (Exception e) {
                e.printStackTrace();
            }
            InspectContainerResponse response = docker.inspectContainerCmd(container.getContainerId()).exec();
            Map bindings = response.getNetworkSettings().getPorts().getBindings();
            for (Integer port : ports) {
                assertTrue(bindings.containsKey(new ExposedPort(port)));
            }
        }
    }

    @Test
    public void testPullAndStartContainer() throws UnirestException {
        HelloWorldContainer container = new HelloWorldContainer(cluster.getConfig().dockerClient);
        String containerId = cluster.addAndStartContainer(container);
        String ipAddress = cluster.getConfig().dockerClient.inspectContainerCmd(containerId).exec().getNetworkSettings().getIpAddress();
        String url = "http://" + ipAddress + ":80";
        assertEquals(200, Unirest.get(url).asString().getStatus());
    }

    @Test
    public void testMasterLinkedToSlaves() throws UnirestException {
        List<MesosSlave> containers = Arrays.asList(cluster.getSlaves());
        for (MesosSlave container : containers) {
            InspectContainerResponse exec = cluster.getMesosMasterContainer().getOuterDockerClient().inspectContainerCmd(container.getContainerId()).exec();
            List<Link> links = Arrays.asList(exec.getHostConfig().getLinks());
            assertTrue(links.contains(new Link(cluster.getMesosMasterContainer().getName(), "minimesos-master")));
            assertTrue(links.contains(new Link(cluster.getZkContainer().getName(), "minimesos-zookeeper")));
        }
    }

    public static class LogContainerTestCallback extends LogContainerResultCallback {
        protected final StringBuffer log = new StringBuffer();

        @Override
        public void onNext(Frame frame) {
            log.append(new String(frame.getPayload()));
            super.onNext(frame);
        }

        @Override
        public String toString() {
            return log.toString();
        }
    }

    @Test
    public void testMesosExecuteContainerSuccess() throws InterruptedException {
        MesosSlave mesosSlave = new MesosSlave(
                cluster.getConfig().dockerClient,
                "ports(*):[9204-9204, 9304-9304]; cpus(*):0.2; mem(*):256; disk(*):200",
                "5051",
                cluster.getZkUrl(),
                cluster.getMesosMasterContainer().getContainerId(),
                "containersol/mesos-agent",
                "0.25.0-0.2.70.ubuntu1404", cluster.getClusterId()) {

            @Override
            protected CreateContainerCmd dockerCommand() {
                CreateContainerCmd containerCmd = super.dockerCommand();
                containerCmd.withEntrypoint(
                        "mesos-execute",
                        "--master=" + cluster.getMesosMasterContainer().getIpAddress() + ":5050",
                        "--docker_image=busybox",
                        "--command=echo 1",
                        "--name=test-cmd",
                        "--resources=cpus(*):0.1;mem(*):256"
                );
                return containerCmd;
            }
        };

        cluster.addAndStartContainer(mesosSlave);
        LogContainerTestCallback cb = new LogContainerTestCallback();
        cluster.getMesosMasterContainer().getOuterDockerClient().logContainerCmd(mesosSlave.getContainerId()).withStdOut().exec(cb);
        cb.awaitCompletion();

        Awaitility.await().atMost(Duration.ONE_MINUTE).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                LogContainerTestCallback cb = new LogContainerTestCallback();
                cluster.getMesosMasterContainer().getOuterDockerClient().logContainerCmd(mesosSlave.getContainerId()).withStdOut().exec(cb);
                cb.awaitCompletion();
                return cb.toString().contains("Received status update TASK_FINISHED for task test-cmd");
            }
        });
    }

//    @Test
//    public void testMesosInstall() {
//        MesosCluster.install("src/test/resources/marathon.json");
//
//    }

}
