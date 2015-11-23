package com.containersol.minimesos.main;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.containersol.minimesos.MesosCluster;
import com.containersol.minimesos.mesos.MesosClusterConfig;
import com.containersol.minimesos.mesos.MesosContainer;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Main method for interacting with minimesos.
 */
public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static CommandUp commandUp;

    public static void main(String[] args) {
        JCommander jc = new JCommander();
        jc.setProgramName("minimesos");

        commandUp = new CommandUp();
        CommandDestroy commandDestroy = new CommandDestroy();
        CommandHelp commandHelp = new CommandHelp();
        CommandInfo commandInfo = new CommandInfo();

        jc.addCommand("up", commandUp);
        jc.addCommand("destroy", commandDestroy);
        jc.addCommand("help", commandHelp);
        jc.addCommand("info", commandInfo);
        try {
            jc.parse(args);
        } catch (ParameterException e) {
            jc.usage();
            return;
        }
        
        String clusterId = MesosCluster.readClusterId();
        MesosCluster.checkStateFile(clusterId);
        clusterId = MesosCluster.readClusterId();

        if (jc.getParsedCommand() == null) {
            if (clusterId != null) {
                MesosCluster.printServiceUrl(clusterId, "master", commandUp.isExposedHostPorts());
                MesosCluster.printServiceUrl(clusterId, "marathon", commandUp.isExposedHostPorts());
            } else {
                jc.usage();
            }
            return;
        }

        switch (jc.getParsedCommand()) {
            case "up":
                doUp();
                break;
            case "info":
                printInfo();
                break;
            case "destroy":
                MesosCluster.destroy();
                break;
            case "help":
                jc.usage();
        }
    }

    private static void doUp() {
        String clusterId = MesosCluster.readClusterId();
        if (clusterId == null) {

            MesosCluster cluster = new MesosCluster(
                    MesosClusterConfig.builder()
                            .slaveResources(new String[]{"ports(*):[33000-34000]"})
                            .mesosImageTag(commandUp.getMesosImageTag())
                            .exposedHostPorts(commandUp.isExposedHostPorts())
                            .build()
            );

            cluster.start();
            cluster.writeClusterId();

        }
        clusterId = MesosCluster.readClusterId();
        MesosCluster.printServiceUrl(clusterId, "master", commandUp.isExposedHostPorts());
        MesosCluster.printServiceUrl(clusterId, "marathon", commandUp.isExposedHostPorts());
    }

    private static void printInfo() {
        String clusterId = MesosCluster.readClusterId();
        if (clusterId != null) {
            LOGGER.info("Minimesos cluster is running");
            LOGGER.info("Mesos version: " + MesosContainer.MESOS_IMAGE_TAG.substring(0, MesosContainer.MESOS_IMAGE_TAG.indexOf("-")));
        } else {
            LOGGER.info("Minimesos cluster is not running");
        }
    }

}
