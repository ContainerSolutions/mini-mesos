package com.containersol.minimesos.main;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.containersol.minimesos.MesosCluster;
import com.containersol.minimesos.MinimesosException;
import com.containersol.minimesos.cmdhooks.CliCommandHookExecutor;
import com.containersol.minimesos.cmdhooks.up.PrintServiceInfo;
import com.containersol.minimesos.marathon.Marathon;
import com.containersol.minimesos.mesos.*;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InfoCmd;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.TreeMap;

/**
 * Main method for interacting with minimesos.
 */
@Parameters(separators = "=", commandDescription = "Global options")
public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static CommandUp commandUp;

    private static String clusterId;

    @Parameter(names = {"--help", "-help", "-?", "-h"}, help = true)
    private static boolean help = false;

    public static CommandUp getCommandUp() {
        return commandUp;
    }

    public static void main(String[] args)  {

        JCommander jc = new JCommander(new Main());
        jc.setProgramName("minimesos");

        commandUp = new CommandUp();
        CommandDestroy commandDestroy = new CommandDestroy();
        CommandHelp commandHelp = new CommandHelp();
        CommandInfo commandInfo = new CommandInfo();
        CommandInstall commandInstall = new CommandInstall();
        CommandState commandState = new CommandState();

        jc.addCommand("up", commandUp);
        jc.addCommand("destroy", commandDestroy);
        jc.addCommand("help", commandHelp);
        jc.addCommand("info", commandInfo);
        jc.addCommand("install", commandInstall );
        jc.addCommand("state", commandState);

        try {
            jc.parse(args);
        } catch (Exception e) {
            LOGGER.error("Failed to parse parameters. " + e.getMessage() + "\n" );
            jc.usage();
            System.exit(1);
        }

        String clusterId = MesosCluster.readClusterId();
        MesosCluster.checkStateFile(clusterId);
        clusterId = MesosCluster.readClusterId();

        if(help) {
            jc.usage();
            return;
        }

        if (jc.getParsedCommand() == null) {
            if (clusterId != null) {
                MesosCluster.printServiceUrl(clusterId, "master", commandUp);
                MesosCluster.printServiceUrl(clusterId, "marathon", commandUp);
            } else {
                jc.usage();
            }
            return;
        }


        try {
            switch (jc.getParsedCommand()) {
                case "up":
                    doUp(commandUp.getTimeout());
                    CliCommandHookExecutor.fireCallbacks("up", Main.clusterId, commandUp);
                    break;
                case "info":
                    printInfo(commandInfo);
                    break;
                case "destroy":
                    MesosCluster.destroy();
                    break;
                case "install":
                    String marathonJson = commandInstall.getMarathonJson( MesosCluster.getMinimesosHostDir() );
                    if(StringUtils.isBlank(marathonJson) ) {
                        jc.usage();
                    } else {
                        MesosCluster.executeMarathonTask( clusterId, marathonJson );
                    }
                    break;
                case "state":
                    printState(commandState.getAgent());
                    break;
                case "help":
                    jc.usage();
            }
        } catch (MinimesosException mme) {
            LOGGER.error("ERROR: " + mme.getMessage());
            System.exit(1);
        } catch (Exception e) {
            LOGGER.error("ERROR: " + e.toString() );
            System.exit(1);
        }

    }

    /**
     * @param timeout in seconds
     */
    private static void doUp(int timeout) {

        String clusterId = MesosCluster.readClusterId();

        boolean exposedHostPorts = commandUp.isExposedHostPorts();
        String marathonImageTag = commandUp.getMarathonImageTag();
        String mesosImageTag = commandUp.getMesosImageTag();
        String zooKeeperImageTag = commandUp.getZooKeeperImageTag();

        if (clusterId == null) {

            DockerClient dockerClient = DockerClientFactory.build();

            ClusterArchitecture.Builder configBuilder = new ClusterArchitecture.Builder(dockerClient)
                    .withZooKeeper(zooKeeperImageTag)
                    .withMaster(zooKeeper -> new MesosMasterExtended(dockerClient, zooKeeper, MesosMaster.MESOS_MASTER_IMAGE, mesosImageTag, new TreeMap<>(), exposedHostPorts))
                    .withContainer(zooKeeper -> new Marathon(dockerClient, zooKeeper, marathonImageTag, exposedHostPorts), ClusterContainers.Filter.zooKeeper());

            // TODO: replace this file by richer YAML configuration. See #185
            List<String> agentResources = commandUp.loadAgentResources( MesosCluster.getMinimesosHostDir() );

            for (int i = 0; i < commandUp.getNumAgents(); i++) {
                String resources;
                if (agentResources != null && agentResources.size() > i) {
                    resources = agentResources.get(i);
                } else {
                    resources = MesosSlave.DEFAULT_RESOURCES;
                }
                configBuilder.withSlave(zooKeeper -> new MesosSlaveExtended(dockerClient, resources, "5051", zooKeeper, MesosSlave.MESOS_SLAVE_IMAGE, mesosImageTag));
            }

            if (commandUp.getStartConsul()) {
                configBuilder.withConsul();
            }

            MesosCluster cluster = new MesosCluster(configBuilder.build());
            cluster.start(timeout);
            cluster.writeClusterId();

        }

        Main.clusterId = MesosCluster.readClusterId();

    }

    private static void printInfo(CommandInfo cmd) throws Exception {
        String clusterId = MesosCluster.readClusterId();
        if (clusterId != null) {

            LOGGER.info("Minimesos cluster is running");
            LOGGER.info("Mesos version: " + MesosContainer.MESOS_IMAGE_TAG.substring(0, MesosContainer.MESOS_IMAGE_TAG.indexOf("-")));

            new PrintServiceInfo().setCmd(cmd).setClusterId(clusterId).call();

            // todo: properly add service url printouts
        } else {
            LOGGER.info("Minimesos cluster is not running");
        }
    }

    private static void printState(String agent) {
        String clusterId = MesosCluster.readClusterId();
        String stateInfo = (StringUtils.isEmpty(agent)) ? MesosCluster.getClusterStateInfo(clusterId) : MesosCluster.getContainerStateInfo(clusterId);
        if( stateInfo != null ) {
            LOGGER.info(stateInfo);
        } else {
            throw new MinimesosException("Did not find the cluster or requested container");
        }

    }

}
