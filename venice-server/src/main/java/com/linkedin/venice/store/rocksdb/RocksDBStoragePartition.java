package com.linkedin.venice.store.rocksdb;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.store.AbstractStoragePartition;
import com.linkedin.venice.store.StoragePartitionConfig;
import com.linkedin.venice.utils.ByteUtils;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.EnvOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.IngestExternalFileOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.SstFileWriter;
import org.rocksdb.WriteOptions;


/**
 * In {@link RocksDBStoragePartition}, it assumes the update(insert/delete) will happen sequentially.
 * If the batch push is bytewise-sorted by key, this class is leveraging {@link SstFileWriter} to
 * generate the SST file directly and ingest all the generated SST files into the RocksDB database
 * at the end of the push.
 *
 * If the ingestion is unsorted, this class is using the regular RocksDB interface to support update
 * operations.
 */
class RocksDBStoragePartition extends AbstractStoragePartition {
  private static Logger LOGGER = Logger.getLogger(RocksDBStoragePartition.class);

  /**
   * This field is being stored during offset checkpointing in {@link com.linkedin.venice.kafka.consumer.StoreIngestionTask}.
   * With the field, RocksDB could recover properly during restart.
   *
   * Essentially, during recovery, this class will remove all the un-committed files after {@link #ROCKSDB_LAST_FINISHED_SST_FILE_NO},
   * and start a new file with no: {@link #ROCKSDB_LAST_FINISHED_SST_FILE_NO} + 1.
   * With this way, we could achieve exact-once ingestion, which is required by {@link SstFileWriter}.
   */
  protected static final String ROCKSDB_LAST_FINISHED_SST_FILE_NO = "rocksdb_last_finished_sst_file_no";

  private static final FlushOptions WAIT_FOR_FLUSH_OPTIONS = new FlushOptions().setWaitForFlush(true);
  /**
   * Here RocksDB disables WAL, but relies on the 'flush', which will be invoked through {@link #sync()}
   * to avoid data loss during recovery.
   */
  private static final WriteOptions DISABLE_WAL_OPTIONS = new WriteOptions().setDisableWAL(true);

  private static final String METADATA_COLUMN_FAMILY_NAME = "metadataColumnFamily";


  private int lastFinishedSSTFileNo = -1;
  private int currentSSTFileNo = 0;
  private SstFileWriter currentSSTFileWriter;
  private long recordNumInCurrentSSTFile = 0;
  private final String fullPathForTempSSTFileDir;
  private final EnvOptions envOptions;


  private final String storeName;
  private final int partitionId;
  private final String fullPathForPartitionDB;

  private final ColumnFamilyHandle metadataColumnFamilyHandle;

  /**
   * The passed in {@link Options} instance.
   * For now, the RocksDB version being used right now doesn't support shared block cache unless
   * all the RocksDB databases reuse the same {@link Options} instance, which is not great.
   *
   * Once the latest version: https://github.com/facebook/rocksdb/releases/tag/v5.12.2 is available
   * in maven repo, we could setup a separate {@link Options} for each RocksDB database to specify
   * customized config, such as:
   * 1. Various block size;
   * 2. Read-only for batch-only store;
   *
   */
  private final Options options;
  private final RocksDB rocksDB;

  /**
   * Whether the input is sorted or not.
   */
  private final boolean deferredWrite;

  /**
   * Whether the database is read only or not.
   */
  private final boolean readOnly;

  /**
   * Whether this parition is storing metadata in addition to data or just data
   */
  private final boolean storingMetadata;

  public RocksDBStoragePartition(StoragePartitionConfig storagePartitionConfig, Options options, String dbDir) {
    super(storagePartitionConfig.getPartitionId());

    // Create the folder for storage partition if it doesn't exist
    this.storeName = storagePartitionConfig.getStoreName();
    this.partitionId = storagePartitionConfig.getPartitionId();
    this.deferredWrite = storagePartitionConfig.isDeferredWrite();
    this.readOnly = storagePartitionConfig.isReadOnly();
    this.fullPathForPartitionDB = RocksDBUtils.composePartitionDbDir(dbDir, storeName, partitionId);
    this.fullPathForTempSSTFileDir = RocksDBUtils.composeTempSSTFileDir(dbDir, storeName, partitionId);
    this.options = options;
    this.storingMetadata = storagePartitionConfig.isStoringMetadata();
    /**
     * TODO: check whether we should tune any config with {@link EnvOptions}.
     */
    this.envOptions = new EnvOptions();
    // Direct write is not efficient when there are a lot of ongoing pushes
    this.envOptions.setUseDirectWrites(false);
    try {
      if (this.readOnly) {
        this.rocksDB = RocksDB.openReadOnly(options, fullPathForPartitionDB);
      } else {
        this.rocksDB = RocksDB.open(options, fullPathForPartitionDB);
      }
      if (this.storingMetadata) {
        this.metadataColumnFamilyHandle = rocksDB.createColumnFamily(new ColumnFamilyDescriptor(METADATA_COLUMN_FAMILY_NAME.getBytes()));
      } else {
        this.metadataColumnFamilyHandle = null;
      }
    } catch (RocksDBException e) {
      throw new VeniceException("Failed to open RocksDB for store: " + storeName + ", partition id: " + partitionId, e);
    }
    LOGGER.info("Opened RocksDB for store: " + storeName + ", partition id: " + partitionId + " in "
        + (this.readOnly ? "read only" : "read write") + " mode and " + (this.deferredWrite ? "deferred write" : " non deferred write") + " mode");
  }

  private void makeSureAllPreviousSSTFilesBeforeCheckpointingExist() {
    if (lastFinishedSSTFileNo < 0) {
      LOGGER.info("Since last finished sst file no is negative, there is nothing to verify");
      return;
    }
    for (int cur = 0; cur <= lastFinishedSSTFileNo; ++cur) {
      String sstFilePath = composeFullPathForSSTFile(cur);
      File sstFile = new File(sstFilePath);
      if (!sstFile.exists()) {
        throw new VeniceException("SST File: " + sstFilePath + " doesn't exist, but last finished sst file no is: " + lastFinishedSSTFileNo);
      }
    }
  }

  private void removeSSTFilesAfterCheckpointing() {
    File tempSSTFileDir = new File(fullPathForTempSSTFileDir);
    String[] sstFiles = tempSSTFileDir.list((File dir, String name) -> RocksDBUtils.isTempSSTFile(name));

    for (String sstFile : sstFiles) {
      int sstFileNo = RocksDBUtils.extractTempSSTFileNo(sstFile);
      if (sstFileNo > lastFinishedSSTFileNo) {
        String fullPathForSSTFile = fullPathForTempSSTFileDir + File.separator + sstFile;
        LOGGER.info("Removing sst file: " + fullPathForSSTFile + " since it is after checkpointed SST file no: " + lastFinishedSSTFileNo);
        boolean ret = new File(fullPathForSSTFile).delete();
        if (!ret) {
          throw new VeniceException("Failed to delete file: " + fullPathForSSTFile);
        }
        LOGGER.info("Removed sst file: " + fullPathForSSTFile + " since it is after checkpointed SST file no: " + lastFinishedSSTFileNo);
      }
    }
  }

  @Override
  public synchronized void beginBatchWrite(Map<String, String> checkpointedInfo) {
    if (!deferredWrite) {
      LOGGER.info("'beginBatchWrite' will do nothing since 'deferredWrite' is disabled");
      return;
    }
    LOGGER.info("'beginBatchWrite' got invoked for RocksDB store: " + storeName + ", partition: " + partitionId +
        " with checkpointed info: " + checkpointedInfo);
    // Create temp SST file dir if it doesn't exist
    File tempSSTFileDir = new File(fullPathForTempSSTFileDir);
    if (!tempSSTFileDir.exists()) {
      tempSSTFileDir.mkdirs();
    }
    if (!checkpointedInfo.containsKey(ROCKSDB_LAST_FINISHED_SST_FILE_NO)) {
      LOGGER.info("No checkpointed info for store: " + storeName + ", partition id: " + partitionId +
          ", so RocksDB will start building sst file from beginning");
      lastFinishedSSTFileNo = -1;
      currentSSTFileNo = 0;
    } else {
      lastFinishedSSTFileNo = Integer.parseInt(checkpointedInfo.get(ROCKSDB_LAST_FINISHED_SST_FILE_NO));
      LOGGER.info("Received last finished sst file no: " + lastFinishedSSTFileNo + " for store: "
          + storeName + ", partition id: " + partitionId);
      if (lastFinishedSSTFileNo < 0) {
        throw new VeniceException("Last finished sst file no: " + lastFinishedSSTFileNo + " shouldn't be negative");
      }
      makeSureAllPreviousSSTFilesBeforeCheckpointingExist();
      removeSSTFilesAfterCheckpointing();
      currentSSTFileNo = lastFinishedSSTFileNo + 1;
    }
    String fullPathForCurrentSSTFile = composeFullPathForSSTFile(currentSSTFileNo);
    currentSSTFileWriter = new SstFileWriter(envOptions, options);
    try {
      currentSSTFileWriter.open(fullPathForCurrentSSTFile);
    } catch (RocksDBException e) {
      throw new VeniceException("Failed to open file: " + fullPathForCurrentSSTFile + " with SstFileWriter");
    }
  }

  private String composeFullPathForSSTFile(int sstFileNo) {
    return fullPathForTempSSTFileDir + File.separator +
        RocksDBUtils.composeTempSSTFileName(sstFileNo);
  }

  @Override
  public synchronized void endBatchWrite() {
    if (!deferredWrite) {
      LOGGER.info("'endBatchWrite' will do nothing since 'deferredWrite' is disabled");
      return;
    }
    /**
     * Sync all the SST files before ingestion.
     */
    sync();
    /**
     * Ingest all the generated sst files into RocksDB database.
     *
     * Note: this function should be invoked after {@link #sync()} to make sure
     * the last SST file written is finished.
     */
    File tempSSTFileDir = new File(fullPathForTempSSTFileDir);
    String[] sstFiles = tempSSTFileDir.list(
        (dir, name) -> RocksDBUtils.isTempSSTFile(name) && new File(dir, name).length() > 0);
    List<String> sstFilePaths = new ArrayList<>();
    for (String sstFile : sstFiles) {
      sstFilePaths.add(tempSSTFileDir + File.separator + sstFile);
    }
    if (0 == sstFilePaths.size()) {
      LOGGER.info("No valid sst file found, so will skip the sst file ingestion for store: " + storeName + ", partition: " + partitionId);
      return;
    }
    LOGGER.info("Start ingesting to store: " + storeName + ", partition id: " + partitionId +
        " from files: " + sstFilePaths);
    try (IngestExternalFileOptions ingestOptions = new IngestExternalFileOptions()) {
      ingestOptions.setMoveFiles(true);
      rocksDB.ingestExternalFile(sstFilePaths, ingestOptions);
      LOGGER.info("Finished ingestion to store: " + storeName + ", partition id: " + partitionId +
          " from files: " + sstFilePaths);
    } catch (RocksDBException e) {
      throw new VeniceException("Received exception during RocksDB#ingestExternalFile", e);
    }
  }

  @Override
  public synchronized void put(byte[] key, byte[] value) {
    try {
      if (deferredWrite) {
        if (null == currentSSTFileWriter) {
          throw new VeniceException("currentSSTFileWriter is null for store: " + storeName + ", partition id: "
              + partitionId + ", 'beginBatchWrite' should be invoked before any write");
        }
        currentSSTFileWriter.put(key, value);
        ++recordNumInCurrentSSTFile;
      } else {
        rocksDB.put(DISABLE_WAL_OPTIONS, key, value);
      }
    } catch (RocksDBException e) {
      throw new VeniceException("Failed to put key/value pair to store: " + storeName + ", partition id: " + partitionId, e);
    }
  }

  @Override
  public synchronized void put(byte[] key, ByteBuffer valueBuffer) {
    /**
     * The reason to create a new byte array to contain the value is that the overhead to create/release
     * {@link Slice} and {@link org.rocksdb.DirectSlice} is high since the creation/release are JNI operation.
     *
     * In the future, if {@link SstFileWriter#put} supports byte array with offset/length, then we don't need
     * to create a byte array copy here.
     * Same for {@link RocksDB#put}.
     */
    put(key, ByteUtils.extractByteArray(valueBuffer));
  }

  public void putMetadata(byte[] key, byte[] value) {
    if (!this.storingMetadata) {
      throw new VeniceException("putMetadata is not support when storingMetadata is set to false!");
    }

    try {
        rocksDB.put(metadataColumnFamilyHandle, DISABLE_WAL_OPTIONS, key, value);
    } catch (RocksDBException e) {
      throw new VeniceException("Failed to put partitionId/offset pair to store: " + storeName + ", partition id: " + partitionId, e);
    }
  }

  @Override
  public byte[] get(byte[] key) {
    try {
      return rocksDB.get(key);
    } catch (RocksDBException e) {
      throw new VeniceException("Failed to get value from store: " + storeName + ", partition id: " + partitionId, e);
    }
  }

  @Override
  public byte[] get(ByteBuffer keyBuffer) {
    /**
     * The reason to create a new byte array to contain the key is that the overhead to create/release
     * {@link Slice} and {@link org.rocksdb.DirectSlice} is high since the creation/release are JNI operation.
     *
     * In the future, if {@link RocksDB#get} supports byte array with offset/length, then we don't need
     * to create a byte array copy here.
     */
    return get(ByteUtils.extractByteArray(keyBuffer));
  }

  public byte[] getMetadata(byte[] key) {
    if (!this.storingMetadata) {
      throw new VeniceException("getMetadata is not support when storingMetadata is set to false!");
    }

    try {
      return rocksDB.get(metadataColumnFamilyHandle, key);
    } catch (RocksDBException e) {
      throw new VeniceException("Failed to get offset from store: " + storeName + ", partition id: " + partitionId, e);
    }
  }

  @Override
  public synchronized void delete(byte[] key) {
    try {
      if (deferredWrite) {
        throw new VeniceException("Deletion is unexpected in 'deferredWrite' mode");
      } else {
        rocksDB.delete(key);
      }
    } catch (RocksDBException e) {
      throw new VeniceException("Failed to delete entry from store: " + storeName + ", partition id: " + partitionId, e);
    }
  }

  @Override
  public synchronized Map<String, String> sync() {
    if (!deferredWrite) {
      LOGGER.debug("Flush memtable to disk for store: " + storeName + ", partition id: " + partitionId);

      if (this.readOnly) {
        LOGGER.warn("Unexpected sync in RocksDB read-only mode");
      } else {
        try {
          // Since Venice RocksDB database disables WAL, flush will be triggered for every 'sync' to avoid data loss during
          // crash recovery
          rocksDB.flush(WAIT_FOR_FLUSH_OPTIONS);
        } catch (RocksDBException e) {
          throw new VeniceException("Failed to flush memtable to disk for store: " + storeName + ", partition id: " + partitionId, e);
        }
      }
      return Collections.emptyMap();
    }

    try {
      /**
       * {@link SstFileWriter#finish()} will throw exception if the current SST file is empty.
       */
      if (recordNumInCurrentSSTFile > 0) {
        currentSSTFileWriter.finish();
        lastFinishedSSTFileNo = currentSSTFileNo;
        ++currentSSTFileNo;
        final String fullPathForLastFinishedSSTFile = composeFullPathForSSTFile(lastFinishedSSTFileNo);
        final String fullPathForCurrentSSTFile = composeFullPathForSSTFile(currentSSTFileNo);
        currentSSTFileWriter.open(fullPathForCurrentSSTFile);
        LOGGER.info("Sync gets invoked for store: " + storeName + ", partition id: " + partitionId
            + ", last finished sst file: " + fullPathForLastFinishedSSTFile + ", current sst file: "
            + fullPathForCurrentSSTFile);
        recordNumInCurrentSSTFile = 0;
      } else {
        LOGGER.warn("Sync get invoked for store: " + storeName + ", partition id: " + partitionId
            +", but the last sst file: " + composeFullPathForSSTFile(currentSSTFileNo) + " is empty");
      }
    } catch (RocksDBException e) {
      throw new VeniceException("Failed to sync SstFileWriter", e);
    }
    /**
     * Return the recovery related info to upper layer to checkpoint.
     */
    Map<String, String> checkpointingInfo = new HashMap<>();
    if (lastFinishedSSTFileNo >= 0) {
      checkpointingInfo.put(ROCKSDB_LAST_FINISHED_SST_FILE_NO, Integer.toString(lastFinishedSSTFileNo));
    }
    return checkpointingInfo;
  }

  private void removeDirWithTwoLayers(String fullPath) {
    File dir = new File(fullPath);
    if (dir.exists()) {
      // Remove the files inside first
      Arrays.stream(dir.list()).forEach(file -> {
        if (!(new File(fullPath, file).delete())) {
          LOGGER.warn("Failed to remove file: " + file + " in dir: " + fullPath);
        }
      });
      // Remove file directory
      if (!dir.delete()) {
        LOGGER.warn("Failed to remove dir: " + fullPath);
      }
    }
  }

  @Override
  public synchronized void drop() {
    close();
    try {
      RocksDB.destroyDB(fullPathForPartitionDB, options);
    } catch (RocksDBException e) {
      LOGGER.error("Failed to destroy DB for store: " + storeName + ", partition id: " + partitionId);
    }
    /**
     * To avoid resource leaking, we will clean up all the database files anyway.
     */
    // Remove extra SST files first
    removeDirWithTwoLayers(fullPathForTempSSTFileDir);

    // Remove partition directory
    removeDirWithTwoLayers(fullPathForPartitionDB);

    LOGGER.info("RocksDB for store: " + storeName + ", partition: " + partitionId + " was dropped");
  }

  @Override
  public synchronized void close() {
    /**
     * The following operations are used to free up memory.
     */
    rocksDB.close();
    if (null != envOptions) {
      envOptions.close();
    }
    if (null != currentSSTFileWriter) {
      currentSSTFileWriter.close();
    }
    LOGGER.info("RocksDB for store: " + storeName + ", partition: " + partitionId + " was closed");
  }

  /**
   * Check {@link AbstractStoragePartition#verifyConfig(StoragePartitionConfig)}.
   *
   * @param storagePartitionConfig
   * @return
   */
  @Override
  public boolean verifyConfig(StoragePartitionConfig storagePartitionConfig) {
    return deferredWrite == storagePartitionConfig.isDeferredWrite() && readOnly == storagePartitionConfig.isReadOnly()
        && storingMetadata == storagePartitionConfig.isStoringMetadata();
  }

  /**
   * This method calculates the file size by adding all subdirectories size
   * @return the partition db size in bytes
   */
  @Override
  public long getPartitionSizeInBytes() {
    File partitionDbDir = new File(fullPathForPartitionDB);
    if (partitionDbDir.exists()) {
      /**
       * {@link FileUtils#sizeOf(File)} will throw {@link IllegalArgumentException} if the file/dir doesn't exist.
       */
      return FileUtils.sizeOf(partitionDbDir);
    } else {
      return 0;
    }
  }
}
