/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.pcap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.types.TypeProtos;
import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.common.types.Types;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.expr.TypeHelper;
import org.apache.drill.exec.ops.OperatorContext;
import org.apache.drill.exec.physical.impl.OutputMutator;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.store.AbstractRecordReader;
import org.apache.drill.exec.store.pcap.decoder.Packet;
import org.apache.drill.exec.store.pcap.decoder.PacketDecoder;
import org.apache.drill.exec.store.pcap.dto.ColumnDto;
import org.apache.drill.exec.store.pcap.schema.PcapTypes;
import org.apache.drill.exec.store.pcap.schema.Schema;
import org.apache.drill.exec.vector.NullableBigIntVector;
import org.apache.drill.exec.vector.NullableIntVector;
import org.apache.drill.exec.vector.NullableTimeStampVector;
import org.apache.drill.exec.vector.NullableVarCharVector;
import org.apache.drill.exec.vector.ValueVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.drill.exec.store.pcap.PcapFormatUtils.parseBytesToASCII;

public class PcapRecordReader extends AbstractRecordReader {
  private static final Logger logger = LoggerFactory.getLogger(PcapRecordReader.class);

  private static final int BATCH_SIZE = 40_000;

  private OutputMutator output;

  private PacketDecoder decoder;
  private ImmutableList<ProjectedColumnInfo> projectedCols;

  private byte[] buffer;
  private int offset = 0;
  private InputStream in;
  private int validBytes;

  private String inputPath;
  private List<SchemaPath> projectedColumns;

  private static final Map<PcapTypes, MinorType> TYPES;

  private static class ProjectedColumnInfo {
    ValueVector vv;
    ColumnDto pcapColumn;
  }

  static {
    TYPES = ImmutableMap.<PcapTypes, TypeProtos.MinorType>builder()
        .put(PcapTypes.STRING, MinorType.VARCHAR)
        .put(PcapTypes.INTEGER, MinorType.INT)
        .put(PcapTypes.LONG, MinorType.BIGINT)
        .put(PcapTypes.TIMESTAMP, MinorType.TIMESTAMP)
        .build();
  }

  public PcapRecordReader(final String inputPath,
                          final List<SchemaPath> projectedColumns) {
    this.inputPath = inputPath;
    this.projectedColumns = projectedColumns;
  }

  @Override
  public void setup(final OperatorContext context, final OutputMutator output) throws ExecutionSetupException {
    try {

      this.output = output;
      this.buffer = new byte[100000];
      this.in = new FileInputStream(inputPath);
      this.decoder = new PacketDecoder(in);
      this.validBytes = in.read(buffer);
      this.projectedCols = getProjectedColsIfItNull();
      setColumns(projectedColumns);
    } catch (IOException io) {
      throw UserException.dataReadError(io)
          .addContext("File name:", inputPath)
          .build(logger);
    }
  }

  @Override
  public int next() {
    try {
      return parsePcapFilesAndPutItToTable();
    } catch (IOException io) {
      throw UserException.dataReadError(io)
          .addContext("Trouble with reading packets in file!")
          .build(logger);
    }
  }

  @Override
  public void close() throws Exception {
//    buffer = null;
//    in.close();
  }

  private ImmutableList<ProjectedColumnInfo> getProjectedColsIfItNull() {
    return projectedCols != null ? projectedCols : initCols(new Schema());
  }

  private ImmutableList<ProjectedColumnInfo> initCols(final Schema schema) {
    ImmutableList.Builder<ProjectedColumnInfo> pciBuilder = ImmutableList.builder();
    ColumnDto column;

    for (int i = 0; i < schema.getNumberOfColumns(); i++) {
      column = schema.getColumnByIndex(i);

      final String name = column.getColumnName().toLowerCase();
      final PcapTypes type = column.getColumnType();
      TypeProtos.MinorType minorType = TYPES.get(type);

      ProjectedColumnInfo pci = getProjectedColumnInfo(column, name, minorType);
      pciBuilder.add(pci);
    }
    return pciBuilder.build();
  }

  private ProjectedColumnInfo getProjectedColumnInfo(final ColumnDto column,
                                                     final String name,
                                                     final MinorType minorType) {
    TypeProtos.MajorType majorType = getMajorType(minorType);

    MaterializedField field =
        MaterializedField.create(name, majorType);

    ValueVector vector =
        getValueVector(minorType, majorType, field);

    return getProjectedColumnInfo(column, vector);
  }

  private ProjectedColumnInfo getProjectedColumnInfo(final ColumnDto column, final ValueVector vector) {
    ProjectedColumnInfo pci = new ProjectedColumnInfo();
    pci.vv = vector;
    pci.pcapColumn = column;
    return pci;
  }

  private MajorType getMajorType(final MinorType minorType) {
    return Types.optional(minorType);
  }

  private ValueVector getValueVector(final MinorType minorType,
                                     final MajorType majorType,
                                     final MaterializedField field) {
    try {

      final Class<? extends ValueVector> clazz = TypeHelper.getValueVectorClass(
          minorType, majorType.getMode());
      ValueVector vector = output.addField(field, clazz);
      vector.allocateNew();
      return vector;

    } catch (SchemaChangeException sce) {
      throw new IllegalStateException("The addition of this field is incompatible with this OutputMutator's capabilities");
    }
  }

  private int parsePcapFilesAndPutItToTable() throws IOException {
    Packet packet = new Packet();
    int counter = 0;
    while (offset < validBytes && counter != BATCH_SIZE) {

      if (validBytes - offset < 9000) {
        System.arraycopy(buffer, offset, buffer, 0, validBytes - offset);
        validBytes = validBytes - offset;
        offset = 0;

        int n = in.read(buffer, validBytes, buffer.length - validBytes);
        if (n > 0) {
          validBytes += n;
        }
      }

      offset = decoder.decodePacket(buffer, offset, packet);

      if (addDataToTable(packet, decoder.getNetwork(), counter)) {
        counter++;
      }
    }
    return counter;
  }

  private boolean addDataToTable(final Packet packet, final int networkType, final int count) {
    for (ProjectedColumnInfo pci : projectedCols) {
      switch (pci.pcapColumn.getColumnName()) {
        case "type":
          setStringColumnValue(packet.getPacketType(), pci, count);
          break;
        case "timestamp":
          setTimestampColumnValue(packet.getTimestamp(), pci, count);
          break;
        case "network":
          setIntegerColumnValue(networkType, pci, count);
          break;
        case "src_mac_address":
          setStringColumnValue(packet.getEthernetSource(), pci, count);
          break;
        case "dst_mac_address":
          setStringColumnValue(packet.getEthernetDestination(), pci, count);
          break;
        case "dst_ip":
          if (packet.getDst_ip() != null) {
            setStringColumnValue(packet.getDst_ip().getHostAddress(), pci, count);
          } else {
            setStringColumnValue(null, pci, count);
          }
          break;
        case "src_ip":
          if (packet.getSrc_ip() != null) {
            setStringColumnValue(packet.getSrc_ip().getHostAddress(), pci, count);
          } else {
            setStringColumnValue(null, pci, count);
          }
          break;
        case "src_port":
          setIntegerColumnValue(packet.getSrc_port(), pci, count);
          break;
        case "dst_port":
          setIntegerColumnValue(packet.getDst_port(), pci, count);
          break;
        case "tcp_session":
          if (packet.isTcpPacket()) {
            setLongColumnValue(packet.getSessionHash(), pci, count);
          }
          break;
        case "tcp_sequence":
          if (packet.isTcpPacket()) {
            setIntegerColumnValue(packet.getSequenceNumber(), pci, count);
          }
          break;
        case "packet_length":
          setIntegerColumnValue(packet.getPacketLength(), pci, count);
          break;
        case "data":
          if (packet.getData() != null) {
            setStringColumnValue(parseBytesToASCII(packet.getData()), pci, count);
          } else {
            setStringColumnValue("[]", pci, count);
          }
          break;
      }
    }
    return true;
  }

  private void setLongColumnValue(long data, ProjectedColumnInfo pci, final int count) {
    ((NullableBigIntVector.Mutator) pci.vv.getMutator())
        .setSafe(count, data);
  }

  private void setIntegerColumnValue(final int data, final ProjectedColumnInfo pci, final int count) {
    ((NullableIntVector.Mutator) pci.vv.getMutator())
        .setSafe(count, data);
  }

  private void setTimestampColumnValue(final long data, final ProjectedColumnInfo pci, final int count) {
    ((NullableTimeStampVector.Mutator) pci.vv.getMutator())
        .setSafe(count, data);
  }

  private void setStringColumnValue(final String data, final ProjectedColumnInfo pci, final int count) {
    if (data == null) {
      ((NullableVarCharVector.Mutator) pci.vv.getMutator())
          .setNull(count);
    } else {
      ByteBuffer value = ByteBuffer.wrap(data.getBytes(UTF_8));
      ((NullableVarCharVector.Mutator) pci.vv.getMutator())
          .setSafe(count, value, 0, value.remaining());
    }
  }
}