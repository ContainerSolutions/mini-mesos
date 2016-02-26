package com.containersol.minimesos.config;

public class MarathonConfig extends GroovyBlock implements ContainerConfig {

    public static final String MARATHON_IMAGE = "mesosphere/marathon";
    public static final String MARATHON_IMAGE_TAG = "v0.13.0";
    public static final int MARATHON_PORT = 8080;

    String imageName     = MARATHON_IMAGE
    String imageTag      = MARATHON_IMAGE_TAG

    boolean exposedHostPort = false;

}
