package com.containersol.minimesos;

import com.containersol.minimesos.container.AbstractContainer;
import com.containersol.minimesos.docker.DockerContainersUtil;
import com.containersol.minimesos.marathon.Marathon;
import com.containersol.minimesos.marathon.MarathonClient;
import com.containersol.minimesos.mesos.*;
import com.containersol.minimesos.util.MesosClusterStateResponse;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.junit.rules.ExternalResource;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Starts the mesos cluster. Responsible for setting up a private docker registry. Once started, users can add
 * their own images to the private registry and start containers which will be removed when the Mesos cluster is
 * destroyed.
 */
public class MesosCluster extends ExternalResource {

    public static final String MINIMESOS_HOST_DIR_PROPERTY = "minimesos.host.dir";
    public static final String MINIMESOS_FILE_PROPERTY = "minimesos.cluster";

    private static Logger LOGGER = Logger.getLogger(MesosCluster.class);

    private static DockerClient dockerClient = DockerClientFactory.build();

    private final List<AbstractContainer> containers = Collections.synchronizedList(new ArrayList<>());

    private ClusterArchitecture clusterArchitecture;

    private final String clusterId;


    /**
     * Create a new cluster with a specified cluster architecture.
     * @param clusterArchitecture Represents the layout of the cluster. See {@link ClusterArchitecture} and {@link ClusterUtil}
     */
    public MesosCluster(ClusterArchitecture clusterArchitecture) {
        this.clusterArchitecture = clusterArchitecture;
        clusterId = Integer.toUnsignedString(new SecureRandom().nextInt());
    }

    /**
     * Starts the Mesos cluster and its containers
     *
     * @param timeout in seconds
     */
    public void start(int timeout) {

        if (clusterArchitecture == null) {
            throw new ClusterArchitecture.MesosArchitectureException("No cluster architecture specified");
        }

        clusterArchitecture.getClusterContainers().getContainers().forEach((container) -> addAndStartContainer(container, timeout));
        // wait until the given number of agents are registered
        new MesosClusterStateResponse(this).waitFor();

    }

    /**
     * Stops the Mesos cluster and its containers
     */
    public void stop() {
        for (AbstractContainer container : this.containers) {
            LOGGER.debug("Removing container [" + container.getContainerId() + "]");
            try {
                container.remove();
            } catch (NotFoundException e) {
                LOGGER.error(String.format("Cannot remove container %s, maybe it's already dead?", container.getContainerId()));
            }
        }
        this.containers.clear();
    }

    /**
     * Start a container. This container will be removed when the Mesos cluster is shut down.
     *
     * @param container container to be started
     * @param timeout in seconds
     *
     * @return container ID
     */
    public String addAndStartContainer(AbstractContainer container, int timeout) {

        container.setClusterId(clusterId);
        LOGGER.debug( String.format("Starting %s (%s) container", container.getName(), container.getContainerId()) );

        try {
            container.start(timeout);
            containers.add(container);
        } catch (Exception exc ) {
            String msg = String.format("Failed to start %s (%s) container", container.getName(), container.getContainerId());
            LOGGER.error( msg, exc );
            throw new MinimesosException( msg, exc );
        }


        return container.getContainerId();
    }


    /**
     * Retrieves JSON with Mesos Cluster master state
     *
     * @param clusterId id of the cluster
     * @return stage JSON
     */
    public static String getClusterStateInfo(String clusterId) {
        Container container = getContainer(clusterId, "master");
        return getContainerStateInfo(container);
    }

    /**
     * Retrieves JSON with Mesos Cluster master state
     *
     * @return stage JSON
     */
    public String getClusterStateInfo() {
        return getClusterStateInfo( clusterId );
    }

    /**
     * Retrieves JSON with Mesos state of the given container
     *
     * @param containerId ID of the container to get state from
     * @return stage JSON
     */
    public static String getContainerStateInfo(String containerId) {
        Container container = DockerContainersUtil.getContainer(dockerClient, containerId);
        return getContainerStateInfo(container);
    }

    private static String getContainerStateInfo(Container container) {

        String info = null;

        if (container != null) {

            String containerId = container.getId();
            String ip = DockerContainersUtil.getIpAddress(dockerClient, containerId);

            if (ip != null) {

                int port = container.getNames()[0].contains("minimesos-agent-") ? MesosAgent.MESOS_AGENT_PORT : MesosMaster.MESOS_MASTER_PORT;
                String url = "http://" + ip + ":" + port + "/state.json";

                try {
                    HttpResponse<JsonNode> request = Unirest.get(url).asJson();
                    info = request.getBody().toString();
                } catch (UnirestException e) {
                    throw new MinimesosException("Failed to retrieve state from " + url, e);
                }

            } else {
                throw new MinimesosException("Cannot find container. Please verify the cluster is running using `minimesos info` command.");
            }
        }

        return info;

    }


    public JSONObject getStateInfoJSON() throws UnirestException {
        return Unirest.get("http://" + this.getMesosMasterContainer().getIpAddress() + ":" + MesosMaster.MESOS_MASTER_PORT + "/state.json").asJson().getBody().getObject();
    }

    public Map<String, String> getFlags() throws UnirestException {
        JSONObject flagsJson = this.getStateInfoJSON().getJSONObject("flags");
        Map<String, String> flags = new TreeMap<>();
        for (Object key : flagsJson.keySet()) {
            String keyString = (String) key;
            String value = flagsJson.getString(keyString);
            flags.put(keyString, value);
        }
        return flags;
    }

    @Override
    protected void before() throws Throwable {
        start(MesosContainer.DEFAULT_TIMEOUT_SEC);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                destroyContainers(clusterId);
            }
        });
    }

    private static void destroyContainers(String clusterId) {
        DockerClient dockerClient = DockerClientFactory.build();
        List<Container> containers = dockerClient.listContainersCmd().exec();
        for (Container container : containers) {
            if (container.getNames()[0].contains(clusterId)) {
                dockerClient.removeContainerCmd(container.getId()).withForce().withRemoveVolumes(true).exec();
            }
        }
        LOGGER.info("Destroyed minimesos cluster " + clusterId);
    }

    public List<AbstractContainer> getContainers() {
        return clusterArchitecture.getClusterContainers().getContainers();
    }

    public MesosAgent[] getAgents() {
        List<AbstractContainer> containers = clusterArchitecture.getClusterContainers().getContainers();
        List<AbstractContainer> agents = containers.stream().filter(ClusterContainers.Filter.mesosAgent()).collect(Collectors.toList());
        MesosAgent[] array = new MesosAgent[agents.size()];
        return agents.toArray(array);
    }

    @Override
    protected void after() {
        stop();
    }

    public MesosMaster getMesosMasterContainer() {
        return (MesosMaster) clusterArchitecture.getClusterContainers().getOne(ClusterContainers.Filter.mesosMaster()).get();
    }

    public String getZkUrl() {
        return MesosContainer.getFormattedZKAddress(getZkContainer());
    }

    public ZooKeeper getZkContainer() {
        return (ZooKeeper) clusterArchitecture.getClusterContainers().getOne(ClusterContainers.Filter.zooKeeper()).get();
    }

    public Marathon getMarathonContainer() {
        return (Marathon) clusterArchitecture.getClusterContainers().getOne(ClusterContainers.Filter.marathon()).get();
    }

    public String getClusterId() {
        return clusterId;
    }

    /**
     * Type safe retrieval of container object (based on naming convention)
     * @param clusterId ID of the cluster to search for containers
     * @param role container role in the cluster
     * @return object of clazz type, which represent the container
     */
    public static Container getContainer(String clusterId, String role) {
        List<Container> containers = dockerClient.listContainersCmd().exec();
        for (Container container : containers) {
            if (container.getNames()[0].contains("minimesos-" + role) && container.getNames()[0].contains(clusterId + "-")) {
                return container;
            }
        }
        return null;
    }

    public static String getContainerIp(String clusterId, String role) {
        Container container = getContainer(clusterId, role);
        if ( container != null ) {
            return DockerContainersUtil.getIpAddress( dockerClient, container.getId() );
        }
        return null;
    }

    /**
     * Check existence of a running minimesos master container
     * @param clusterId String
     * @return boolean
     */
    public static boolean isUp(String clusterId) {
        if (clusterId != null) {
            DockerClient dockerClient = DockerClientFactory.build();
            List<Container> containers = dockerClient.listContainersCmd().exec();
            for (Container container : containers) {
                for (String name : container.getNames()) {
                    if (name.contains("minimesos-master-" + clusterId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static void destroy() {

        String clusterId = readClusterId();

        if (clusterId != null) {

            MarathonClient marathon = new MarathonClient( getContainerIp(clusterId, "marathon") );
            marathon.killAllApps();

            destroyContainers(clusterId);

            File minimesosFile = getMinimesosFile();
            if (minimesosFile.exists()) {
                minimesosFile.deleteOnExit();
            }

        } else {
            LOGGER.info("Minimesos cluster is not running");
        }
    }

    public static String readClusterId() {
        try {
            return IOUtils.toString(new FileReader(getMinimesosFile()));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * @return never null
     */
    private static File getMinimesosFile() {
        return new File( getMinimesosDir(), MINIMESOS_FILE_PROPERTY);
    }

    public static File getMinimesosDir() {

        File hostDir = getMinimesosHostDir();
        File minimesosDir = new File( hostDir, ".minimesos");
        if( !minimesosDir.exists() ) {
            if( !minimesosDir.mkdirs() ) {
                throw new MinimesosException( "Failed to create " + minimesosDir.getAbsolutePath() + " directory" );
            }
        }

        return minimesosDir;
    }

    public static File getMinimesosHostDir() {
        String sp = System.getProperty(MINIMESOS_HOST_DIR_PROPERTY);
        if( sp == null ) {
            sp = System.getProperty("user.dir");
        }
        return new File( sp );
    }

    public void writeClusterId() {
        File minimesosDir = getMinimesosDir();
        try {
            FileUtils.forceMkdir(minimesosDir);
            Files.write(Paths.get(minimesosDir.getAbsolutePath() + "/" + MINIMESOS_FILE_PROPERTY), getClusterId().getBytes());
        } catch (IOException ie) {
            LOGGER.error("Could not write .minimesos folder", ie);
            throw new RuntimeException(ie);
        }
    }

    public static void checkStateFile(String clusterId) {
        if (clusterId != null && !isUp(clusterId)) {
            File minimesosFile = getMinimesosFile();
            if (minimesosFile.delete()) {
                LOGGER.info("Invalid state file removed");
            } else {
                LOGGER.info("Cannot remove invalid state file " + minimesosFile.getAbsolutePath());
            }
        }
    }

    public static void printServiceUrl(String clusterId, String serviceName, boolean exposedHostPorts) {
        String dockerHostIp = System.getenv("DOCKER_HOST_IP");
        List<Container> containers = dockerClient.listContainersCmd().exec();
        for (Container container : containers) {
            for (String name : container.getNames()) {
                if (name.contains("minimesos-" + serviceName + "-" + clusterId)) {
                    String uri, ip;
                    if (!exposedHostPorts || dockerHostIp.isEmpty()) {
                        ip = DockerContainersUtil.getIpAddress( dockerClient, container.getId() );
                    } else {
                        ip = dockerHostIp;
                    }
                    switch (serviceName) {
                        case "master":
                            uri = "Master http://" + ip + ":" + MesosMaster.MESOS_MASTER_PORT;
                            break;
                        case "marathon":
                            uri = "Marathon http://" + ip + ":" + Marathon.MARATHON_PORT;
                            break;
                        default:
                            uri = "Unknown service type '" + serviceName + "'";
                    }
                    LOGGER.info(uri);
                    return;
                }
            }
        }
    }

    public static void executeMarathonTask(String clusterId, String marathonFilePath) {

        String marathonIp = getContainerIp(clusterId, "marathon");
        if( marathonIp == null ) {
            throw new MinimesosException("Marathon container is not found in cluster " + MesosCluster.readClusterId() );
        }

        File marathonFile = new File( marathonFilePath );
        if( !marathonFile.exists() ) {
            marathonFile = new File( getMinimesosHostDir(), marathonFilePath );
            if( !marathonFile.exists() ) {
                String msg = String.format("Neither %s nor %s exist", new File( marathonFilePath ).getAbsolutePath(), marathonFile.getAbsolutePath() );
                throw new MinimesosException( msg );
            }
        }

        MarathonClient marathonClient = new MarathonClient( marathonIp );
        LOGGER.info(String.format("Installing %s on marathon %s", marathonFile, marathonIp));

        try (FileInputStream fis = new FileInputStream(marathonFile)) {
            String taskJson = IOUtils.toString(fis);
            marathonClient.deployTask(taskJson);
        } catch (IOException e) {
            throw new MinimesosException( "Failed to open " + marathonFile.getAbsolutePath(), e );
        }

    }

    public void executeMarathonTask(String taskJson) {

        String marathonIp = getMarathonContainer().getIpAddress();
        if( marathonIp == null ) {
            throw new MinimesosException("Marathon container is not found in cluster " + clusterId );
        }

        MarathonClient marathonClient = new MarathonClient( marathonIp );
        LOGGER.info(String.format("Installing a task on marathon %s", marathonIp));
        marathonClient.deployTask(taskJson);

    }

    public String getStateUrl() {
        return "http://" + getMesosMasterContainer().getIpAddress() + ":5050/state.json";
    }

}
