/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.easy.json;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.apache.drill.common.JSONOptions;
import org.apache.drill.common.exceptions.DrillRuntimeException;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.memory.OutOfMemoryException;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.ops.OperatorContext;
import org.apache.drill.exec.physical.impl.OutputMutator;
import org.apache.drill.exec.store.AbstractRecordReader;
import org.apache.drill.exec.store.dfs.DrillFileSystem;
import org.apache.drill.exec.store.easy.json.JsonProcessor.ReadState;
import org.apache.drill.exec.store.easy.json.reader.CountingJsonReader;
import org.apache.drill.exec.vector.BaseValueVector;
import org.apache.drill.exec.vector.complex.fn.JsonReader;
import org.apache.drill.exec.vector.complex.impl.VectorContainerWriter;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

public class JSONRecordReader extends AbstractRecordReader {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(JSONRecordReader.class);

  private OutputMutator mutator;
  private VectorContainerWriter writer;

  // Data we're consuming
  private Path hadoopPath;
  private JsonNode embeddedContent;
  private InputStream stream;
  private DrillFileSystem fileSystem;
  private JsonProcessor jsonReader;
  private int recordCount;
  private FragmentContext fragmentContext;
  private OperatorContext operatorContext;
  private boolean enableAllTextMode;

  /**
   * Create a JSON Record Reader that uses a file based input stream.
   * @param fragmentContext
   * @param inputPath
   * @param fileSystem
   * @param columns
   * @throws OutOfMemoryException
   */
  public JSONRecordReader(FragmentContext fragmentContext, String inputPath, DrillFileSystem fileSystem,
      List<SchemaPath> columns) throws OutOfMemoryException {
    this(fragmentContext, inputPath, null, fileSystem, columns);
  }

  /**
   * Create a new JSON Record Reader that uses a in memory materialized JSON stream.
   * @param fragmentContext
   * @param inputPath
   * @param fileSystem
   * @param columns
   * @throws OutOfMemoryException
   */
  public JSONRecordReader(FragmentContext fragmentContext, JsonNode embeddedContent, DrillFileSystem fileSystem,
      List<SchemaPath> columns) throws OutOfMemoryException {
    this(fragmentContext, null, embeddedContent, fileSystem, columns);
  }

  private JSONRecordReader(FragmentContext fragmentContext, String inputPath, JsonNode embeddedContent, DrillFileSystem fileSystem,
                          List<SchemaPath> columns) throws OutOfMemoryException {

    Preconditions.checkArgument(
        (inputPath == null && embeddedContent != null) ||
        (inputPath != null && embeddedContent == null),
        "One of inputPath or embeddedContent must be set but not both."
        );

    if(inputPath != null){
      this.hadoopPath = new Path(inputPath);
    }else{
      this.embeddedContent = embeddedContent;
    }

    this.fileSystem = fileSystem;
    this.fragmentContext = fragmentContext;

    // only enable all text mode if we aren't using embedded content mode.
    this.enableAllTextMode = embeddedContent == null && fragmentContext.getOptions().getOption(ExecConstants.JSON_READER_ALL_TEXT_MODE_VALIDATOR);
    setColumns(columns);
  }

  private void setupData() throws IOException{
    if(hadoopPath != null){
      CompressionCodecFactory factory = new CompressionCodecFactory(new Configuration());
      CompressionCodec codec = factory.getCodec(hadoopPath); // infers from file ext.
      if (codec != null) {
        this.stream = codec.createInputStream(fileSystem.open(hadoopPath));
      } else {
        this.stream = fileSystem.open(hadoopPath);
      }
    }
  }

  private void setupParser() throws IOException{
    if(hadoopPath != null){
      jsonReader.setSource(stream);
    }else{
      jsonReader.setSource(embeddedContent);
    }
  }

  @Override
  public void setup(OutputMutator output) throws ExecutionSetupException {
    try{
      setupData();
      this.writer = new VectorContainerWriter(output);
      this.mutator = output;
      if (isSkipQuery()) {
        this.jsonReader = new CountingJsonReader(fragmentContext.getManagedBuffer());
      } else {
        this.jsonReader = new JsonReader(fragmentContext.getManagedBuffer(), ImmutableList.copyOf(getColumns()), enableAllTextMode, true);
      }
      setupParser();
    }catch(Exception e){
      handleAndRaise("Failure reading JSON file.", e);
    }
  }

  protected void handleAndRaise(String msg, Exception e) {
    StringBuilder sb = new StringBuilder();
    sb.append(msg).append(" - In ").append(hadoopPath.toUri().getPath()).append(" parser was at record: ").append(recordCount+1);
    if (e instanceof JsonParseException) {
      JsonParseException ex = JsonParseException.class.cast(e);
      sb.append(" column: ").append(ex.getLocation().getColumnNr());
    }
    throw new DrillRuntimeException(sb.toString(), e);
  }


  public OperatorContext getOperatorContext() {
    return operatorContext;
  }

  public void setOperatorContext(OperatorContext operatorContext) {
    this.operatorContext = operatorContext;
  }

  @Override
  public int next() {
    writer.allocate();
    writer.reset();

    recordCount = 0;
    ReadState write = null;
//    Stopwatch p = new Stopwatch().start();
    try{
      outside: while(recordCount < BaseValueVector.INITIAL_VALUE_ALLOCATION){
        writer.setPosition(recordCount);
        write = jsonReader.write(writer);

        if(write == ReadState.WRITE_SUCCEED){
//          logger.debug("Wrote record.");
          recordCount++;
        }else{
//          logger.debug("Exiting.");
          break outside;
        }

      }

      jsonReader.ensureAtLeastOneField(writer);

      writer.setValueCount(recordCount);
//      p.stop();
//      System.out.println(String.format("Wrote %d records in %dms.", recordCount, p.elapsed(TimeUnit.MILLISECONDS)));


      return recordCount;

    } catch (JsonParseException e) {
      handleAndRaise("Error parsing JSON.", e);
    } catch (IOException e) {
      handleAndRaise("Error reading JSON.", e);
    }
    // this is never reached
    return 0;
  }

  @Override
  public void cleanup() {
    try {
      if(stream != null){
        stream.close();
      }
    } catch (IOException e) {
      logger.warn("Failure while closing stream.", e);
    }
  }

}
