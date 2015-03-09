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

<@pp.dropOutputFile />
<@pp.changeOutputFile name="org/apache/drill/exec/store/JSONOutputRecordWriter.java" />
<#include "/@includes/license.ftl" />

package org.apache.drill.exec.store;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.Lists;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.expr.TypeHelper;
import org.apache.drill.exec.expr.holders.*;
import org.apache.drill.exec.record.BatchSchema;
import org.apache.drill.exec.store.EventBasedRecordWriter.FieldConverter;
import org.apache.drill.exec.store.parquet.ParquetTypeHelper;
import org.apache.drill.exec.vector.*;
import org.apache.drill.exec.util.DecimalUtility;
import org.apache.drill.exec.vector.complex.reader.FieldReader;
import parquet.io.api.RecordConsumer;
import parquet.schema.MessageType;
import parquet.io.api.Binary;
import io.netty.buffer.ByteBuf;
import org.apache.drill.exec.memory.TopLevelAllocator;
import org.apache.drill.exec.record.BatchSchema;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.vector.complex.fn.JsonOutput;



import org.apache.drill.common.types.TypeProtos;

import org.joda.time.DateTimeUtils;

import java.io.IOException;
import java.lang.UnsupportedOperationException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Abstract implementation of RecordWriter interface which exposes interface:
 *    {@link #writeHeader(List)}
 *    {@link #addField(int,String)}
 * to output the data in string format instead of implementing addField for each type holder.
 *
 * This is useful for text format writers such as CSV, TSV etc.
 */
public abstract class JSONOutputRecordWriter extends AbstractRecordWriter implements RecordWriter {

  protected JsonOutput gen;

<#list vv.types as type>
  <#list type.minor as minor>
    <#list vv.modes as mode>
  <#assign friendlyType = (minor.friendlyType!minor.boxedType!type.boxedType) />
  @Override
  public FieldConverter getNew${mode.prefix}${minor.class}Converter(int fieldId, String fieldName, FieldReader reader) {
    return new ${mode.prefix}${minor.class}JsonConverter(fieldId, fieldName, reader);
  }

  public class ${mode.prefix}${minor.class}JsonConverter extends FieldConverter {

    public ${mode.prefix}${minor.class}JsonConverter(int fieldId, String fieldName, FieldReader reader) {
      super(fieldId, fieldName, reader);
    }

    @Override
    public void startField() throws IOException {
      gen.writeFieldName(fieldName);
    }

    @Override
    public void writeField() throws IOException {
  <#if mode.prefix == "Repeated" >
    // empty lists are represented by simply not starting a field, rather than starting one and putting in 0 elements
    if (reader.size() == 0) {
      return;
    }
    gen.writeStartArray();
    for (int i = 0; i < reader.size(); i++) {
  <#else>
  </#if>
  
  <#assign typeName = minor.class >
  
  <#switch minor.class>
  <#case "UInt1">
  <#case "UInt2">
  <#case "UInt4">
  <#case "UInt8">
    <#assign typeName = "unsupported">
    <#break>
    
  <#case "Decimal9">
  <#case "Decimal18">
  <#case "Decimal28Sparse">
  <#case "Decimal28Dense">
  <#case "Decimal38Dense">
  <#case "Decimal38Sparse">
    <#assign typeName = "Decimal">
    <#break>
  <#case "Float4">
    <#assign typeName = "Float">
    <#break>
  <#case "Float8">
    <#assign typeName = "Double">
    <#break>
    
  <#case "IntervalDay">
  <#case "IntervalYear">
    <#assign typeName = "Interval">
    <#break>
    
  <#case "Bit">
    <#assign typeName = "Boolean">
    <#break>  

  <#case "TimeStamp">
    <#assign typeName = "Timestamp">
    <#break>  
    
  <#case "VarBinary">
    <#assign typeName = "Binary">
    <#break>  
    
  </#switch>
  
  <#if typeName == "unsupported">
    throw new UnsupportedOperationException("Unable to currently write ${minor.class} type to JSON.");
  <#elseif mode.prefix == "Repeated" >
    gen.write${typeName}(i, reader);
  <#else>
    gen.write${typeName}(reader);
  </#if>

  <#if mode.prefix == "Repeated">
    }
      gen.writeEndArray();
  </#if>
    }
  }
    </#list>
  </#list>
</#list>

}
