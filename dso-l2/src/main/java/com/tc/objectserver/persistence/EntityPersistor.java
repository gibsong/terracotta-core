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
package com.tc.objectserver.persistence;

import com.tc.net.ClientID;
import com.tc.object.EntityID;
import com.tc.objectserver.persistence.EntityData.JournalEntry;
import com.tc.util.Assert;

import java.util.Collection;
import java.util.List;
import java.util.Vector;

import org.terracotta.exception.EntityException;
import org.terracotta.persistence.IPersistentStorage;
import org.terracotta.persistence.KeyValueStorage;


/**
 * Stores the information relating to the entities currently alive on the platform into persistent storage.
 */
public class EntityPersistor {
  private static final String ENTITIES_ALIVE = "entities_alive";
  private static final String JOURNAL_CONTAINER = "journal_container";
  private static final String COUNTERS = "counters";
  private static final String COUNTERS_CONSUMER_ID = "counters:consumerID";

  private final KeyValueStorage<EntityData.Key, EntityData.Value> entities;
  private final KeyValueStorage<ClientID, List<EntityData.JournalEntry>> entityLifeJournal;
  private final KeyValueStorage<String, Long> counters;

  @SuppressWarnings({ "unchecked", "rawtypes" })
  public EntityPersistor(IPersistentStorage storageManager) {
    this.entities = storageManager.getKeyValueStorage(ENTITIES_ALIVE, EntityData.Key.class, EntityData.Value.class);
    this.entityLifeJournal = storageManager.getKeyValueStorage(JOURNAL_CONTAINER, ClientID.class, (Class)List.class);
    this.counters = storageManager.getKeyValueStorage(COUNTERS, String.class, Long.class);
    // Make sure that the consumerID is initialized to 1 (0 reserved for platform).
    if (!this.counters.containsKey(COUNTERS_CONSUMER_ID)) {
      this.counters.put(COUNTERS_CONSUMER_ID, new Long(1));
    }
  }

  public void clear() {
    this.entities.clear();
    this.entityLifeJournal.clear();
    this.counters.clear();
    if (!this.counters.containsKey(COUNTERS_CONSUMER_ID)) {
      this.counters.put(COUNTERS_CONSUMER_ID, new Long(1));
    }
  }

  @SuppressWarnings("deprecation")
  public Collection<EntityData.Value> loadEntityData() {
    return this.entities.values();
  }

  public boolean containsEntity(ClientID clientID, long transactionID, long oldestTransactionOnClient, EntityID id) {
    boolean doesContain = false;
    // First, check to see if this is something known to the journal.
    EntityData.JournalEntry entry = getEntryForTransaction(clientID, transactionID);
    if (null != entry) {
      // This is in the journal so just get our answer from there.
      doesContain = entry.didFind;
    } else {
      // This is new so look up the answer and store it in the journal.
      EntityData.Key key = new EntityData.Key();
      key.className = id.getClassName();
      key.entityName = id.getEntityName();
      // Make sure that the EntityID makes sense.
      Assert.assertNotNull(key.className);
      Assert.assertNotNull(key.entityName);
      doesContain = this.entities.containsKey(key);
      addToJournal(clientID, transactionID, oldestTransactionOnClient, EntityData.Operation.DOES_EXIST, null, doesContain, null);
    }
    return doesContain;
  }

  /**
   * Consults the journal to see if there already was an entity created with this transactionID from the referenced clientID.
   * If an attempt was made, true is returned (on success) or EntityException is thrown (if it was a failure).
   * False is returned if this clientID and transactionID seem new.
   */
  public boolean wasEntityCreatedInJournal(ClientID clientID, long transactionID) throws EntityException {
    boolean didSucceed = false;
    EntityData.JournalEntry entry = getEntryForTransaction(clientID, transactionID);
    if (null != entry) {
      if (null == entry.failure) {
        didSucceed = true;
      } else {
        throw entry.failure;
      }
    }
    return didSucceed;
  }

  public void entityCreateFailed(ClientID clientID, long transactionID, long oldestTransactionOnClient, EntityException error) {
    addToJournal(clientID, transactionID, oldestTransactionOnClient, EntityData.Operation.CREATE, null, false, error);
  }

  public void entityCreated(ClientID clientID, long transactionID, long oldestTransactionOnClient, EntityID id, long version, long consumerID, byte[] configuration) {
    addNewEntityToMap(id, version, consumerID, configuration);
    
    // Record this in the journal - null error on success.
    addToJournal(clientID, transactionID, oldestTransactionOnClient, EntityData.Operation.CREATE, null, false, null);
  }

  public void entityCreatedNoJournal(EntityID id, long version, long consumerID, byte[] configuration) {
    addNewEntityToMap(id, version, consumerID, configuration);
    // (Note that we don't store this into the journal - this is used for passive sync).
  }

  /**
   * Consults the journal to see if there already was an entity destroyed with this transactionID from the referenced clientID.
   * If an attempt was made, true is returned (on success) or EntityAlreadyExistsException is thrown.
   * False is returned if this clientID and transactionID seem new.
   */
  public boolean wasEntityDestroyedInJournal(ClientID clientID, long transactionID) throws EntityException {
    boolean didSucceed = false;
    EntityData.JournalEntry entry = getEntryForTransaction(clientID, transactionID);
    if (null != entry) {
      if (null == entry.failure) {
        didSucceed = true;
      } else {
        throw entry.failure;
      }
    }
    return didSucceed;
  }

  public void entityDestroyFailed(ClientID clientID, long transactionID, long oldestTransactionOnClient, EntityException error) {
    addToJournal(clientID, transactionID, oldestTransactionOnClient, EntityData.Operation.DESTROY, null, false, error);
  }

  public void entityDestroyed(ClientID clientID, long transactionID, long oldestTransactionOnClient, EntityID id) {
    EntityData.Key key = new EntityData.Key();
    key.className = id.getClassName();
    key.entityName = id.getEntityName();
    Assert.assertTrue(this.entities.containsKey(key));
    this.entities.remove(key);
    
    // Record this in the journal - null error on success.
    addToJournal(clientID, transactionID, oldestTransactionOnClient, EntityData.Operation.DESTROY, null, false, null);
  }

  public byte[] reconfiguredResultInJournal(ClientID clientID, long transactionID) throws EntityException {
    byte[] cachedResult = null;
    EntityData.JournalEntry entry = getEntryForTransaction(clientID, transactionID);
    if (null != entry) {
      if (null == entry.failure) {
        Assert.assertNotNull(entry.reconfigureResponse);
        cachedResult = entry.reconfigureResponse;
      } else {
        throw entry.failure;
      }
    }
    return cachedResult;
  }

  public void entityReconfigureFailed(ClientID clientID, long transactionID, long oldestTransactionOnClient, EntityException error) {
    addToJournal(clientID, transactionID, oldestTransactionOnClient, EntityData.Operation.RECONFIGURE, null, false, error);
  }

  /**
   * @return The over-written configuration value.
   */
  public byte[] entityReconfigureSucceeded(ClientID clientID, long transactionID, long oldestTransactionOnClient, EntityID id, long version, byte[] configuration) {
    String className = id.getClassName();
    String entityName = id.getEntityName();

    EntityData.Key key = new EntityData.Key();
    key.className = className;
    key.entityName = entityName;
    
    EntityData.Value val = this.entities.get(key);
    byte[] previousConfiguration = val.configuration;
    Assert.assertNotNull(previousConfiguration);
    val.configuration = configuration;
    Assert.assertEquals(version, val.version);
    
    this.entities.put(key, val);
    
    // Record this in the journal.
    addToJournal(clientID, transactionID, oldestTransactionOnClient, EntityData.Operation.RECONFIGURE, previousConfiguration, false, null);
    
    // Return what we over-wrote.
    return previousConfiguration;
  }

  public long getNextConsumerID() {
    long consumerID = this.counters.get(COUNTERS_CONSUMER_ID);
    this.counters.put(COUNTERS_CONSUMER_ID, new Long(consumerID + 1));
    return consumerID;
  }

  public void removeTrackingForClient(ClientID sourceNodeID) {
    this.entityLifeJournal.remove(sourceNodeID);
  }


  private List<JournalEntry> filterJournal(List<JournalEntry> list, long oldestTransactionOnClient) {
    Assert.assertNotNull(list);
    List<JournalEntry> newList = new Vector<>();
    for (JournalEntry entry : list) {
      if (entry.transactionID >= oldestTransactionOnClient) {
        newList.add(entry);
      }
    }
    return newList;
  }

  private void addToJournal(ClientID clientID, long transactionID, long oldestTransactionOnClient, EntityData.Operation operation, byte[] reconfigureResult, boolean didFind, EntityException error) {
    List<EntityData.JournalEntry> rawJournal = this.entityLifeJournal.get(clientID);
    // Note that this may be the first time we encountered this client.
    if (null == rawJournal) {
      rawJournal = new Vector<>();
    }
    List<EntityData.JournalEntry> clientJournal = filterJournal(rawJournal, oldestTransactionOnClient);
    JournalEntry newEntry = new JournalEntry();
    newEntry.operation = operation;
    newEntry.transactionID = transactionID;
    newEntry.didFind = didFind;
    newEntry.failure = error;
    clientJournal.add(newEntry);
    this.entityLifeJournal.put(clientID, clientJournal);
  }

  private JournalEntry getEntryForTransaction(ClientID clientID, long transactionID) {
    JournalEntry foundEntry = null;
    List<EntityData.JournalEntry> clientJournal =  this.entityLifeJournal.get(clientID);
    // Note that we may not know anything about this client.
    if (null != clientJournal) {
      for (JournalEntry entry : clientJournal) {
        if (entry.transactionID == transactionID) {
          foundEntry = entry;
          break;
        }
      }
    }
    return foundEntry;
  }

  private void addNewEntityToMap(EntityID id, long version, long consumerID, byte[] configuration) {
    String className = id.getClassName();
    String entityName = id.getEntityName();
    
    EntityData.Key key = new EntityData.Key();
    EntityData.Value value = new EntityData.Value();
    key.className = className;
    key.entityName = entityName;
    value.className = className;
    value.version = version;
    value.consumerID = consumerID;
    value.entityName = entityName;
    value.configuration = configuration;
    this.entities.put(key, value);
  }
}
