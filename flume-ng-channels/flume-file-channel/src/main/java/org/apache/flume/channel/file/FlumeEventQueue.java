/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flume.channel.file;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;

/**
 * Queue of events in the channel. This queue stores only
 * {@link FlumeEventPointer} objects which are represented
 * as 8 byte longs internally. Additionally the queue itself
 * of longs is stored as a memory mapped file with a fixed
 * header and circular queue semantics. The header of the queue
 * contains the timestamp of last sync, the queue size and
 * the head position.
 */
final class FlumeEventQueue {
  private static final Logger LOG = LoggerFactory
  .getLogger(FlumeEventQueue.class);
  private static final int EMPTY = 0;
  private final EventQueueBackingStore backingStore;
  private final String channelNameDescriptor;
  private final InflightEventWrapper inflightTakes;
  private final InflightEventWrapper inflightPuts;

  /**
   * @param capacity max event capacity of queue
   * @throws IOException
   */
  FlumeEventQueue(EventQueueBackingStore backingStore, File inflightTakesFile,
          File inflightPutsFile) throws Exception {
    Preconditions.checkArgument(backingStore.getCapacity() > 0,
        "Capacity must be greater than zero");
    this.channelNameDescriptor = "[channel=" + backingStore.getName() + "]";
    this.backingStore = backingStore;
    try {
      inflightPuts = new InflightEventWrapper(inflightPutsFile);
      inflightTakes = new InflightEventWrapper(inflightTakesFile);
    } catch (Exception e) {
      LOG.error("Could not read checkpoint.", e);
      throw e;
    }
  }

  SetMultimap<Long, Long> deserializeInflightPuts()
          throws IOException, BadCheckpointException{
    return inflightPuts.deserialize();
  }

  SetMultimap<Long, Long> deserializeInflightTakes()
          throws IOException, BadCheckpointException{
    return inflightTakes.deserialize();
  }

  synchronized long getLogWriteOrderID() {
    return backingStore.getLogWriteOrderID();
  }

  synchronized boolean checkpoint(boolean force) throws Exception {
    if (!backingStore.syncRequired()
            && !inflightTakes.syncRequired()
            && !force) { //No need to check inflight puts, since that would
                         //cause elements.syncRequired() to return true.
      LOG.debug("Checkpoint not required");
      return false;
    }
    inflightPuts.serializeAndWrite();
    inflightTakes.serializeAndWrite();
    backingStore.checkpoint();
    return true;
  }

  /**
   * Retrieve and remove the head of the queue.
   *
   * @return FlumeEventPointer or null if queue is empty
   */
  synchronized FlumeEventPointer removeHead(long transactionID) {
    if(backingStore.getSize()  == 0) {
      return null;
    }

    long value = remove(0, transactionID);
    Preconditions.checkState(value != EMPTY, "Empty value "
          + channelNameDescriptor);

    FlumeEventPointer ptr = FlumeEventPointer.fromLong(value);
    backingStore.decrementFileID(ptr.getFileID());
    return ptr;
  }

  /**
   * Add a FlumeEventPointer to the head of the queue.
   * Called during rollbacks.
   * @param FlumeEventPointer to be added
   * @return true if space was available and pointer was
   * added to the queue
   */
  synchronized boolean addHead(FlumeEventPointer e) {
    //Called only during rollback, so should not consider inflight takes' size,
    //because normal puts through addTail method already account for these
    //events since they are in the inflight takes. So puts will not happen
    //in such a way that these takes cannot go back in. If this if returns true,
    //there is a buuuuuuuug!
    if (backingStore.getSize() == backingStore.getCapacity()) {
      LOG.error("Could not reinsert to queue, events which were taken but "
              + "not committed. Please report this issue.");
      return false;
    }

    long value = e.toLong();
    Preconditions.checkArgument(value != EMPTY);
    backingStore.incrementFileID(e.getFileID());

    add(0, value);
    return true;
  }


  /**
   * Add a FlumeEventPointer to the tail of the queue.
   * @param FlumeEventPointer to be added
   * @return true if space was available and pointer
   * was added to the queue
   */
  synchronized boolean addTail(FlumeEventPointer e) {
    if (getSize() == backingStore.getCapacity()) {
      return false;
    }

    long value = e.toLong();
    Preconditions.checkArgument(value != EMPTY);
    backingStore.incrementFileID(e.getFileID());

    add(backingStore.getSize(), value);
    return true;
  }

  /**
   * Must be called when a put happens to the log. This ensures that put commits
   * after checkpoints will retrieve all events committed in that txn.
   * @param e
   * @param transactionID
   */
  synchronized void addWithoutCommit(FlumeEventPointer e, long transactionID) {
    inflightPuts.addEvent(transactionID, e.toLong());
  }

  /**
   * Remove FlumeEventPointer from queue, will normally
   * only be used when recovering from a crash
   * @param FlumeEventPointer to be removed
   * @return true if the FlumeEventPointer was found
   * and removed
   */
  synchronized boolean remove(FlumeEventPointer e) {
    long value = e.toLong();
    Preconditions.checkArgument(value != EMPTY);
    for (int i = 0; i < backingStore.getSize(); i++) {
      if(get(i) == value) {
        remove(i, 0);
        FlumeEventPointer ptr = FlumeEventPointer.fromLong(value);
        backingStore.decrementFileID(ptr.getFileID());
        return true;
      }
    }
    return false;
  }
  /**
   * @return a copy of the set of fileIDs which are currently on the queue
   * will be normally be used when deciding which data files can
   * be deleted
   */
  synchronized SortedSet<Integer> getFileIDs() {
    //Java implements clone pretty well. The main place this is used
    //in checkpointing and deleting old files, so best
    //to use a sorted set implementation.
    SortedSet<Integer> fileIDs =
        new TreeSet<Integer>(backingStore.getReferenceCounts());
    fileIDs.addAll(inflightPuts.getFileIDs());
    fileIDs.addAll(inflightTakes.getFileIDs());
    return fileIDs;
  }

  protected long get(int index) {
    if (index < 0 || index > backingStore.getSize() - 1) {
      throw new IndexOutOfBoundsException(String.valueOf(index)
          + channelNameDescriptor);
    }
    return backingStore.get(index);
  }

  private void set(int index, long value) {
    if (index < 0 || index > backingStore.getSize() - 1) {
      throw new IndexOutOfBoundsException(String.valueOf(index)
          + channelNameDescriptor);
    }
    backingStore.put(index, value);
  }

  protected boolean add(int index, long value) {
    if (index < 0 || index > backingStore.getSize()) {
      throw new IndexOutOfBoundsException(String.valueOf(index)
          + channelNameDescriptor);
    }

    if (backingStore.getSize() == backingStore.getCapacity()) {
      return false;
    }

    backingStore.setSize(backingStore.getSize() + 1);

    if (index <= backingStore.getSize()/2) {
      // Shift left
      backingStore.setHead(backingStore.getHead() - 1);
      if (backingStore.getHead() < 0) {
        backingStore.setHead(backingStore.getCapacity() - 1);
      }
      for (int i = 0; i < index; i++) {
        set(i, get(i+1));
      }
    } else {
      // Sift right
      for (int i = backingStore.getSize() - 1; i > index; i--) {
        set(i, get(i-1));
      }
    }
    set(index, value);
    return true;
  }

  /**
   * Must be called when a transaction is being committed or rolled back.
   * @param transactionID
   */
  synchronized void completeTransaction(long transactionID) {
    if (!inflightPuts.completeTransaction(transactionID)) {
      inflightTakes.completeTransaction(transactionID);
    }
  }

  protected synchronized long remove(int index, long transactionID) {
    if (index < 0 || index > backingStore.getSize() - 1) {
      throw new IndexOutOfBoundsException("index = " + index
          + ", queueSize " + backingStore.getSize() +" " + channelNameDescriptor);
    }
    long value = get(index);
    //if txn id = 0, we are recovering from a crash.
    if(transactionID != 0) {
      inflightTakes.addEvent(transactionID, value);
    }
    if (index > backingStore.getSize()/2) {
      // Move tail part to left
      for (int i = index; i < backingStore.getSize() - 1; i++) {
        long rightValue = get(i+1);
        set(i, rightValue);
      }
      set(backingStore.getSize() - 1, EMPTY);
    } else {
      // Move head part to right
      for (int i = index - 1; i >= 0; i--) {
        long leftValue = get(i);
        set(i+1, leftValue);
      }
      set(0, EMPTY);
      backingStore.setHead(backingStore.getHead() + 1);
      if (backingStore.getHead() == backingStore.getCapacity()) {
        backingStore.setHead(0);
      }
    }
    backingStore.setSize(backingStore.getSize() - 1);
    return value;
  }


  protected synchronized int getSize() {
    return backingStore.getSize() + inflightTakes.getSize();
  }

  /**
   * @return max capacity of the queue
   */
  public int getCapacity() {
    return backingStore.getCapacity();
  }

  synchronized void close() {
    try {
      backingStore.close();
    } catch (IOException e) {
      LOG.warn("Error closing backing store", e);
    }
  }
  /**
   * A representation of in flight events which have not yet been committed.
   * None of the methods are thread safe, and should be called from thread
   * safe methods only.
   */
  class InflightEventWrapper {
    private SetMultimap<Long, Long> inflightEvents = HashMultimap.create();
    private RandomAccessFile file;
    private volatile java.nio.channels.FileChannel fileChannel;
    private final MessageDigest digest;
    private volatile Future<?> future;
    private final File inflightEventsFile;
    private volatile boolean syncRequired = false;
    private SetMultimap<Long, Integer> inflightFileIDs = HashMultimap.create();

    public InflightEventWrapper(File inflightEventsFile) throws Exception{
      if(!inflightEventsFile.exists()){
        Preconditions.checkState(inflightEventsFile.createNewFile(),"Could not"
                + "create inflight events file: "
                + inflightEventsFile.getCanonicalPath());
      }
      this.inflightEventsFile = inflightEventsFile;
      file = new RandomAccessFile(inflightEventsFile, "rw");
      fileChannel = file.getChannel();
      digest = MessageDigest.getInstance("MD5");
    }

    /**
     * Complete the transaction, and remove all events from inflight list.
     * @param transactionID
     */
    public boolean completeTransaction(Long transactionID) {
      if(!inflightEvents.containsKey(transactionID)) {
        return false;
      }
      inflightEvents.removeAll(transactionID);
      inflightFileIDs.removeAll(transactionID);
      syncRequired = true;
      return true;
    }

    /**
     * Add an event pointer to the inflights list.
     * @param transactionID
     * @param pointer
     */
    public void addEvent(Long transactionID, Long pointer){
      inflightEvents.put(transactionID, pointer);
      inflightFileIDs.put(transactionID,
              FlumeEventPointer.fromLong(pointer).getFileID());
      syncRequired = true;
    }

    /**
     * Serialize the set of in flights into a byte longBuffer.
     * @return Returns the checksum of the buffer that is being
     * asynchronously written to disk.
     */
    public void serializeAndWrite() throws Exception {
      //Check if there is a current write happening, if there is abort it.
      if (future != null) {
        try {
          future.cancel(true);
        } catch (Exception e) {
          LOG.warn("Interrupted a write to inflights "
                  + "file: " + inflightEventsFile.getName()
                  + " to start a new write.");
        }
        while (!future.isDone()) {
          TimeUnit.MILLISECONDS.sleep(100);
        }
      }
      Collection<Long> values = inflightEvents.values();
      if(values.isEmpty()){
        file.setLength(0L);
      }
      if(!fileChannel.isOpen()){
        file = new RandomAccessFile(inflightEventsFile, "rw");
        fileChannel = file.getChannel();
      }
      //What is written out?
      //Checksum - 16 bytes
      //and then each key-value pair from the map:
      //transactionid numberofeventsforthistxn listofeventpointers

      try {
        int expectedFileSize = (((inflightEvents.keySet().size() * 2) //For transactionIDs and events per txn ID
                + values.size()) * 8) //Event pointers
                + 16; //Checksum
        //There is no real need of filling the channel with 0s, since we
        //will write the exact nummber of bytes as expected file size.
        file.setLength(expectedFileSize);
        Preconditions.checkState(file.length() == expectedFileSize,
                "Expected File size of inflight events file does not match the "
                + "current file size. Checkpoint is incomplete.");
        file.seek(0);
        final ByteBuffer buffer = ByteBuffer.allocate(expectedFileSize);
        LongBuffer longBuffer = buffer.asLongBuffer();
        for (Long txnID : inflightEvents.keySet()) {
          Set<Long> pointers = inflightEvents.get(txnID);
          longBuffer.put(txnID);
          longBuffer.put((long) pointers.size());
          LOG.debug("Number of events inserted into "
                  + "inflights file: " + String.valueOf(pointers.size())
                  + " file: " + inflightEventsFile.getCanonicalPath());
          long[] written = ArrayUtils.toPrimitive(
                  pointers.toArray(new Long[0]));
          longBuffer.put(written);
        }
        byte[] checksum = digest.digest(buffer.array());
        file.write(checksum);
        future = Executors.newSingleThreadExecutor().submit(
                new Runnable() {
                  @Override
                  public void run() {
                    try {
                      buffer.position(0);
                      fileChannel.write(buffer);
                      fileChannel.force(true);
                    } catch (IOException ex) {
                      LOG.error("Error while writing inflight events to "
                              + "inflights file: "
                              + inflightEventsFile.getName());
                    }
                  }
                });
        syncRequired = false;
      } catch (IOException ex) {
        LOG.error("Error while writing checkpoint to disk.", ex);
        throw ex;
      }
    }

    /**
     * Read the inflights file and return a
     * {@link com.google.common.collect.SetMultimap}
     * of transactionIDs to events that were inflight.
     *
     * @return - map of inflight events per txnID.
     *
     */
    public SetMultimap<Long, Long> deserialize()
            throws IOException, BadCheckpointException {
      SetMultimap<Long, Long> inflights = HashMultimap.create();
      if (!fileChannel.isOpen()) {
        file = new RandomAccessFile(inflightEventsFile, "rw");
        fileChannel = file.getChannel();
      }
      if(file.length() == 0) {
        return inflights;
      }
      file.seek(0);
      byte[] checksum = new byte[16];
      file.read(checksum);
      ByteBuffer buffer = ByteBuffer.allocate(
              (int)(file.length() - file.getFilePointer()));
      fileChannel.read(buffer);
      byte[] fileChecksum = digest.digest(buffer.array());
      if (!Arrays.equals(checksum, fileChecksum)) {
        throw new BadCheckpointException("Checksum of inflights file differs"
                + " from the checksum expected.");
      }
      buffer.position(0);
      LongBuffer longBuffer = buffer.asLongBuffer();
      try {
        while (true) {
          long txnID = longBuffer.get();
          int numEvents = (int)(longBuffer.get());
          for(int i = 0; i < numEvents; i++) {
            long val = longBuffer.get();
            inflights.put(txnID, val);
          }
        }
      } catch (BufferUnderflowException ex) {
        LOG.debug("Reached end of inflights buffer. Long buffer position ="
                + String.valueOf(longBuffer.position()));
      }
      return  inflights;
    }

    public int getSize() {
      return inflightEvents.size();
    }

    public boolean syncRequired(){
      return syncRequired;
    }

    public Collection<Integer> getFileIDs(){
      return inflightFileIDs.values();
    }

    //Needed for testing.
    public Collection<Long> getInFlightPointers() {
      return inflightEvents.values();
    }
  }
}
