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
package com.tc.objectserver.entity;

import org.terracotta.entity.EntityMessage;
import org.terracotta.entity.ServerEntityService;
import org.terracotta.exception.EntityAlreadyExistsException;
import org.terracotta.exception.EntityException;
import org.terracotta.exception.EntityNotFoundException;
import org.terracotta.exception.EntityVersionMismatchException;

import com.tc.object.EntityID;
import com.tc.objectserver.api.EntityManager;
import com.tc.objectserver.api.ManagedEntity;
import com.tc.objectserver.core.api.ITopologyEventCollector;
import com.tc.services.TerracottaServiceProviderRegistry;
import com.tc.util.Assert;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import org.terracotta.entity.EntityResponse;


public class EntityManagerImpl implements EntityManager {
  private final ConcurrentMap<EntityID, ManagedEntity> entities = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, ServerEntityService<EntityMessage, EntityResponse>> entityServices = new ConcurrentHashMap<>();

  private final ClassLoader creationLoader;
  private final TerracottaServiceProviderRegistry serviceRegistry;
  private final ClientEntityStateManager clientEntityStateManager;
  private final ITopologyEventCollector eventCollector;
  
  private final BiConsumer<EntityID, Long> noopLoopback;
  
  private final RequestProcessor processorPipeline;
  private boolean shouldCreateActiveEntities;

  public EntityManagerImpl(TerracottaServiceProviderRegistry serviceRegistry, 
      ClientEntityStateManager clientEntityStateManager, ITopologyEventCollector eventCollector, 
      RequestProcessor processor, BiConsumer<EntityID, Long> noopLoopback) {
    this.serviceRegistry = serviceRegistry;
    this.clientEntityStateManager = clientEntityStateManager;
    this.eventCollector = eventCollector;
    this.processorPipeline = processor;
    // By default, the server starts up in a passive mode so we will create passive entities.
    this.shouldCreateActiveEntities = false;
    this.creationLoader = Thread.currentThread().getContextClassLoader();
    this.noopLoopback = noopLoopback;
    ManagedEntity platform = createPlatformEntity();
    entities.put(platform.getID(), platform);
  }

  private ManagedEntity createPlatformEntity() {
    return new PlatformEntity(processorPipeline);
  }

  @Override
  public void enterActiveState() {
    // We can't enter active twice.
    Assert.assertFalse(this.shouldCreateActiveEntities);
    
    // Set the state of the manager.
    this.shouldCreateActiveEntities = true;
    processorPipeline.enterActiveState();
    // We can promote directly because this method is only called from PTH initialize 
    //  thus, this only happens once RTH is spun down and PTH is beginning to spin up.  We know the request queues are clear
    for (ManagedEntity entity : this.entities.values()) {
      entity.promoteEntity();
    }
  }

  @Override
  public void createEntity(EntityID id, long version, long consumerID) throws EntityException {
    // Valid entity versions start at 1.
    Assert.assertTrue(version > 0);
    ManagedEntity temp = new ManagedEntityImpl(id, version, noopLoopback, serviceRegistry.subRegistry(consumerID),
        clientEntityStateManager, this.eventCollector, processorPipeline, getVersionCheckedService(id, version), this.shouldCreateActiveEntities);
    if (entities.putIfAbsent(id, temp) != null) {
      throw new EntityAlreadyExistsException(id.getClassName(), id.getEntityName());
    }
  }

  @Override
  public void loadExisting(EntityID entityID, long recordedVersion, long consumerID, byte[] configuration) throws EntityException {
    // Valid entity versions start at 1.
    Assert.assertTrue(recordedVersion > 0);
    ManagedEntity temp = new ManagedEntityImpl(entityID, recordedVersion, noopLoopback, serviceRegistry.subRegistry(consumerID), clientEntityStateManager, this.eventCollector, processorPipeline, getVersionCheckedService(entityID, recordedVersion), this.shouldCreateActiveEntities);
    if (entities.putIfAbsent(entityID, temp) != null) {
      throw new IllegalStateException("Double create for entity " + entityID);
    }    
    temp.loadEntity(configuration);
  }

  @Override
  public void destroyEntity(EntityID id) throws EntityException {
    if (entities.remove(id) == null) {
      throw new EntityNotFoundException(id.getClassName(), id.getEntityName());
    }
  }

  @Override
  public Optional<ManagedEntity> getEntity(EntityID id, long version) throws EntityException {
    Assert.assertNotNull(id);
    if (EntityID.NULL_ID.equals(id)) {
//  short circuit for null entity, it's never here
      return Optional.empty();
    }
    // Valid entity versions start at 1.
    Assert.assertTrue(version > 0);
    ManagedEntity entity = entities.get(id);
    if (entity != null) {
      //  check the provided version against the version of the entity
      if (entity.getVersion() != version) {
        throw new EntityVersionMismatchException(id.getClassName(), id.getEntityName(), entity.getVersion(), version);
      }
    }
    return Optional.ofNullable(entity);
  }
  
  public Collection<ManagedEntity> getAll() {
    return new ArrayList<>(entities.values());
  }
  
  private ServerEntityService<EntityMessage, EntityResponse> getVersionCheckedService(EntityID entityID, long version) throws EntityVersionMismatchException {
    // Valid entity versions start at 1.
    Assert.assertTrue(version > 0);
    String typeName = entityID.getClassName();
    ServerEntityService<EntityMessage, EntityResponse> service = entityServices.get(typeName);
    if (service == null) {
      service = ServerEntityFactory.getService(typeName, this.creationLoader);
      // getService only fails to resolve by throwing.
      Assert.assertNotNull(service);
      Object oldService = entityServices.putIfAbsent(typeName, service);
      // This needs to be null or else there was some kind of unexpected concurrent access which would have caused failure or a duplicate entry.
      Assert.assertNull(oldService);
    }
    
    // We must have a service by now or we would have thrown.
    Assert.assertNotNull(service);
    long serviceVersion = service.getVersion();
    if (serviceVersion != version) {
      throw new EntityVersionMismatchException(typeName, entityID.getEntityName(), serviceVersion, version);
    }
    return service;
  }

  @Override
  public String toString() {
    return "EntityManagerImpl{" + "entities=" + entities.keySet() + '}';
  }
}

