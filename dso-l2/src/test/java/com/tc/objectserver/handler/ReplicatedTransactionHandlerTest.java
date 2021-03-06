/*
 *
 *  The contents of this file are subject to the Terracotta Public License Version
 *  2.0 (the "License"); You may not use this file except in compliance with the
 *  License. You may obtain a copy of the License at
 *
 *  http://terracotta.org/legal/terracotta-public-license.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 *  the specific language governing rights and limitations under the License.
 *
 *  The Covered Software is Terracotta Core.
 *
 *  The Initial Developer of the Covered Software is
 *  Terracotta, Inc., a Software AG company
 *
 */
package com.tc.objectserver.handler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;

import com.tc.async.api.EventHandler;
import com.tc.async.api.EventHandlerException;
import com.tc.async.api.Sink;
import com.tc.async.api.SpecializedEventContext;
import com.tc.entity.NetworkVoltronEntityMessage;
import com.tc.entity.VoltronEntityAppliedResponse;
import com.tc.entity.VoltronEntityMessage;
import com.tc.entity.VoltronEntityReceivedResponse;
import com.tc.l2.msg.PassiveSyncMessage;
import com.tc.l2.msg.ReplicationMessage;
import com.tc.l2.state.StateManager;
import com.tc.net.ClientID;
import com.tc.net.ServerID;
import com.tc.net.groups.GroupManager;
import com.tc.net.protocol.tcm.MessageChannel;
import com.tc.net.protocol.tcm.TCMessageType;
import com.tc.object.ClientInstanceID;
import com.tc.object.EntityDescriptor;
import com.tc.object.EntityID;
import com.tc.object.net.DSOChannelManager;
import com.tc.object.tx.TransactionID;
import com.tc.objectserver.api.EntityManager;
import com.tc.objectserver.api.ManagedEntity;
import com.tc.objectserver.api.ServerEntityAction;
import com.tc.objectserver.api.ServerEntityRequest;
import com.tc.objectserver.core.api.ITopologyEventCollector;
import com.tc.objectserver.entity.ClientEntityStateManager;
import com.tc.objectserver.entity.PlatformEntity;
import com.tc.objectserver.persistence.EntityPersistor;
import com.tc.objectserver.persistence.TransactionOrderPersistor;
import com.tc.services.TerracottaServiceProviderRegistry;
import com.tc.stats.Stats;
import com.tc.util.Assert;
import java.nio.ByteBuffer;

import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.Mockito;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


public class ReplicatedTransactionHandlerTest {
  private TerracottaServiceProviderRegistry terracottaServiceProviderRegistry;
  private EntityPersistor entityPersistor;
  private TransactionOrderPersistor transactionOrderPersistor;
  private ReplicatedTransactionHandler rth;
  private ClientID source;
  private ForwardingSink loopbackSink;
  private ClientEntityStateManager clientEntityStateManager;
  private ITopologyEventCollector eventCollector;
  private StateManager stateManager;
  private EntityManager entityManager;
  private GroupManager groupManager;
  
  private long rid = 0;
  
  @Before
  public void setUp() throws Exception {
    this.terracottaServiceProviderRegistry = mock(TerracottaServiceProviderRegistry.class);
    this.entityPersistor = mock(EntityPersistor.class);
    this.transactionOrderPersistor = mock(TransactionOrderPersistor.class);
    this.stateManager = mock(StateManager.class);
    this.entityManager = mock(EntityManager.class);
    this.groupManager = mock(GroupManager.class);
    when(entityManager.getEntity(Matchers.eq(PlatformEntity.PLATFORM_ID), Matchers.eq(PlatformEntity.VERSION))).thenReturn(Optional.of(mock(ManagedEntity.class)));
    this.rth = new ReplicatedTransactionHandler(stateManager, this.transactionOrderPersistor, this.entityManager, this.entityPersistor, this.groupManager);
    this.source = mock(ClientID.class);
    
    MessageChannel messageChannel = mock(MessageChannel.class);
    when(messageChannel.createMessage(TCMessageType.VOLTRON_ENTITY_APPLIED_RESPONSE)).thenReturn(mock(VoltronEntityAppliedResponse.class));
    when(messageChannel.createMessage(TCMessageType.VOLTRON_ENTITY_RECEIVED_RESPONSE)).thenReturn(mock(VoltronEntityReceivedResponse.class));
    
    DSOChannelManager channelManager = mock(DSOChannelManager.class);
    when(channelManager.getActiveChannel(this.source)).thenReturn(messageChannel);
    
    this.loopbackSink = new ForwardingSink(this.rth.getEventHandler());
    
    this.eventCollector = mock(ITopologyEventCollector.class);
    channelManager.addEventListener(clientEntityStateManager);
  }
  
  @Test
  public void testEntityNoIgnoresDuringSyncOfKey() throws Exception {
    EntityID eid = new EntityID("foo", "bar");
    EntityDescriptor descriptor = new EntityDescriptor(eid, ClientInstanceID.NULL_ID, 1);
    ServerID sid = new ServerID("test", "test".getBytes());
    ManagedEntity entity = mock(ManagedEntity.class);
    ReplicationMessage msg = mock(ReplicationMessage.class);
    int rand = 1;
    when(msg.getConcurrency()).thenReturn(rand);
    when(msg.getType()).thenReturn(ReplicationMessage.REPLICATE);
    when(msg.getReplicationType()).thenReturn(ReplicationMessage.ReplicationType.INVOKE_ACTION);
    when(msg.getEntityID()).thenReturn(eid);
    when(msg.messageFrom()).thenReturn(sid);
    when(msg.getEntityDescriptor()).thenReturn(descriptor);
    when(msg.getOldestTransactionOnClient()).thenReturn(TransactionID.NULL_ID);
    when(this.entityManager.getEntity(Matchers.any(), Matchers.anyInt())).thenReturn(Optional.of(entity));
    Mockito.doAnswer(invocation->{
      ((ServerEntityRequest)invocation.getArguments()[0]).complete(new byte[0]);
      return null;
    }).when(entity).addInvokeRequest(Matchers.any(), Matchers.any(), Matchers.eq(rand));
    this.loopbackSink.addSingleThreaded(PassiveSyncMessage.createStartSyncMessage());
    this.loopbackSink.addSingleThreaded(PassiveSyncMessage.createStartEntityMessage(eid, 1, new byte[0]));
    this.loopbackSink.addSingleThreaded(PassiveSyncMessage.createStartEntityKeyMessage(eid, 1, rand));
    this.loopbackSink.addSingleThreaded(msg);
    this.loopbackSink.addSingleThreaded(PassiveSyncMessage.createEndEntityKeyMessage(eid, 1, rand));
    this.loopbackSink.addSingleThreaded(PassiveSyncMessage.createEndEntityMessage(eid, 1));
    this.loopbackSink.addSingleThreaded(PassiveSyncMessage.createEndSyncMessage());

    verify(entity).addInvokeRequest(Matchers.any(), Matchers.any(), Matchers.eq(rand));
    verify(groupManager).sendTo(Matchers.eq(sid), Matchers.any());
  }  
  
  @Test
  public void testEntityGetsConcurrencyKey() throws Exception {
    EntityID eid = new EntityID("foo", "bar");
    EntityDescriptor descriptor = new EntityDescriptor(eid, ClientInstanceID.NULL_ID, 1);
    ServerID sid = new ServerID("test", "test".getBytes());
    ManagedEntity entity = mock(ManagedEntity.class);
    ReplicationMessage msg = mock(ReplicationMessage.class);
    int rand = new Random().nextInt();
    when(msg.getConcurrency()).thenReturn(rand);
    when(msg.getType()).thenReturn(ReplicationMessage.REPLICATE);
    when(msg.getReplicationType()).thenReturn(ReplicationMessage.ReplicationType.INVOKE_ACTION);
    when(msg.getEntityID()).thenReturn(eid);
    when(msg.messageFrom()).thenReturn(sid);
    when(msg.getEntityDescriptor()).thenReturn(descriptor);
    when(msg.getOldestTransactionOnClient()).thenReturn(TransactionID.NULL_ID);
    when(this.entityManager.getEntity(Matchers.any(), Matchers.anyInt())).thenReturn(Optional.of(entity));
    Mockito.doAnswer(invocation->{
      ((ServerEntityRequest)invocation.getArguments()[0]).complete(new byte[0]);
      return null;
    }).when(entity).addInvokeRequest(Matchers.any(), Matchers.any(), Matchers.eq(rand));
    this.loopbackSink.addSingleThreaded(PassiveSyncMessage.createStartSyncMessage());
    this.loopbackSink.addSingleThreaded(PassiveSyncMessage.createEndSyncMessage());
    this.loopbackSink.addSingleThreaded(msg);
    verify(entity).addInvokeRequest(Matchers.any(), Matchers.any(), Matchers.eq(rand));
    verify(groupManager).sendTo(Matchers.eq(sid), Matchers.any());
  }
  
  @Test
  public void testTestDefermentDuringSync() throws Exception {
    EntityID eid = new EntityID("foo", "bar");
    long VERSION = 1;
    EntityDescriptor descriptor = new EntityDescriptor(eid, ClientInstanceID.NULL_ID, 1);
    ServerID sid = new ServerID("test", "test".getBytes());
    ManagedEntity entity = mock(ManagedEntity.class);
    when(this.entityManager.getEntity(Matchers.eq(eid), Matchers.eq(VERSION))).thenReturn(Optional.of(entity));
    Mockito.doAnswer(invocation->{
      ServerEntityRequest req = (ServerEntityRequest)invocation.getArguments()[0];
      req.complete(new byte[0]);
      verifySequence(req, (byte[])invocation.getArguments()[1], (int)invocation.getArguments()[2]);
      return null;
    }).when(entity).addInvokeRequest(Matchers.any(), Matchers.any(), Matchers.anyInt());
    Mockito.doAnswer(invocation->{
      ServerEntityRequest req = (ServerEntityRequest)invocation.getArguments()[0];
      req.complete(new byte[0]);
      verifySequence(req, (byte[])invocation.getArguments()[1], (int)invocation.getArguments()[2]);
      return null;
    }).when(entity).addSyncRequest(Matchers.any(), Matchers.any(), Matchers.anyInt());
    mockPassiveSync(rth);
  }
  
  private ServerEntityRequest last;
  private int lastSid = 0;
  private int concurrency = 0;
  private boolean invoked = false;
  
  private void verifySequence(ServerEntityRequest req, byte[] payload, int c) {
    switch(req.getAction()) {
      case RECEIVE_SYNC_ENTITY_START:
        Assert.assertNull(last);
        last = req;
        Assert.assertEquals(0, concurrency);
        break;
      case RECEIVE_SYNC_ENTITY_KEY_START:
        Assert.assertTrue(last.getAction() == ServerEntityAction.RECEIVE_SYNC_ENTITY_START || last.getAction() == ServerEntityAction.RECEIVE_SYNC_ENTITY_KEY_END);
        last = req;
        Assert.assertEquals(0, concurrency);
        concurrency = c;
        break;
      case RECEIVE_SYNC_PAYLOAD:
        Assert.assertEquals(last.getAction(), ServerEntityAction.RECEIVE_SYNC_ENTITY_KEY_START);
        last = req;
        Assert.assertEquals(concurrency, c);
  //  make sure no invokes deferred to the end of a concurrency key
        Assert.assertFalse(invoked);
        break;
      case RECEIVE_SYNC_ENTITY_KEY_END:
        Assert.assertEquals(last.getAction(), ServerEntityAction.RECEIVE_SYNC_PAYLOAD);
        last = req;
        Assert.assertEquals(concurrency, c);
        concurrency = 0;
        break;
      case RECEIVE_SYNC_ENTITY_END:
        Assert.assertEquals(last.getAction(), ServerEntityAction.RECEIVE_SYNC_ENTITY_KEY_END);
        last = req;
        invoked = false;
        break;
      case INVOKE_ACTION:
        Assert.assertTrue(last.getAction() == ServerEntityAction.RECEIVE_SYNC_PAYLOAD);
        int sid = ByteBuffer.wrap(payload).getInt();
        Assert.assertEquals(lastSid + 1, sid);
        Assert.assertEquals(concurrency, c);
        lastSid = sid;
  //  make sure no invokes deferred to the end
        invoked = true;
  //  don't sert last
        break;
      default:
        break;
    }
  }
  
  private void mockPassiveSync(ReplicatedTransactionHandler rth) throws EventHandlerException {
//  start passive sync
    EntityID eid = new EntityID("foo", "bar");
    long VERSION = 1;
    byte[] config = new byte[0];
    send(PassiveSyncMessage.createStartSyncMessage());
    send(PassiveSyncMessage.createStartEntityMessage(eid, VERSION, config));
    send(PassiveSyncMessage.createStartEntityKeyMessage(eid, VERSION, 1));
    send(PassiveSyncMessage.createPayloadMessage(eid, VERSION, 1, config));
    send(PassiveSyncMessage.createEndEntityKeyMessage(eid, VERSION, 1));
    send(PassiveSyncMessage.createStartEntityKeyMessage(eid, VERSION, 2));
    send(PassiveSyncMessage.createPayloadMessage(eid, VERSION, 2, config));
    send(PassiveSyncMessage.createEndEntityKeyMessage(eid, VERSION, 2));  
    send(PassiveSyncMessage.createStartEntityKeyMessage(eid, VERSION, 3));
    send(PassiveSyncMessage.createPayloadMessage(eid, VERSION, 3, config));
    send(PassiveSyncMessage.createEndEntityKeyMessage(eid, VERSION, 3));  
    send(PassiveSyncMessage.createStartEntityKeyMessage(eid, VERSION, 4));
//  defer a few replicated messages with sequence as payload
    send(createMockReplicationMessage(eid, VERSION, ByteBuffer.wrap(new byte[Integer.BYTES]).putInt(1).array(), 4));
    send(createMockReplicationMessage(eid, VERSION, ByteBuffer.wrap(new byte[Integer.BYTES]).putInt(2).array(), 4));
    send(createMockReplicationMessage(eid, VERSION, ByteBuffer.wrap(new byte[Integer.BYTES]).putInt(3).array(), 4));
    send(PassiveSyncMessage.createPayloadMessage(eid, 1, 4, config));
//  defer a few replicated messages with sequence as payload
    send(createMockReplicationMessage(eid, VERSION, ByteBuffer.wrap(new byte[Integer.BYTES]).putInt(4).array(), 4));
    send(createMockReplicationMessage(eid, VERSION, ByteBuffer.wrap(new byte[Integer.BYTES]).putInt(5).array(), 4));
    send(createMockReplicationMessage(eid, VERSION, ByteBuffer.wrap(new byte[Integer.BYTES]).putInt(6).array(), 4));
    send(PassiveSyncMessage.createEndEntityKeyMessage(eid, 1, 4)); 
    send(PassiveSyncMessage.createEndEntityMessage(eid, VERSION));
    send(PassiveSyncMessage.createEndSyncMessage());
  }

  private long send(ReplicationMessage msg) throws EventHandlerException {
    msg.setReplicationID(rid++);
    if (!msg.getEntityID().equals(EntityID.NULL_ID)) {
      loopbackSink.addSingleThreaded(msg);
    }
    return rid;
  }
  @After
  public void tearDown() throws Exception {
    this.rth.getEventHandler().destroy();
  }
  
  private ReplicationMessage createMockReplicationMessage(EntityID eid, long VERSION, byte[] payload, int concurrency) {
    return new ReplicationMessage(new EntityDescriptor(eid, ClientInstanceID.NULL_ID, VERSION), 
        source, TransactionID.NULL_ID, TransactionID.NULL_ID, ReplicationMessage.ReplicationType.INVOKE_ACTION, payload, concurrency);
  }

  /**
   * This is pulled out as its own helper since the mocked EntityIDs aren't .equals() each other so using the same
   * instance gives convenient de facto equality.
   */
  private EntityID createMockEntity(String entityName) {
    EntityID entityID = mock(EntityID.class);
    // We will use the TestEntity since we only want to proceed with the check, not actually create it (this is from entity-api).
    when(entityID.getClassName()).thenReturn("org.terracotta.TestEntity");
    when(entityID.getEntityName()).thenReturn(entityName);
    return entityID;
  }

  private NetworkVoltronEntityMessage createMockRequest(VoltronEntityMessage.Type type, EntityID entityID, TransactionID transactionID) {
    NetworkVoltronEntityMessage request = mock(NetworkVoltronEntityMessage.class);
    when(request.getSource()).thenReturn(this.source);
    when(request.getVoltronType()).thenReturn(type);
    EntityDescriptor entityDescriptor = mock(EntityDescriptor.class);
    when(entityDescriptor.getClientSideVersion()).thenReturn((long) 1);
    when(entityDescriptor.getEntityID()).thenReturn(entityID);
    when(request.getEntityDescriptor()).thenReturn(entityDescriptor);
    when(request.getTransactionID()).thenReturn(transactionID);
    when(request.getOldestTransactionOnClient()).thenReturn(new TransactionID(0));
    return request;
  }


  private static abstract class NoStatsSink<T> implements Sink<T> {
    @Override
    public void enableStatsCollection(boolean enable) {
      throw new UnsupportedOperationException();
    }
    @Override
    public boolean isStatsCollectionEnabled() {
      throw new UnsupportedOperationException();
    }
    @Override
    public Stats getStats(long frequency) {
      throw new UnsupportedOperationException();
    }
    @Override
    public Stats getStatsAndReset(long frequency) {
      throw new UnsupportedOperationException();
    }
    @Override
    public void resetStats() {
      throw new UnsupportedOperationException();
    }
    @Override
    public void addSpecialized(SpecializedEventContext specialized) {
      throw new UnsupportedOperationException();
    }
    @Override
    public int size() {
      throw new UnsupportedOperationException();
    }
    @Override
    public void clear() {
      throw new UnsupportedOperationException();
    }
  }


  private static class RunnableSink extends NoStatsSink<Runnable> {
    private final Queue<Runnable> runnableQueue;

    public RunnableSink() {
      this.runnableQueue = new LinkedList<>();
    }

    public void runUntilEmpty() {
      while (!this.runnableQueue.isEmpty()) {
        Runnable task = this.runnableQueue.poll();
        task.run();
      }
    }
    @Override
    public void addSingleThreaded(Runnable context) {
      throw new UnsupportedOperationException();
    }
    @Override
    public void addMultiThreaded(Runnable context) {
      this.runnableQueue.add(context);
    }

    @Override
    public void setClosed(boolean closed) {
      throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  }


  private static class ForwardingSink extends NoStatsSink<ReplicationMessage> {
    private final EventHandler<ReplicationMessage> target;

    public ForwardingSink(EventHandler<ReplicationMessage> voltronMessageHandler) {
      this.target = voltronMessageHandler;
    }

    @Override
    public void addSingleThreaded(ReplicationMessage context) {
      try {
        this.target.handleEvent(context);
      } catch (EventHandlerException e) {
        Assert.fail();
      }
    }
    @Override
    public void addMultiThreaded(ReplicationMessage context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setClosed(boolean closed) {
      throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }
    
    
}
}
