/*
 * Copyright (c) 2003-2006 Terracotta, Inc. All rights reserved.
 */
package com.tc.async.api;

public interface StageMonitor {
  
  public void eventBegin(int queueDepth);

  public void flush();

  public Analysis analyze();

  public interface Analysis {
    
    public Number getEventCount();

    public Number getElapsedTime();
    
    public Number getEventsPerSecond();

    public Number getMinQueueDepth();

    public Number getMaxQueueDepth();

    public Number getAvgQueueDepth();
    
  }
}
