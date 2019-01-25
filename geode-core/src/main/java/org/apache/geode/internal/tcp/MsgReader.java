/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.tcp;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import org.apache.logging.log4j.Logger;

import org.apache.geode.distributed.internal.DMStats;
import org.apache.geode.distributed.internal.DistributionMessage;
import org.apache.geode.distributed.internal.ReplyProcessor21;
import org.apache.geode.internal.Assert;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.Version;
import org.apache.geode.internal.logging.LogService;
import org.apache.geode.internal.net.Buffers;
import org.apache.geode.internal.net.NioFilter;

/**
 * This class is currently used for reading direct ack responses It should probably be used for all
 * of the reading done in Connection.
 *
 */
public class MsgReader {
  private static final Logger logger = LogService.getLogger();

  protected final Connection conn;
  protected final Header header = new Header();
  private final NioFilter ioFilter;
  private ByteBuffer peerNetData;
  private final ByteBufferInputStream byteBufferInputStream;



  MsgReader(Connection conn, NioFilter nioFilter, ByteBuffer peerNetData, Version version) {
    this.conn = conn;
    this.ioFilter = nioFilter;
    this.peerNetData = peerNetData;
    if (conn.getConduit().useSSL()) {
      ByteBuffer buffer = ioFilter.getUnwrappedBuffer(peerNetData);
      buffer.position(0).limit(0);
    }
    this.byteBufferInputStream =
        version == null ? new ByteBufferInputStream() : new VersionedByteBufferInputStream(version);
  }

  Header readHeader() throws IOException {
    ByteBuffer unwrappedBuffer = readAtLeast(Connection.MSG_HEADER_BYTES);

    // logger.info("BRUCE: MsgReader.readHeader buffer position {} limit {}",
    // unwrappedBuffer.position(), unwrappedBuffer.limit());
    Assert.assertTrue(unwrappedBuffer.remaining() >= Connection.MSG_HEADER_BYTES);

    int position = unwrappedBuffer.position();
    int limit = unwrappedBuffer.limit();

    try {
      int nioMessageLength = unwrappedBuffer.getInt();
      /* nioMessageVersion = */
      Connection.calcHdrVersion(nioMessageLength);
      nioMessageLength = Connection.calcMsgByteSize(nioMessageLength);
      byte nioMessageType = unwrappedBuffer.get();
      short nioMsgId = unwrappedBuffer.getShort();

      boolean directAck = (nioMessageType & Connection.DIRECT_ACK_BIT) != 0;
      if (directAck) {
        nioMessageType &= ~Connection.DIRECT_ACK_BIT; // clear the ack bit
      }

      header.setFields(nioMessageLength, nioMessageType, nioMsgId);

      return header;
    } catch (BufferUnderflowException e) {
      dumpState("BufferUnderflowException", e, unwrappedBuffer, position, limit);
      throw e;
    } finally {
      dumpState("readHeader", null, unwrappedBuffer, position, limit);
    }

  }

  /**
   * Block until you can read a message. Returns null if the message was a message chunk.
   *
   * @return the message, or null if we only received a chunk of the message
   */
  DistributionMessage readMessage(Header header)
      throws IOException, ClassNotFoundException {
    ByteBuffer nioInputBuffer = readAtLeast(header.messageLength);
    Assert.assertTrue(nioInputBuffer.remaining() >= header.messageLength);
    this.getStats().incMessagesBeingReceived(true, header.messageLength);
    long startSer = this.getStats().startMsgDeserialization();
    int position = nioInputBuffer.position();
    int limit = nioInputBuffer.limit();
    try {
      byteBufferInputStream.setBuffer(nioInputBuffer);
      ReplyProcessor21.initMessageRPId();
//      dumpState("readMessage ready to deserialize", null, nioInputBuffer, position, limit);
      return (DistributionMessage) InternalDataSerializer.readDSFID(byteBufferInputStream);
    } catch (RuntimeException e) {
      dumpState("readMessage(1)", e, nioInputBuffer, position, limit);
      throw e;
    } catch (IOException e) {
      dumpState("readMessage(2)", e, nioInputBuffer, position, limit);
      throw e;
    } finally {
      this.getStats().endMsgDeserialization(startSer);
      this.getStats().decMessagesBeingReceived(header.messageLength);
      ioFilter.doneReading(nioInputBuffer);
    }
  }

  private void dumpState(String whereFrom, Throwable e, ByteBuffer inputBuffer, int position,
      int limit) {
     logger.info("BRUCE: {}, Connection to {}", whereFrom, conn.getRemoteAddress());
     logger.info("BRUCE: {}, Message length {}; type {}; id {}",
     whereFrom, header.messageLength, header.messageId, header.messageId);
     logger.info("BRUCE: {}, starting buffer position {}; buffer limit {} buffer hash {}",
     whereFrom, position, limit, Integer.toHexString(System.identityHashCode(inputBuffer)));
     logger.info("BRUCE: {}, current buffer position {}; buffer limit {}",
     whereFrom, inputBuffer.position(), inputBuffer.limit());
     if (e != null) {
     logger.info("BRUCE: Exception reading message", e);
     }
  }

  void readChunk(Header header, MsgDestreamer md)
      throws IOException {
    ByteBuffer unwrappedBuffer = readAtLeast(header.messageLength);
    this.getStats().incMessagesBeingReceived(md.size() == 0, header.messageLength);
    md.addChunk(unwrappedBuffer, header.messageLength);
    // show that the bytes have been consumed by adjusting the buffer's position
    unwrappedBuffer.position(unwrappedBuffer.position() + header.messageLength);
  }



  private ByteBuffer readAtLeast(int bytes) throws IOException {
    // logger.info("BRUCE: ensureWrappedCapacity need {} buffer {} position {} limit {} capacity
    // {}", bytes,
    // Integer.toHexString(System.identityHashCode(peerNetData)), peerNetData.position(),
    // peerNetData.limit(), peerNetData.capacity());
    peerNetData = ioFilter.ensureWrappedCapacity(bytes, peerNetData,
        Buffers.BufferType.TRACKED_RECEIVER, getStats());
    // logger.info("BRUCE: result buffer {} position {} limit {} capacity {}",
    // Integer.toHexString(System.identityHashCode(peerNetData)), peerNetData.position(),
    // peerNetData.limit(), peerNetData.capacity());
    return ioFilter.readAtLeast(conn.getSocket().getChannel(), bytes, peerNetData, getStats());
  }



  private DMStats getStats() {
    return conn.getConduit().getStats();
  }

  public static class Header {

    private int messageLength;
    private byte messageType;
    private short messageId;

    public void setFields(int nioMessageLength, byte nioMessageType, short nioMsgId) {
      messageLength = nioMessageLength;
      messageType = nioMessageType;
      messageId = nioMsgId;
    }

    int getMessageLength() {
      return messageLength;
    }

    byte getMessageType() {
      return messageType;
    }

    short getMessageId() {
      return messageId;
    }
  }

}
