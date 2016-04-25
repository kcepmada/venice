package com.linkedin.venice.router.api;

import com.linkedin.ddsstorage.base.concurrency.AsyncFuture;
import com.linkedin.ddsstorage.base.concurrency.AsyncPromise;
import com.linkedin.ddsstorage.netty3.misc.BasicHttpRequest;
import com.linkedin.ddsstorage.router.api.PartitionDispatchHandler;
import com.linkedin.ddsstorage.router.api.Scatter;
import com.linkedin.ddsstorage.router.api.ScatterGatherRequest;
import com.linkedin.venice.HttpConstants;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.meta.Instance;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;


/**
 * Created by mwise on 3/9/16.
 */
public class VeniceDispatcher implements PartitionDispatchHandler<Instance, VeniceStoragePath, RouterKey>, Closeable{

  private static final String HTTP = "http://";

  private static final Logger logger = Logger.getLogger(VeniceDispatcher.class);

  // see: https://hc.apache.org/httpcomponents-asyncclient-dev/quickstart.html
  private final Map<Instance, CloseableHttpAsyncClient> clientPool;

  // key is (resource + "_" + partition)
  private final ConcurrentMap<String, Long> offsets = new ConcurrentHashMap<>();
  private final VeniceHostHealth healthMontior;

  // How many offsets behind can a storage node be for a partition and still be considered 'caught up'
  private long acceptableOffsetLag = 0; /* TODO: make this configurable for streaming use-case */

  public VeniceDispatcher(VeniceHostHealth healthMonitor){
    clientPool = new ConcurrentHashMap<>();
    this.healthMontior = healthMonitor;
  }

  @Override
  public void dispatch(
      Scatter<Instance, VeniceStoragePath, RouterKey> scatter,
      ScatterGatherRequest<Instance, RouterKey> part,
      VeniceStoragePath path,
      BasicHttpRequest request,
      AsyncPromise<Instance> hostSelected,
      AsyncPromise<List<HttpResponse>> responseFuture,
      AsyncPromise<HttpResponseStatus> retryFuture,
      AsyncFuture<Void> timeoutFuture,
      Executor contextExecutor) {

    Instance host;
    try {
      int hostCount = part.getHosts().size();
      host = part.getHosts().get( ((int)System.currentTimeMillis()) % hostCount );  //cheap random host selection
      hostSelected.setSuccess(host);
    } catch (Exception e) {
      hostSelected.setFailure(e);
      throw new VeniceException("Failed to route request to a host");
    }
    logger.debug("Routing request to host: " + host.getHost() + ":" + host.getPort());

    CloseableHttpAsyncClient httpClient = clientPool.computeIfAbsent(host, new Function<Instance, CloseableHttpAsyncClient>() {
      @Override
      public CloseableHttpAsyncClient apply(Instance instance) {
        CloseableHttpAsyncClient httpClient = HttpAsyncClients.custom().setConnectionReuseStrategy(new DefaultConnectionReuseStrategy())  //Supports connection re-use if able
            .setMaxConnPerRoute(2) // concurrent execute commands beyond this limit get queued internally by the client
            .setMaxConnTotal(2).build();
        httpClient.start();
        return httpClient;
      }
    });

    String requestPath = path.getLocation();
    logger.debug("Using request path: " + requestPath);

    //  http://host:port/path
    String address = HTTP + host.getHost() + ":" + host.getPort() + "/" + requestPath;
    final HttpGet requestToNode = new HttpGet(address);
    httpClient.execute(requestToNode, new FutureCallback<org.apache.http.HttpResponse>() {

      @Override
      public void completed(org.apache.http.HttpResponse result) {
        Iterator<String> partitionNames = part.getPartitionsNames().iterator();
        String partitionName = partitionNames.next();
        if (partitionNames.hasNext()){
          logger.error("There must be only one partition in a request, handling request as if there is only one partition");
        }
        long offset = Long.parseLong(result.getFirstHeader(HttpConstants.VENICE_OFFSET).getValue());
        String offsetKey = path.getResourceName() + "_" + partitionName;
        if (offsets.containsKey(offsetKey)
            && offset + acceptableOffsetLag < offsets.get(offsetKey) ) {
          healthMontior.setHostAsSlow(host, partitionName);
          contextExecutor.execute(() -> {
            // Triggers an immediate router retry excluding the host we selected.
            retryFuture.setSuccess(HttpResponseStatus.SERVICE_UNAVAILABLE);
          });
          return;
        }
        offsets.put(offsetKey, offset);
        int responseStatus = result.getStatusLine().getStatusCode();
        HttpResponse response;
        switch (responseStatus){
          case HttpStatus.SC_OK:
            response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            break;
          case HttpStatus.SC_NOT_FOUND:
            response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
            break;
          case HttpStatus.SC_INTERNAL_SERVER_ERROR:
          default: //Path Parser will throw BAD_REQUEST responses.
            response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY);
        }

        try (InputStream contentStream = result.getEntity().getContent()) {
          response.setContent(ChannelBuffers.wrappedBuffer(IOUtils.toByteArray(contentStream)));
        } catch (IOException e) {
          completeWithError(HttpResponseStatus.INTERNAL_SERVER_ERROR, e);
          return;
        }
        HttpHeaders.setContentLength(response, response.getContent().readableBytes());
        HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_TYPE, HttpConstants.APPLICATION_OCTET);
        HttpHeaders.setHeader(response, HttpConstants.VENICE_STORE_VERSION, path.getVersionNumber());
        HttpHeaders.setHeader(response, HttpConstants.VENICE_PARTITION, numberFromPartitionName(partitionName));
        contextExecutor.execute(() -> {
          responseFuture.setSuccess(Collections.singletonList(response));
        });
      }

      @Override
      public void failed(Exception ex) {
        completeWithError(HttpResponseStatus.INTERNAL_SERVER_ERROR, ex);
      }

      @Override
      public void cancelled() {
        completeWithError(HttpResponseStatus.INTERNAL_SERVER_ERROR,
            new VeniceException("Request to storage node was cancelled"));
      }

      private void completeWithError(HttpResponseStatus status, Throwable e) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        response.setContent(ChannelBuffers.wrappedBuffer(e.getMessage().getBytes(StandardCharsets.UTF_8)));
        HttpHeaders.setContentLength(response, response.getContent().readableBytes());
        HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_TYPE, HttpConstants.TEXT_PLAIN);
        contextExecutor.execute(() -> {
          responseFuture.setSuccess(Collections.singletonList(response));
        });
      }
    });
  }

  public void close(){
    for (CloseableHttpAsyncClient client : clientPool.values()){
      try {
        client.close();
      } catch (IOException e) {
        logger.error("Error closing an async http client", e);
      }
    }
    clientPool.clear();
  }

  protected static String numberFromPartitionName(String partitionName){
    return partitionName.substring(partitionName.lastIndexOf("_")+1);
  }
}
