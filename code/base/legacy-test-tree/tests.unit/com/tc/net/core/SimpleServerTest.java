/*
 * Copyright (c) 2003-2006 Terracotta, Inc. All rights reserved.
 */
package com.tc.net.core;

import EDU.oswego.cs.dl.util.concurrent.LinkedQueue;
import EDU.oswego.cs.dl.util.concurrent.PooledExecutor;
import EDU.oswego.cs.dl.util.concurrent.SynchronizedRef;

import com.tc.net.TCSocketAddress;
import com.tc.net.protocol.EchoSink;
import com.tc.net.proxy.TCPProxy;
import com.tc.test.TCTestCase;

import java.io.File;

public class SimpleServerTest extends TCTestCase {
  private final boolean         useProxy  = false;
  private TCPProxy              proxy     = null;
  private int                   proxyPort = -1;
  private TCConnectionManager   connMgr;
  private SimpleServer          server;
  private final SynchronizedRef error     = new SynchronizedRef(null);

  protected void setUp() throws Exception {
    connMgr = new TCConnectionManagerFactory().getInstance();
    server = new SimpleServer(new EchoSink(true, new EchoSink.ErrorListener() {
      public void error(Throwable t) {
        setError(t);
      }
    }));
    server.start();

    if (useProxy) {
      int serverPort = server.getServerAddr().getPort();
      proxyPort = serverPort + 1;
      proxy = new TCPProxy(proxyPort, server.getServerAddr().getAddress(), serverPort, 0, true, new File(System
          .getProperty("java.io.tmpdir")));
      proxy.start();
    }
  }

  private void setError(Throwable t) {
    t.printStackTrace();
    error.set(t);
  }

  protected void tearDown() throws Exception {
    if (error.get() != null) {
      fail(error.get().toString());
    }
    if (proxy != null) {
      proxy.stop();
    }
    connMgr.shutdown();
    server.stop();
  }

  public void testLargeMessages() throws Exception {
    System.out.println("LARGE MESSAGES");
    runMultiClient(15, 5, 256, 2, 100, 150);
  }

  public void testSmallMessages() throws Exception {
    // these messages only take one buffer
    System.out.println("SMALLEST MESSAGES");
    runMultiClient(250, 20, 0, 100, 3, 5);
  }

  public void testKindaSmallMessages() throws Exception {
    // these messages span at least two byte buffers
    System.out.println("SMALL MESSAGES");
    runMultiClient(75, 10, 1, 100, 3, 7);
  }

  private void runMultiClient(int numClients, int maxConcurrent, final int dataSize, final int numToSend,
                              final int minDelay, final int maxDelay) throws Exception {
    long start = System.currentTimeMillis();

    try {
      final int numConcurrent = Math.min(maxConcurrent, numClients);
      PooledExecutor pool = new PooledExecutor(new LinkedQueue(), numConcurrent);
      pool.setKeepAliveTime(1000);

      final TCSocketAddress addr;
      if (proxy != null) {
        addr = new TCSocketAddress(proxyPort);
      } else {
        addr = new TCSocketAddress(server.getServerAddr().getPort());
      }

      for (int i = 0; i < numClients; i++) {
        pool.execute(new ClientTask(i, new VerifierClient(connMgr, addr, dataSize, numToSend, minDelay, maxDelay)));
      }

      pool.shutdownAfterProcessingCurrentlyQueuedTasks();
      pool.awaitTerminationAfterShutdown();
    } finally {
      System.err.println("Took " + (System.currentTimeMillis() - start) + " millis for test: " + getName());
    }
  }

  private class ClientTask implements Runnable {
    private final VerifierClient client;
    private final int            num;

    ClientTask(int num, VerifierClient client) {
      this.num = num;
      this.client = client;
    }

    public void run() {
      try {
        client.run();
      } catch (Throwable t) {
        setError(t);
      } finally {
        System.err.println(System.currentTimeMillis() + ": client " + num + " finished");
      }
    }

  }

}