package com.containersolutions.mesoshelloworld.executor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.mesos.ExecutorDriver;
import org.apache.mesos.Protos;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class FrameworkTask extends Thread {

    public static final String RESPONSE_STRING = "Hello world";

    private final Protos.TaskInfo task;
    private final ExecutorDriver driver;

    public FrameworkTask(ExecutorDriver driver, Protos.TaskInfo task) {
        this.driver = driver;
        this.task = task;
    }

    public void run() {
        try {
            Integer port = task.getDiscovery().getPorts().getPorts(0).getNumber();
            System.out.println("Starting webserver on port " + port);
            startWebServer(port);

            Protos.TaskStatus status = Protos.TaskStatus.newBuilder()
                .setTaskId(task.getTaskId())
                .setState(Protos.TaskState.TASK_RUNNING).build();

            driver.sendStatusUpdate(status);

            System.out.println("Running task " + task.getTaskId().getValue());
        } catch (Exception e) {
            System.out.println("Unable to start webserver:" + e);

            Protos.TaskStatus status = Protos.TaskStatus.newBuilder()
                .setTaskId(task.getTaskId())
                .setState(Protos.TaskState.TASK_FINISHED).build();

            driver.sendStatusUpdate(status);
        }
    }

    private void startWebServer(Integer port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new MyHandler());
        server.setExecutor(null); // creates a default executor
        server.start();
    }

    private class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {

            t.sendResponseHeaders(200, RESPONSE_STRING.length());
            OutputStream os = t.getResponseBody();
            os.write(RESPONSE_STRING.getBytes());
            os.close();
        }
    }

}