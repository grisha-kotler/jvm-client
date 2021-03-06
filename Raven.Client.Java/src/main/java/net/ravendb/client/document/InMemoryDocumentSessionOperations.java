package net.ravendb.client.document;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.ravendb.abstractions.basic.CleanCloseable;
import net.ravendb.abstractions.basic.Reference;
import net.ravendb.abstractions.basic.Tuple;
import net.ravendb.abstractions.closure.Action1;
import net.ravendb.abstractions.closure.Action3;
import net.ravendb.abstractions.closure.Function1;
import net.ravendb.abstractions.commands.DeleteCommandData;
import net.ravendb.abstractions.commands.ICommandData;
import net.ravendb.abstractions.commands.PutCommandData;
import net.ravendb.abstractions.data.BatchResult;
import net.ravendb.abstractions.data.Constants;
import net.ravendb.abstractions.data.DocumentsChanges;
import net.ravendb.abstractions.data.DocumentsChanges.ChangeType;
import net.ravendb.abstractions.data.Etag;
import net.ravendb.abstractions.data.HttpMethods;
import net.ravendb.abstractions.data.JsonDocument;
import net.ravendb.abstractions.exceptions.ConcurrencyException;
import net.ravendb.abstractions.exceptions.ReadVetoException;
import net.ravendb.abstractions.extensions.MetadataExtensions;
import net.ravendb.abstractions.json.linq.RavenJArray;
import net.ravendb.abstractions.json.linq.RavenJObject;
import net.ravendb.abstractions.json.linq.RavenJToken;
import net.ravendb.abstractions.json.linq.RavenJValue;
import net.ravendb.abstractions.logging.ILog;
import net.ravendb.abstractions.logging.LogManager;
import net.ravendb.abstractions.util.IncludesUtil;
import net.ravendb.client.DocumentStoreBase;
import net.ravendb.client.IDocumentStore;
import net.ravendb.client.connection.HttpExtensions;
import net.ravendb.client.document.batches.ILazyOperation;
import net.ravendb.client.exceptions.NonAuthoritativeInformationException;
import net.ravendb.client.exceptions.NonUniqueObjectException;
import net.ravendb.client.extensions.MultiDatabase;
import net.ravendb.client.listeners.IDocumentConversionListener;
import net.ravendb.client.listeners.IDocumentDeleteListener;
import net.ravendb.client.listeners.IDocumentStoreListener;
import net.ravendb.client.util.IdentityHashSet;
import net.ravendb.client.util.Types;
import net.ravendb.client.utils.Closer;
import net.ravendb.client.utils.Lang;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang.StringUtils;

import com.google.common.base.Defaults;


/**
 * Abstract implementation for in memory session operations
 */
public abstract class InMemoryDocumentSessionOperations implements CleanCloseable {

  protected final List<ILazyOperation> pendingLazyOperations = new ArrayList<>();
  protected final Map<ILazyOperation, Action1<Object>> onEvaluateLazy = new HashMap<>();

  private static AtomicInteger counter = new AtomicInteger();

  private final int hash = counter.incrementAndGet();

  protected boolean generateDocumentKeysOnStore = true;
  //session id
  private UUID id;

  private String databaseName;

  protected static final ILog log = LogManager.getCurrentClassLogger();

  //The entities waiting to be deleted
  protected final Set<Object> deletedEntities = new IdentityHashSet<>();

  //Entities whose id we already know do not exists, because they are a missing include, or a missing load, etc.
  protected final Set<String> knownMissingIds =  new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

  private Map<String, Object> externalState;

  // hold the data required to manage the data for RavenDB's Unit of Work
  protected final Map<Object, DocumentMetadata> entitiesAndMetadata = new IdentityHashMap<>();

  protected final Map<String, JsonDocument> includedDocumentsByKey = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

  // Translate between a key and its associated entity
  protected final Map<String, Object> entitiesByKey = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

  protected final String dbName;
  private final DocumentStoreBase documentStore;

  // all the listeners for this session
  protected final DocumentSessionListeners theListeners;

  private int numberOfRequests;
  private Long nonAuthoritativeInformationTimeout;
  private int maxNumberOfRequestsPerSession;
  private boolean useOptimisticConcurrency;
  private boolean allowNonAuthoritativeInformation;

  private final List<ICommandData> deferedCommands = new ArrayList<>();
  protected String _databaseName;
  private GenerateEntityIdOnTheClient generateEntityIdOnTheClient;
  public EntityToJson entityToJson;

  public DocumentSessionListeners getListeners() {
    return theListeners;
  }



  /**
   * Gets the number of entities held in memory to manage Unit of Work
   */
  public int getNumberOfEntitiesInUnitOfWork() {
    return entitiesAndMetadata.size();
  }

  public int getNumberOfRequests() {
    return numberOfRequests;
  }

  // The document store associated with this session
  public IDocumentStore getDocumentStore() {
    return documentStore;
  }

  public Map<String, Object> getExternalState() {
    if (externalState == null)  {
      externalState = new HashMap<>();
    }
    return externalState;
  }

  public UUID getId() {
    return id;
  }

  public void setDatabaseName(String databaseName) {
    this.databaseName = databaseName;
  }

  public String getDatabaseName() {
    return Lang.coalesce(databaseName, MultiDatabase.getDatabaseName(documentStore.getUrl()));
  }

  /**
   * Initializes a new instance of the {@link InMemoryDocumentSessionOperations} class.
   * @param dbName
   * @param documentStore
   * @param listeners
   * @param id
   */
  @SuppressWarnings("boxing")
  protected InMemoryDocumentSessionOperations(String dbName,
      DocumentStoreBase documentStore,
      DocumentSessionListeners listeners,
      UUID id) {
    this.id = id;
    this.dbName = dbName;
    this.documentStore = documentStore;
    this.theListeners = listeners;
    this.useOptimisticConcurrency = documentStore.getConventions().isDefaultUseOptimisticConcurrency();
    this.allowNonAuthoritativeInformation = true;
    this.nonAuthoritativeInformationTimeout = 15 * 1000L;
    this.maxNumberOfRequestsPerSession = documentStore.getConventions().getMaxNumberOfRequestsPerSession();
    this.generateEntityIdOnTheClient = new GenerateEntityIdOnTheClient(documentStore.getConventions(), new Function1<Object, String>() {
      @Override
      public String apply(Object entity) {
        return generateKey(entity);
      }
    });
    this.entityToJson = new EntityToJson(documentStore, listeners);
  }

  /**
   * Gets the timeout to wait for authoritative information if encountered non authoritative document.
   */
  public Long getNonAuthoritativeInformationTimeout() {
    return nonAuthoritativeInformationTimeout;
  }

  /**
   * Sets the timeout to wait for authoritative information if encountered non authoritative document.
   * @param nonAuthoritativeInformationTimeout
   */
  public void setNonAuthoritativeInformationTimeout(Long nonAuthoritativeInformationTimeout) {
    this.nonAuthoritativeInformationTimeout = nonAuthoritativeInformationTimeout;
  }

  /**
   * Gets the store identifier for this session.
   * The store identifier is the identifier for the particular RavenDB instance.
   */
  public String getStoreIdentifier() {
    return documentStore.getIdentifier() + ";" + getDatabaseName();
  }

  /**
   * Gets the conventions used by this session
   *
   * This instance is shared among all sessions, changes to the {@link DocumentConvention} should be done
   * via the {@link IDocumentStore} instance, not on a single session.
   */
  public DocumentConvention getConventions() {
    return documentStore.getConventions();
  }

  /**
   * Gets the max number of requests per session.
   * If the numberOfRequest rise above maxNumberOfRequestsPerSession, an exception will be thrown.
   */
  public int getMaxNumberOfRequestsPerSession() {
    return maxNumberOfRequestsPerSession;
  }

  /**
   * Sets the max number of requests per session.
   * If the numberOfRequest rise above maxNumberOfRequestsPerSession, an exception will be thrown.
   * @param maxNumberOfRequestsPerSession
   */
  public void setMaxNumberOfRequestsPerSession(int maxNumberOfRequestsPerSession) {
    this.maxNumberOfRequestsPerSession = maxNumberOfRequestsPerSession;
  }

  /**
   * Gets a value indicating whether the session should use optimistic concurrency.
   * When set to <c>true</c>, a check is made so that a change made behind the session back would fail
   * and raise {@link ConcurrencyException}
   */
  public boolean isUseOptimisticConcurrency() {
    return useOptimisticConcurrency;
  }

  /**
   * Sets a value indicating whether the session should use optimistic concurrency.
   * When set to <c>true</c>, a check is made so that a change made behind the session back would fail
   * and raise {@link ConcurrencyException}
   */
  public void setUseOptimisticConcurrency(boolean useOptimisticConcurrency) {
    this.useOptimisticConcurrency = useOptimisticConcurrency;
  }

  /**
   * Gets the ETag for the specified entity.
   *
   * If the entity is transient, it will load the etag from the store
   * and associate the current state of the entity with the etag from the server.
   *
   * @param instance
   */
  public <T> Etag getEtagFor(T instance) {
    return getDocumentMetadata(instance).getEtag();
  }

  /**
   * Gets the metadata for the specified entity.
   * @param instance
   */
  public <T> RavenJObject getMetadataFor(T instance) {
    return getDocumentMetadata(instance).getMetadata();
  }

  private <T> DocumentMetadata getDocumentMetadata(T instance) {
    DocumentMetadata value;
    if (entitiesAndMetadata.containsKey(instance)) {
      return entitiesAndMetadata.get(instance);
    } else {
      Reference<String> idHolder = new Reference<>();

      if (generateEntityIdOnTheClient.tryGetIdFromInstance(instance, idHolder)) {
        assertNoNonUniqueInstance(instance, idHolder.value);
        JsonDocument jsonDocument = getJsonDocument(idHolder.value);
        value = getDocumentMetadataValue(instance, idHolder, jsonDocument);
      } else {
        throw new IllegalStateException("Could not find the document key for " + instance);
      }
      return value;
    }
  }

  protected <T> DocumentMetadata getDocumentMetadataValue(T instance, Reference<String> idHolder, JsonDocument jsonDocument) {
    entitiesByKey.put(idHolder.value, instance);
    DocumentMetadata value = new DocumentMetadata();
    value.setEtag(useOptimisticConcurrency ? Etag.empty(): null);
    value.setKey(idHolder.value);
    value.setOriginalMetadata(jsonDocument.getMetadata());
    value.setMetadata(jsonDocument.getMetadata().cloneToken());
    value.setOriginalValue(new RavenJObject());
    entitiesAndMetadata.put(instance, value);
    return value;
  }

  /**
   * Get the json document by key from the store
   * @param documentKey
   */
  protected abstract JsonDocument getJsonDocument(String documentKey);

  /**
   * Returns whatever a document with the specified id is loaded in the
   * current session
   * @param id
   */
  @SuppressWarnings("hiding")
  public boolean isLoaded(String id) {
    if (isDeleted(id)) {
      return false;
    }
    return entitiesByKey.containsKey(id) || includedDocumentsByKey.containsKey(id);
  }

  /**
   * Returns whatever a document with the specified id is deleted
   * or known to be missing
   * @param id
   */
  @SuppressWarnings("hiding")
  public boolean isDeleted(String id) {
    return knownMissingIds.contains(id);
  }

  /**
   * Gets the document id.
   * @param instance
   */
  public String getDocumentId(Object instance) {
    if (instance == null)
      return null;
    DocumentMetadata metadata = entitiesAndMetadata.get(instance);
    if (metadata == null) {
      return null;
    }
    return metadata.getKey();
  }

  /**
   * Gets a value indicating whether any of the entities tracked by the session has changes.
   */
  public boolean hasChanges() {
    if (deletedEntities.size() > 0) {
      return true;
    }
    for (Map.Entry<Object, DocumentMetadata> pair : entitiesAndMetadata.entrySet()) {
      if (entityChanged(pair.getKey(), pair.getValue(), null)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Determines whether the specified entity has changed.
   *
   * @param entity
   */
  public boolean hasChanged(Object entity) {
    DocumentMetadata value;
    if (entitiesAndMetadata.containsKey(entity)) {
      value = entitiesAndMetadata.get(entity);
      return entityChanged(entity, value, null);
    } else {
      return false;
    }
  }

  @SuppressWarnings("boxing")
  public void incrementRequestCount() {
    if (++numberOfRequests > maxNumberOfRequestsPerSession)
      throw new IllegalStateException(String.format("The maximum number of requests (%d) allowed for this session has been reached."  +
          "Raven limits the number of remote calls that a session is allowed to make as an early warning system. Sessions are expected to be short lived, and " +
          "Raven provides facilities like Load(string[] keys) to load multiple documents at once and batch saves (call SaveChanges() only once)." +
          "You can increase the limit by setting DocumentConvention.MaxNumberOfRequestsPerSession or MaxNumberOfRequestsPerSession, but it is" +
          "advisable that you'll look into reducing the number of remote calls first, since that will speed up your application significantly and result in a" +
          "more responsive application.", maxNumberOfRequestsPerSession));
  }

  /**
   * Tracks the entity inside the unit of work
   * @param entityType
   * @param documentFound
   */
  public Object trackEntity(Class<?> entityType, JsonDocument documentFound) {
    if (Boolean.TRUE.equals(documentFound.getNonAuthoritativeInformation()) && !allowNonAuthoritativeInformation) {
      throw new NonAuthoritativeInformationException("Document " + documentFound.getKey() +
          " returned Non Authoritative Information (probably modified by a transaction in progress) and AllowNonAuthoritativeInformation  is set to false");
    }
    if (Boolean.TRUE.equals(documentFound.getMetadata().value(Boolean.class, Constants.RAVEN_DOCUMENT_DOES_NOT_EXISTS))) {
      return getDefaultValue(entityType); // document is not really there.
    }
    if (documentFound.getEtag() != null && !documentFound.getMetadata().containsKey("@etag")) {
      documentFound.getMetadata().add("@etag", new RavenJValue(documentFound.getEtag().toString()));
    }
    if (!documentFound.getMetadata().containsKey(Constants.LAST_MODIFIED)) {
      documentFound.getMetadata().add(Constants.LAST_MODIFIED, new RavenJValue(documentFound.getLastModified()));
    }

    return trackEntity(entityType, documentFound.getKey(), documentFound.getDataAsJson(), documentFound.getMetadata(), false);
  }

  /**
   * Tracks the entity.
   * @param entityType The entityType
   * @param key The key
   * @param document The document
   * @param metadata The metadata
   * @param noTracking Entity tracking is enabled if true, disabled otherwise.
   */
  @SuppressWarnings("boxing")
  public Object trackEntity(Class<?> entityType, String key, RavenJObject document, RavenJObject metadata, boolean noTracking) {
    document.remove("@metadata");
    Object entity;
    if (entitiesByKey.containsKey(key)) {
      // the local instance may have been changed, we adhere to the current Unit of Work
      // instance, and return that, ignoring anything new.
      return entitiesByKey.get(key);
    } else {
      entity = convertToEntity(entityType, key, document, metadata, false);
    }

    String etag = metadata.value(String.class, "@etag");

    if (metadata.value(Boolean.TYPE, "Non-Authoritative-Information") && !allowNonAuthoritativeInformation) {
      throw new NonAuthoritativeInformationException("Document " + key +
          " returned Non Authoritative Information (probably modified by a transaction in progress) and AllowNonAuthoritativeInformation  is set to false");
    }

    if (!noTracking) {
      DocumentMetadata docMeta = new DocumentMetadata();
      docMeta.setOriginalValue(document);
      docMeta.setMetadata(metadata);
      docMeta.setOriginalMetadata(metadata.cloneToken());
      docMeta.setEtag(HttpExtensions.etagHeaderToEtag(etag));
      docMeta.setKey(key);

      entitiesAndMetadata.put(entity, docMeta);
      entitiesByKey.put(key, entity);
    }

    return entity;
  }

  /**
   * Converts the json document to an entity.
   * @param entityType
   * @param id The id.
   * @param documentFound The document found
   * @param metadata The metadata
   * @param isStreaming Is the conversion is part of the streaming? If yes, no sense in registering missing properties
   */
  @SuppressWarnings("hiding")
  public Object convertToEntity(Class<?> entityType, String id, RavenJObject documentFound, RavenJObject metadata, boolean isStreaming) {
    if (RavenJObject.class.equals(entityType)) {
      return documentFound.cloneToken();
    }
    for (IDocumentConversionListener extendedDocumentConversionListener: theListeners.getConversionListeners()) {
      extendedDocumentConversionListener.beforeConversionToEntity(id, documentFound, metadata);
    }

    Object defaultValue = getDefaultValue(entityType);
    Object entity = defaultValue;
    ensureNotReadVetoed(metadata);

    CleanCloseable disposable = null;
    DefaultRavenContractResolver defaultRavenContractResolver = (DefaultRavenContractResolver) getConventions().getJsonContractResolver();
    if (!isStreaming && defaultRavenContractResolver != null && getConventions().isPreserveDocumentPropertiesNotFoundOnModel()) {
      disposable = defaultRavenContractResolver.registerForExtensionData(new Action3<Object, String, RavenJToken>() {
        @SuppressWarnings("synthetic-access")
        @Override
        public void apply(Object o, String key, RavenJToken value) {
          registerMissingProperties(o, key, value);
        }
      });
    }

    try {
      String documentType = getConventions().getJavaClass(id, documentFound, metadata);
      if (documentType != null) {
        Class< ? > type = Class.forName(documentType);
        if (type != null) {
          entity = getConventions().createSerializer().deserialize(documentFound, type);
        }
      }

      if (Objects.equals(entity, defaultValue)) {
        entity = getConventions().createSerializer().deserialize(documentFound, entityType);
      }

      generateEntityIdOnTheClient.trySetIdentity(entity, id);
      for (IDocumentConversionListener extendedDocumentConversionListener: theListeners.getConversionListeners()) {
        extendedDocumentConversionListener.afterConversionToEntity(id, documentFound, metadata, entity);
      }

      return entity;
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    } finally {
      Closer.close(disposable);
    }
  }

  private void registerMissingProperties(Object o, String key, Object value) {
    if (!entityToJson.getMissingDictionary().containsKey(o)) {
      entityToJson.getMissingDictionary().put(o, new HashMap<String, RavenJToken>());
    }
    Map<String, RavenJToken> dictionary = entityToJson.getMissingDictionary().get(o);
    dictionary.put(key, convertValueToJToken(value));
  }

  private static RavenJToken convertValueToJToken(Object value) {
    if (value instanceof RavenJToken)  {
      return (RavenJToken)value;
    }
    return RavenJToken.fromObject(value);
  }

  /**
   * Gets the default value of the specified type.
   * @param type
   */
  static Object getDefaultValue(Class<?> type) {
    return Defaults.defaultValue(type);
  }

  /**
   *
   * Gets  a value indicating whether non authoritative information is allowed.
   * Non authoritative information is document that has been modified by a transaction that hasn't been committed.
   * The server provides the latest committed version, but it is known that attempting to write to a non authoritative document
   * will fail, because it is already modified.
   * If set to <c>false</c>, the session will wait nonAuthoritativeInformationTimeout for the transaction to commit to get an
   * authoritative information. If the wait is longer than nonAuthoritativeInformationTimeout, NonAuthoritativeInformationException is thrown.
   * @return  true  if non authoritative information is allowed; otherwise, <c>false</c>.
   */
  public boolean isAllowNonAuthoritativeInformation() {
    return allowNonAuthoritativeInformation;
  }

  /**
   * Sets  a value indicating whether non authoritative information is allowed.
   * Non authoritative information is document that has been modified by a transaction that hasn't been committed.
   * The server provides the latest committed version, but it is known that attempting to write to a non authoritative document
   * will fail, because it is already modified.
   * If set to <c>false</c>, the session will wait nonAuthoritativeInformationTimeout for the transaction to commit to get an
   * authoritative information. If the wait is longer than nonAuthoritativeInformationTimeout, NonAuthoritativeInformationException is thrown.
   */
  public void setAllowNonAuthoritativeInformation(boolean allowNonAuthoritativeInformation) {
    this.allowNonAuthoritativeInformation = allowNonAuthoritativeInformation;
  }

  /**
   * Marks the specified entity for deletion. The entity will be deleted when SaveChanges is called.
   */
  @SuppressWarnings("boxing")
  public <T> void delete(T entity) {
    if (entity == null) {
      throw new IllegalArgumentException("Entity is null");
    }
    if (!entitiesAndMetadata.containsKey(entity)) {
      throw new IllegalStateException(entity + " is not associated with the session, cannot delete unknown entity instance");
    }
    DocumentMetadata value = entitiesAndMetadata.get(entity);
    if (value.getOriginalMetadata().containsKey(Constants.RAVEN_READ_ONLY) && value.getOriginalMetadata().value(Boolean.class, Constants.RAVEN_READ_ONLY)) {
      throw new IllegalStateException(entity + " is marked as read only and cannot be deleted");
    }

    deletedEntities.add(entity);
    knownMissingIds.add(value.getKey());
  }

  /**
   * Marks the specified entity for deletion. The entity will be deleted when IDocumentSession.saveChanges() is called.
   * WARNING: This method will not call beforeDelete listener!
   */
  @SuppressWarnings({"hiding", "boxing"})
  public <T> void delete(Class<T> clazz, Number id) {
    delete(getConventions().getFindFullDocumentKeyFromNonStringIdentifier().find(id, clazz, false));
  }

  /**
   * Marks the specified entity for deletion. The entity will be deleted when IDocumentSession.saveChanges() is called.
   * WARNING: This method will not call beforeDelete listener!
   */
  @SuppressWarnings({"hiding", "boxing"})
  public <T> void delete(Class<T> clazz, UUID id) {
    delete(getConventions().getFindFullDocumentKeyFromNonStringIdentifier().find(id, clazz, false));
  }

  /**
   * Marks the specified entity for deletion. The entity will be deleted when IDocumentSession.saveChanges() is called.
   * WARNING: This method will not call beforeDelete listener!
   */
  @SuppressWarnings("hiding")
  public void delete(String id)
  {
      if (id == null) {
        throw new IllegalArgumentException("id is null");
      }
      Object entity;
      if (entitiesByKey.containsKey(id)) {
        entity = entitiesByKey.get(id);
          // find if entity was changed on session or just inserted
          if (entityChanged(entity, entitiesAndMetadata.get(entity))) {
              throw new IllegalStateException("Can't delete changed entity using identifier. Use delete(T entity) instead.");
          }
          delete(entity);
          return;
      }
      includedDocumentsByKey.remove(id);
      knownMissingIds.add(id);
      defer(new DeleteCommandData(id, null));
  }

  public static void ensureNotReadVetoed(RavenJObject metadata) {
    RavenJToken readVetoToken = metadata.get("Raven-Read-Veto");
    if (readVetoToken instanceof RavenJObject) {
      RavenJObject readVeto = (RavenJObject) readVetoToken;
      String s = readVeto.value(String.class, "Reason");
      throw new ReadVetoException(
          "Document could not be read because of a read veto.\n"  +
              "The read was vetoed by: " + readVeto.value(String.class, "Trigger") + "\n" +
              "Veto reason: " + s
          );
    } else {
      return;
    }
  }

  /**
   * Stores the specified entity in the session. The entity will be saved when SaveChanges is called.
   * @param entity
   */
  @SuppressWarnings("hiding")
  public void store(Object entity) {
    Reference<String> id = new Reference<>();

    boolean hasId = generateEntityIdOnTheClient.tryGetIdFromInstance(entity, id);
    storeInternal(entity, null, null, hasId == false);
  }

  /**
   * Stores the specified entity in the session. The entity will be saved when SaveChanges is called.
   * @param entity
   * @param etag
   */
  public void store(Object entity, Etag etag) {
    storeInternal(entity, etag, null, true);
  }

  /**
   * Stores the specified entity in the session, explicitly specifying its Id. The entity will be saved when SaveChanges is called.
   * @param entity
   * @param id
   */
  @SuppressWarnings("hiding")
  public void store(Object entity, String id) {
    storeInternal(entity, null, id, false);
  }

  /**
   * Stores the specified entity in the session, explicitly specifying its Id. The entity will be saved when SaveChanges is called.
   * @param entity
   * @param etag
   * @param id
   */
  @SuppressWarnings("hiding")
  public void store(Object entity, Etag etag, String id) {
    storeInternal(entity, etag, id, true);
  }

  @SuppressWarnings("hiding")
  private void storeInternal(Object entity, Etag etag, String id, boolean forceConcurrencyCheck) {
    if (entity == null) {
      throw new IllegalArgumentException("entity is null");
    }

    if (entitiesAndMetadata.containsKey(entity)) {
      DocumentMetadata value = entitiesAndMetadata.get(entity);
      if (etag != null) {
        value.setEtag(etag);
      }
      value.setForceConcurrencyCheck(forceConcurrencyCheck);
      return;
    }

    if (id == null) {
      if (generateDocumentKeysOnStore) {
        id = generateEntityIdOnTheClient.generateDocumentKeyForStorage(entity);
      } else {
        rememberEntityForDocumentKeyGeneration(entity);
      }
    } else {
      // Store it back into the Id field so the client has access to to it
      generateEntityIdOnTheClient.trySetIdentity(entity, id);
    }

    for (ICommandData command : deferedCommands) {
      if (command.getKey().equals(id)) {
        throw new IllegalStateException("Can't store document, there is a deferred command registered for this document in the session. Document id:" + id);
      }
    }

    if (deletedEntities.contains(entity)) {
      throw new IllegalStateException("Can't store object, it was already deleted in this session.  Document id: " + id);
    }

    // we make the check here even if we just generated the key
    // users can override the key generation behavior, and we need
    // to detect if they generate duplicates.
    assertNoNonUniqueInstance(entity, id);

    RavenJObject metadata = new RavenJObject();
    String tag = documentStore.getConventions().getDynamicTagName(entity);
    if (tag != null) {
      metadata.add(Constants.RAVEN_ENTITY_NAME, new RavenJValue(tag));
    }
    if (id != null) {
      knownMissingIds.remove(id);
    }
    storeEntityInUnitOfWork(id, entity, etag, metadata, forceConcurrencyCheck);
  }


  protected abstract String generateKey(Object entity);

  @SuppressWarnings("unused")
  protected void rememberEntityForDocumentKeyGeneration(Object entity) {
    throw new NotImplementedException("You cannot set GenerateDocumentKeysOnStore to false without implementing RememberEntityForDocumentKeyGeneration");
  }

  @SuppressWarnings("hiding")
  protected void storeEntityInUnitOfWork(String id, Object entity, Etag etag, RavenJObject metadata, boolean forceConcurrencyCheck) {
    deletedEntities.remove(entity);
    if (id != null) {
      knownMissingIds.remove(id);
    }

    DocumentMetadata meta = new DocumentMetadata();
    meta.setKey(id);
    meta.setMetadata(metadata);
    meta.setOriginalMetadata(new RavenJObject());
    meta.setEtag(etag);
    meta.setOriginalValue(new RavenJObject());
    meta.setForceConcurrencyCheck(forceConcurrencyCheck);

    entitiesAndMetadata.put(entity, meta);

    if (id != null) {
      entitiesByKey.put(id, entity);
    }
  }

  @SuppressWarnings("hiding")
  protected void assertNoNonUniqueInstance(Object entity, String id) {
    if (id == null || id.endsWith("/") || !entitiesByKey.containsKey(id) || entitiesByKey.get(id) == entity) {
      return;
    }

    throw new NonUniqueObjectException("Attempted to associate a different object with id '" + id + "'.");
  }

  /**
   * Creates the put entity command.
   * @param entity
   * @param documentMetadata
   */
  protected ICommandData createPutEntityCommand(Object entity, DocumentMetadata documentMetadata) {

    Reference<String> idHolder = new Reference<>();

    if (generateEntityIdOnTheClient.tryGetIdFromInstance(entity, idHolder) &&
        documentMetadata.getKey() != null &&
        !documentMetadata.getKey().equalsIgnoreCase(idHolder.value)) {
      throw new IllegalStateException("Entity " + entity.getClass().getName() + " had document key '" +
          documentMetadata.getKey() + "' but now has document key property '" + idHolder.value + "'.\n" +
          "You cannot change the document key property of a entity loaded into the session");
    }

    RavenJObject json = entityToJson.convertEntityToJson(documentMetadata.getKey(), entity, documentMetadata.getMetadata());

    Etag etag = (isUseOptimisticConcurrency() || documentMetadata.isForceConcurrencyCheck() )? ( documentMetadata.getEtag() != null ? documentMetadata.getEtag() : Etag.empty() ) : null;

    PutCommandData putCommand = new PutCommandData();
    putCommand.setDocument(json);
    putCommand.setEtag(etag);
    putCommand.setKey(documentMetadata.getKey());
    putCommand.setMetadata(MetadataExtensions.filterHeadersToObject(documentMetadata.getMetadata().cloneToken()));
    return putCommand;

  }

  /**
   * Updates the batch results.
   * @param batchResults
   * @param saveChangesData
   */
  protected void updateBatchResults(List<BatchResult> batchResults, SaveChangesData saveChangesData) {
    if (documentStore.hasJsonRequestFactory() && getConventions().isShouldSaveChangesForceAggressiveCacheCheck() && batchResults.size() != 0) {
      documentStore.getJsonRequestFactory().expireItemsFromCache(databaseName != null ? databaseName : Constants.SYSTEM_DATABASE);
    }
    for (int i = saveChangesData.getDeferredCommandsCount(); i < batchResults.size(); i++) {
      BatchResult batchResult = batchResults.get(i);
      if (!batchResult.getMethod().equalsIgnoreCase(HttpMethods.PUT.name())) {
        continue;
      }

      Object entity = saveChangesData.getEntities().get(i - saveChangesData.getDeferredCommandsCount());
      if (!entitiesAndMetadata.containsKey(entity)) {
        continue;
      }
      DocumentMetadata documentMetadata = entitiesAndMetadata.get(entity);
      batchResult.getMetadata().add("@etag", new RavenJValue(batchResult.getEtag().toString()));
      entitiesByKey.put(batchResult.getKey(), entity);

      documentMetadata.setEtag(batchResult.getEtag());
      documentMetadata.setKey(batchResult.getKey());
      documentMetadata.setOriginalMetadata(batchResult.getMetadata().cloneToken());
      documentMetadata.setMetadata(batchResult.getMetadata());
      documentMetadata.setOriginalValue(entityToJson.convertEntityToJson(documentMetadata.getKey(), entity, documentMetadata.getMetadata()));

      generateEntityIdOnTheClient.trySetIdentity(entity, batchResult.getKey());

      for (IDocumentStoreListener documentStoreListener : theListeners.getStoreListeners()) {
        documentStoreListener.afterStore(batchResult.getKey(), entity, batchResult.getMetadata());
      }
    }

    BatchResult lastPut = null;
    for (int i = batchResults.size() - 1; i >=0; i--) {
      if (batchResults.get(i).getMethod().equals("PUT")) {
        lastPut = batchResults.get(i);
        break;
      }
    }
    if (lastPut == null) {
      return ;
    }

    documentStore.getLastEtagHolder().updateLastWrittenEtag(lastPut.getEtag());
  }

  /**
   * Prepares for save changes.
   */
  protected SaveChangesData prepareForSaveChanges() {
    getEntityToJson().getCachedJsonDocs().clear();

    SaveChangesData result = new SaveChangesData();
    result.setEntities(new ArrayList<>());
    result.setCommands(new ArrayList<>(deferedCommands));
    result.setDeferredCommandsCount(deferedCommands.size());

    deferedCommands.clear();

    prepareForEntitiesDeletion(result, null);
    prepareForEntitiesPuts(result);

    return result;
  }

  public Map<String, List<DocumentsChanges>> whatChanged() {
    Map<String, List<DocumentsChanges>> changes = new HashMap<>();
    prepareForEntitiesDeletion(null, changes);
    getAllEntitiesChanges(changes);
    return changes;
  }


  private void prepareForEntitiesPuts(SaveChangesData result) {
    for (Map.Entry<Object, DocumentMetadata> pair: entitiesAndMetadata.entrySet()) {
      if (entityChanged(pair.getKey(), pair.getValue())) {
        for (IDocumentStoreListener documentStoreListener : theListeners.getStoreListeners()) {

          if (documentStoreListener.beforeStore(pair.getValue().getKey(), pair.getKey(), pair.getValue().getMetadata(), pair.getValue().getOriginalValue())) {
            entityToJson.getCachedJsonDocs().remove(pair.getKey());
          }
        }
        result.getEntities().add(pair.getKey());

        if (pair.getValue().getKey() != null) {
          entitiesByKey.remove(pair.getValue().getKey());
        }
        result.getCommands().add(createPutEntityCommand(pair.getKey(), pair.getValue()));
      }
    }
  }

  private void getAllEntitiesChanges(Map<String, List<DocumentsChanges>> changes) {
    for (Map.Entry<Object, DocumentMetadata> pair : entitiesAndMetadata.entrySet()) {
      if (pair.getValue().getOriginalValue().getCount() == 0) {
        List<DocumentsChanges> docChanges = new ArrayList<>();
        DocumentsChanges change = new DocumentsChanges();
        change.setChange(ChangeType.DOCUMENT_ADDED);
        docChanges.add(change);
        changes.put(pair.getValue().getKey(), docChanges);
        continue;
      }
      entityChanged(pair.getKey(), pair.getValue(), changes);
    }
  }

  @SuppressWarnings("boxing")
  private void prepareForEntitiesDeletion(SaveChangesData result, Map<String, List<DocumentsChanges>> changes) {
    DocumentMetadata value = null;

    List<String> keysToDelete = new ArrayList<>();

    for (Object deletedEntity: deletedEntities) {
      if (entitiesAndMetadata.containsKey(deletedEntity)) {
        value = entitiesAndMetadata.get(deletedEntity);
        if (!value.getOriginalMetadata().containsKey(Constants.RAVEN_READ_ONLY) ||
            !value.getOriginalMetadata().value(boolean.class, Constants.RAVEN_READ_ONLY)) {
          keysToDelete.add(value.getKey());
        }
      }
    }
    for(String key: keysToDelete) {
      if (changes != null)
      {
        List<DocumentsChanges> docChanges = new ArrayList<>();
        DocumentsChanges change = new DocumentsChanges();
        change.setFieldNewValue("");
        change.setFieldOldValue("");
        change.setChange(ChangeType.DOCUMENT_DELETED);

        docChanges.add(change);
        changes.put(key, docChanges);
      }
      else
      {
          Etag etag = null;
          Object existingEntity = null;
          DocumentMetadata metadata = null;
          if (entitiesByKey.containsKey(key)) {
            existingEntity = entitiesByKey.get(key);
            if (entitiesAndMetadata.containsKey(existingEntity)) {
              metadata = entitiesAndMetadata.get(existingEntity);
              etag = metadata.getEtag();
            }
            entitiesAndMetadata.remove(existingEntity);
            entitiesByKey.remove(key);
          }

          etag = isUseOptimisticConcurrency() ? etag : null;
          result.getEntities().add(existingEntity);
          for (IDocumentDeleteListener deleteListener: theListeners.getDeleteListeners()) {
            deleteListener.beforeDelete(key, existingEntity, metadata != null ? metadata.getMetadata(): null);
          }
          DeleteCommandData delCmd = new DeleteCommandData();
          delCmd.setEtag(etag);
          delCmd.setKey(key);

          result.getCommands().add(delCmd);
      }
    }
    if (changes == null) {
      deletedEntities.clear();
    }
  }

  /**
   * Mark the entity as read only, change tracking won't apply
   * to such an entity. This can be done as an optimization step, so
   * we don't need to check the entity for changes.
   * @param entity
   */
  public void markReadOnly(Object entity) {
    getMetadataFor(entity).add(Constants.RAVEN_READ_ONLY, new RavenJValue(true));
  }

  public void ignoreChangesFor(Object entity) {
    getDocumentMetadata(entity).setIgnoreChanges(true);
  }


  protected boolean entityChanged(Object entity, DocumentMetadata documentMetadata) {
    return entityChanged(entity, documentMetadata, null);
  }

  /**
   * Determines if the entity have changed.
   * @param entity
   * @param documentMetadata The map of changes
   */
  @SuppressWarnings("boxing")
  protected boolean entityChanged(Object entity, DocumentMetadata documentMetadata, Map<String, List<DocumentsChanges>> changes) {
    if (documentMetadata == null) {
      return true;
    }

    if (documentMetadata.isIgnoreChanges()) {
      return false;
    }

    Reference<String> idHolder = new Reference<>();
    if (generateEntityIdOnTheClient.tryGetIdFromInstance(entity, idHolder) &&
        !StringUtils.equalsIgnoreCase(documentMetadata.getKey(), idHolder.value)) {
      return true;
    }

    // prevent saves of a modified read only entity
    if (documentMetadata.getOriginalMetadata().containsKey(Constants.RAVEN_READ_ONLY)
        && documentMetadata.getOriginalMetadata().value(boolean.class, Constants.RAVEN_READ_ONLY)
        && documentMetadata.getMetadata().containsKey(Constants.RAVEN_READ_ONLY)
        && documentMetadata.getMetadata().value(boolean.class, Constants.RAVEN_READ_ONLY)) {
      return false;
    }

    RavenJObject newObj = entityToJson.convertEntityToJson(documentMetadata.getKey(), entity, documentMetadata.getMetadata());

    List<DocumentsChanges> changedData = changes != null ? new ArrayList<DocumentsChanges>() : null;
    boolean changed = (RavenJToken.deepEquals(newObj, documentMetadata.getOriginalValue(), changedData) == false) ||
            (RavenJToken.deepEquals(documentMetadata.getMetadata(), documentMetadata.getOriginalMetadata(), changedData) == false);

    if (changes != null && !changedData.isEmpty()) {
      changes.put(documentMetadata.getKey(), changedData);
    }
    return changed;
  }

  /**
   * Evicts the specified entity from the session.
   * Remove the entity from the delete queue and stops tracking changes for this entity.
   */
  public <T> void evict(T entity) {
    if (entitiesAndMetadata.containsKey(entity)) {
      DocumentMetadata value = entitiesAndMetadata.get(entity);
      entitiesAndMetadata.remove(entity);
      entitiesByKey.remove(value.getKey());
    }
    deletedEntities.remove(entity);
  }

  /**
   * Clears this instance.
   * Remove all entities from the delete queue and stops tracking changes for all entities.
   */
  public void clear() {
    entitiesAndMetadata.clear();
    deletedEntities.clear();
    entitiesByKey.clear();
    knownMissingIds.clear();
  }

  public EntityToJson getEntityToJson() {
    return entityToJson;
  }

  public GenerateEntityIdOnTheClient getGenerateEntityIdOnTheClient() {
    return generateEntityIdOnTheClient;
  }


  /**
   * Defer commands to be executed on saveChanges()
   */
  public void defer(ICommandData... commands) {
    for (ICommandData command: commands) {
      deferedCommands.add(command);
    }
  }

  /**
   *  Version this entity when it is saved.  Use when Versioning bundle configured to ExcludeUnlessExplicit.
   */
  @SuppressWarnings("boxing")
  public void explicitlyVersion(Object entity) {
    RavenJObject metadata = getMetadataFor(entity);
    metadata.add(Constants.RAVEN_CREATE_VERSION, true);
  }

  /**
   * Performs application-defined tasks associated with freeing, releasing, or resetting unmanaged resources.
   */
  @Override
  public void close() {
    //empty by design
  }

  @SuppressWarnings("boxing")
  protected void logBatch(SaveChangesData data) {

    if (log.isDebugEnabled()) {
      StringBuilder sb = new StringBuilder();
      sb.append(String.format("Saving %d changes to %s\n", data.getCommands().size(), getStoreIdentifier()));
      for (ICommandData commandData : data.getCommands()) {
        sb.append(String.format("\t%s %s\n", commandData.getMethod(), commandData.getKey()));
      }
      log.debug(sb.toString());
    }
  }

  @SuppressWarnings("hiding")
  public void registerMissing(String id) {
    knownMissingIds.add(id);
  }

  public void unregisterMissing(String id) {
    knownMissingIds.remove(id);
  }

  public void registerMissingIncludes(Collection<RavenJObject> results, Collection<String> includes) {
    if (includes == null || includes.isEmpty()){
      return;
    }

    for (RavenJObject result : results) {
      for (String include: includes) {
        IncludesUtil.include(result, include, new Action1<String>() {
          @SuppressWarnings("hiding")
          @Override
          public void apply(String id) {
            if (id == null) {
              return;
            }
            if (isLoaded(id) == false) {
              registerMissing(id);
            }
          }
        });
      }
    }
  }

  @Override
  public int hashCode() {
    return hash;
  }

  public Object projectionToInstance(RavenJObject y, Class<?> type) {
    handleInternalMetadata(y);
    for (IDocumentConversionListener conversionListener : theListeners.getConversionListeners()) {
      conversionListener.beforeConversionToEntity(null, y, null);
    }

    Object instance = getConventions().createSerializer().deserialize(y, type);

    for (IDocumentConversionListener conversionListener : theListeners.getConversionListeners()) {
      conversionListener.afterConversionToEntity(null, y, null, instance);
    }
    return instance;
  }

  protected void handleInternalMetadata(RavenJObject result) {
    // Implant a property with "id" value ... if not exists
    RavenJObject metadata = result.value(RavenJObject.class, "@metadata");
    if (metadata == null || StringUtils.isEmpty(metadata.value(String.class, "@id"))) {
      // if the item has metadata, then nested items will not have it, so we can skip recursing down
      for (Map.Entry<String, RavenJToken> nestedToken : result) {
        RavenJToken nested = nestedToken.getValue();
        if (nested instanceof RavenJObject) {
          handleInternalMetadata((RavenJObject) nested);
        }
        if (nested instanceof RavenJArray) {
          RavenJArray array = (RavenJArray) nested;
          for (RavenJToken item : array) {
            if (item instanceof RavenJObject) {
              handleInternalMetadata((RavenJObject) item);
            }
          }
        }
      }
      return;
    }

    String entityName = metadata.value(String.class, Constants.RAVEN_ENTITY_NAME);
    String idPropName = getConventions().getFindIdentityPropertyNameFromEntityName().find(entityName);
    if (result.containsKey(idPropName)) {
      return;
    }
    result.add(idPropName, new RavenJValue(metadata.value(String.class, "@id")));

  }



  public void trackIncludedDocument(JsonDocument include) {
      includedDocumentsByKey.put(include.getKey(), include);
  }

  @SuppressWarnings("rawtypes")
  public String createDynamicIndexName(Class clazz) {
    String indexName = "dynamic";
    if (Types.isEntityType(clazz)) {
      indexName += "/" + getConventions().getTypeTagName(clazz);
    }
    return indexName;
  }

  @SuppressWarnings({"hiding", "boxing"})
  public boolean checkIfIdAlreadyIncluded(String[] ids, Tuple<String, Class<?>>[] includes) {
    for (String id : ids) {
      if (knownMissingIds.contains(id)) {
        continue;
      }

      if (!entitiesByKey.containsKey(id)) {
        return false;
      }
      Object data = entitiesByKey.get(id);
      if (!entitiesAndMetadata.containsKey(data)) {
        return false;
      }
      DocumentMetadata value = entitiesAndMetadata.get(data);
      for (Tuple<String, Class<?>> include : includes) {
        final Reference<Boolean> hasAll = new Reference<>(true);
        IncludesUtil.include(value.getOriginalValue(), include.getItem1(), new Action1<String>() {
          @Override
          public void apply(String s) {
           hasAll.value &= isLoaded(s);
          }
        });
        if (!hasAll.value) {
          return false;
        }
      }
    }

    return true;
  }

  public <T> void refreshInternal(T entity, JsonDocument jsonDocument, DocumentMetadata value) {
    if (jsonDocument == null) {
      throw new IllegalStateException("Document '" + value.getKey() + "' no longer exists and was probably deleted");
    }
    value.setMetadata(jsonDocument.getMetadata());
    value.setOriginalMetadata(jsonDocument.getMetadata().cloneToken());
    value.setEtag(jsonDocument.getEtag());
    value.setOriginalValue(jsonDocument.getDataAsJson());
    Object newEntity = convertToEntity(entity.getClass(), value.getKey(), jsonDocument.getDataAsJson(), jsonDocument.getMetadata(), false);

    try {
      for (PropertyDescriptor propertyDescriptor : Introspector.getBeanInfo(entity.getClass()).getPropertyDescriptors()) {
        if (propertyDescriptor.getWriteMethod() == null || propertyDescriptor.getReadMethod() == null) {
          continue;
        }
        Object propValue = propertyDescriptor.getReadMethod().invoke(newEntity, new Object[0]);
        propertyDescriptor.getWriteMethod().invoke(entity, new Object[] { propValue });
      }
    } catch (IntrospectionException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }




}
