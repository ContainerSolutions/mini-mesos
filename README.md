# mini-mesos

Testing infrastructure for Mesos frameworks. 

## Overview

The basic idea is to provide simple to use test utilities to setup a Mesos cluster that can be tested against.

The Mesos cluster setup is a docker container running zookeeper with one master and a configurable number of slaves. 
The master and slaves are launched directly inside the container.

The test utilities provide mechanisms to make arbitrary docker images available inside that Mesos cluster container.
This way executors themselves could be launched as docker containers (inside that Mesos cluster container, Docker-in-docker). 

A possible testing scenario could be:
 
 1. In the test setup build an executor image and launch the Mesos cluster container with that executor image being available inside.
 2. Call the scheduler directly from your test and point to zookeeper to detect the master or passing the master URL directly.
 3. The scheduler launches a task (which runs the executor as a docker container) on a suitable slave.
 4. Poll the state of the Mesos cluster to verify that you framework is running
 5. The test utilities take care of stopping and removing the used Mesos cluster container...

![Mini Mesos](/docs/mini-mesos.gif?raw=true "Mini Mesos")

![Creative Commons Licence](/docs/cc-cc.png "Creative Commons Licence") Licenced under CC BY [remember to play](http://remembertoplay.co/) in collaboration with [Container Solutions](http://www.container-solutions.com/)

## Running on a mac

Create a docker machine, make sure its environment variables are
visible to the test, ensure the docker containers' IP addresses are
available on the host, and then build and run the tests:

```
$ docker-machine create -d virtualbox --virtualbox-memory 4096 mini-mesos
$ eval $(docker-machine env mini-mesos)
$ sudo route delete 172.17.0.0/16; sudo route -n add 172.17.0.0/16 $(docker-machine ip mini-mesos)
$ mvn clean verify
```

In Idea, add the `docker-machine env` variables to the idea junit testing dialog. E.g.
```
DOCKER_TLS_VERIFY=1
DOCKER_HOST=tcp://192.168.99.100:2376
DOCKER_CERT_PATH=/home/user/.docker/machine/machines/mini-mesos
```

## Usage

That project provides a JUnit Rule which can be included like:



```java
public class MesosClusterTest {
    @ClassRule
    public static MesosCluster cluster = new MesosCluster(MesosClusterConfig.builder()
            .numberOfSlaves(3)
            .slaveResources(new String[]{"ports(*):[9200-9200,9300-9300]","ports(*):[9201-9201,9301-9301]","ports(*):[9202-9202,9302-9302]"})
            .privateRegistryPort(15000) // Currently you have to choose an unused port by yourself (e.g. unique per Jenkins-Job)
            .build());
            
            
    @Test
    public void mesosClusterCanBeStarted() throws Exception {
        JSONObject stateInfo = cluster.getStateInfoJSON();
    
        Assert.assertEquals(3, stateInfo.getInt("activated_slaves"));
        
        Assert.assertTrue(cluster.getMesosMasterURL().contains(":5050"));
     }
}
```

In this snippet we're configuring the Mesos cluster to start 3 slaves with different resources. 

Other test cases could call the scheduler directly...

## Pushing your own images into the private registry
Once the mesos cluster is running, you will probably want to push your own images. See the example below:

```
    @Test
    public void testInstantiate() throws InterruptedException, UnirestException {
        DockerClient dockerClient = getDockerClient();

        HelloWorldContainer helloWorldContainer = new HelloWorldContainer(dockerClient);
        cluster.addAndStartContainer(helloWorldContainer); // This will automatically shut down your container.
        //helloWorld.start(); // Alternatively, you can start and shutdown the container yourself.
        //helloWorld.remove();

        String ipAddress = helloWorldContainer.getIpAddress();
        String url = "http://" + ipAddress + ":" + HelloWorldContainer.PORT;
        Assert.assertEquals(200, Unirest.get(url).asString().getStatus());
    }

    class HelloWorldContainer extends AbstractContainer {

        public static final String HELLO_WORLD_IMAGE = "tutum/hello-world";
        public static final int PORT = 80;

        protected HelloWorldContainer(DockerClient dockerClient) {
            super(dockerClient);
        }

        @Override
        protected void pullImage() {
            dockerUtil.pullImage(HELLO_WORLD_IMAGE, "latest");
        }

        @Override
        protected CreateContainerCmd dockerCommand() {
            return dockerClient.createContainerCmd(HELLO_WORLD_IMAGE).withPortBindings(PortBinding.parse("0.0.0.0:" + PORT + ":" + PORT));
        }
    }
```
