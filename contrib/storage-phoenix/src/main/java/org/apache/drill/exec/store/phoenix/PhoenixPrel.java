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
package org.apache.drill.exec.store.phoenix;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.exec.physical.base.PhysicalOperator;
import org.apache.drill.exec.planner.physical.PhysicalPlanCreator;
import org.apache.drill.exec.planner.physical.Prel;
import org.apache.drill.exec.planner.physical.visitor.PrelVisitor;
import org.apache.drill.exec.record.BatchSchema.SelectionVectorMode;
import org.apache.phoenix.calcite.PhoenixTable;
import org.apache.phoenix.calcite.rel.PhoenixTableScan;

/**
 * Represents a JDBC Plan once the children nodes have been rewritten into SQL.
 */
public class PhoenixPrel extends AbstractRelNode implements Prel {

  private final String hbaseTableName;
  private final double rows;
  private final PhoenixTableScan tableScan;

  public PhoenixPrel(RelOptCluster cluster, RelTraitSet traitSet, PhoenixIntermediatePrel prel) {
    super(cluster, traitSet);

    final RelNode input = prel.getInput();
    rows = input.getRows();
    tableScan = (PhoenixTableScan) prel.getInput();
    hbaseTableName = tableScan.getTable().unwrap(PhoenixTable.class).pTable.getName().getString();

  }

  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    final String storagePluginName = tableScan.getTable().getQualifiedName().iterator().next();
    try {
      PhoenixStoragePlugin plugin = (PhoenixStoragePlugin) creator.getContext().getStorage()
          .getPlugin(storagePluginName);
      return new PhoenixGroupScan(hbaseTableName, plugin, rows);
    } catch (ExecutionSetupException e) {
      throw new IOException(String.format("Failure while retrieving storage plugin %s", storagePluginName), e);
    }
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw).item("table", hbaseTableName);
  }

  @Override
  public double getRows() {
    return rows;
  }

  @Override
  public Iterator<Prel> iterator() {
    return Collections.emptyIterator();
  }

  @Override
  public <T, X, E extends Throwable> T accept(PrelVisitor<T, X, E> logicalVisitor, X value) throws E {
    return logicalVisitor.visitPrel(this, value);
  }

  @Override
  public SelectionVectorMode[] getSupportedEncodings() {
    return SelectionVectorMode.DEFAULT;
  }

  @Override
  public SelectionVectorMode getEncoding() {
    return SelectionVectorMode.NONE;
  }

  @Override
  public boolean needsFinalColumnReordering() {
    return false;
  }
}
