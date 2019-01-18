/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.planner.logical;

import java.math.BigDecimal;
import java.util.List;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeName;

import com.dremio.common.logical.data.Limit;
import com.dremio.common.logical.data.LogicalOperator;
import com.dremio.exec.planner.common.LimitRelBase;
import com.dremio.exec.planner.torel.ConversionContext;

public class LimitRel extends LimitRelBase implements Rel {

  public LimitRel(RelOptCluster cluster, RelTraitSet traitSet, RelNode child, RexNode offset, RexNode fetch) {
    super(cluster, traitSet, child, offset, fetch);
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new LimitRel(getCluster(), traitSet, sole(inputs), offset, fetch);
  }

  @Override
  public LogicalOperator implement(LogicalPlanImplementor implementor) {
    LogicalOperator inputOp = implementor.visitChild(this, 0, getInput());

    // First offset to include into results (inclusive). Null implies it is starting from offset 0
    int first = offset != null ? Math.max(0, RexLiteral.intValue(offset)) : 0;

    // Last offset to stop including into results (exclusive), translating fetch row counts into an offset.
    // Null value implies including entire remaining result set from first offset
    Integer last = fetch != null ? Math.max(0, RexLiteral.intValue(fetch)) + first : null;
    Limit limit = new Limit(first, last);
    limit.setInput(inputOp);
    return limit;
  }

  public static LimitRel convert(Limit limit, ConversionContext context) throws InvalidRelException{
    RelNode input = context.toRel(limit.getInput());
    RexNode first = context.getRexBuilder().makeExactLiteral(BigDecimal.valueOf(limit.getFirst()), context.getTypeFactory().createSqlType(SqlTypeName.INTEGER));
    RexNode last = context.getRexBuilder().makeExactLiteral(BigDecimal.valueOf(limit.getLast()), context.getTypeFactory().createSqlType(SqlTypeName.INTEGER));
    return new LimitRel(context.getCluster(), context.getLogicalTraits(), input, first, last);
  }


}
