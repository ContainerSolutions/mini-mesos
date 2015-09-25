package org.apache.mesos.mini.util;

import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.http.HttpHost;
import org.apache.log4j.Logger;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static com.jayway.awaitility.Awaitility.await;

public class MesosClusterStateResponse implements Callable<Boolean> {
    private final Logger LOGGER = Logger.getLogger(MesosClusterStateResponse.class);
    private final String mesosMasterUrl;
    private final int expectedNumberOfSlaves;
    private final HttpHost proxy;

    public MesosClusterStateResponse(String mesosMasterUrl, int expectedNumberOfSlaves) {
        this.mesosMasterUrl = mesosMasterUrl;
        this.expectedNumberOfSlaves = expectedNumberOfSlaves;
        this.proxy = null;
    }

    public MesosClusterStateResponse(String mesosMasterUrl, int expectedNumberOfSlaves, HttpHost proxy) {
        this.mesosMasterUrl = mesosMasterUrl;
        this.expectedNumberOfSlaves = expectedNumberOfSlaves;
        this.proxy = proxy;

    }

    @Override
    public Boolean call() throws Exception {
        try {
            if (this.proxy != null) {
                Unirest.setProxy(this.proxy);
            }
            int activated_slaves = Unirest.get("http://" + mesosMasterUrl + "/state.json").asJson().getBody().getObject().getInt("activated_slaves");

            if (!(activated_slaves == expectedNumberOfSlaves)) {
                LOGGER.debug("Waiting for " + expectedNumberOfSlaves + " activated slaves - current number of activated slaves: " + activated_slaves);
                return false;
            }
        } catch (UnirestException e) {
            LOGGER.error(e.getMessage());
            LOGGER.debug("Polling MesosMaster state on host: \"" + mesosMasterUrl + "\"...");
            return false;
        } catch (Exception e) {
            LOGGER.error("An error occured while polling mesos master", e);
            return false;
        }
        return true;
    }

    public void waitFor() {

        await()
                .atMost(180, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(this);

        LOGGER.debug("MesosMaster state discovered successfully");
    }
}
