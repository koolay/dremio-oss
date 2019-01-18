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
package com.dremio.exec.planner.sql.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.core.Window;
import org.apache.calcite.rel.core.Window.RexWinAggCall;
import org.apache.calcite.rel.logical.LogicalWindow;
import org.apache.calcite.rel.rules.MultiJoin;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeFieldImpl;
import org.apache.calcite.rel.type.RelRecordType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexPermuteInputsShuttle;
import org.apache.calcite.rex.RexVisitor;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.rex.RexWindowBound;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql2rel.RelFieldTrimmer;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.mapping.IntPair;
import org.apache.calcite.util.mapping.Mapping;
import org.apache.calcite.util.mapping.MappingType;
import org.apache.calcite.util.mapping.Mappings;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.calcite.logical.ScanCrel;
import com.dremio.exec.planner.logical.DremioRelFactories;
import com.dremio.exec.planner.logical.FlattenVisitors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class DremioFieldTrimmer extends RelFieldTrimmer {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DremioFieldTrimmer.class);

  private final RelBuilder builder;

  public static DremioFieldTrimmer of(RelOptCluster cluster) {
    RelBuilder builder = DremioRelFactories.CALCITE_LOGICAL_BUILDER.create(cluster, null);
    return new DremioFieldTrimmer(builder);
  }

  private DremioFieldTrimmer(RelBuilder builder) {
    super(null, builder);
    this.builder = builder;
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for {@link ScanCrel}.
   */
  @SuppressWarnings("unused")
  public TrimResult trimFields(
      ScanCrel crel,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {

    if(fieldsUsed.cardinality() == crel.getRowType().getFieldCount()) {
      return result(crel, Mappings.createIdentity(crel.getRowType().getFieldCount()));
    }

    if(fieldsUsed.cardinality() == 0) {
      // do something similar to dummy project but avoid using a scan field. This ensures the scan
      // does a skipAll operation rather than projectin a useless column.
      final RelOptCluster cluster = crel.getCluster();
      final Mapping mapping = Mappings.create(MappingType.INVERSE_SURJECTION, crel.getRowType().getFieldCount(), 1);
      final RexLiteral expr = cluster.getRexBuilder().makeExactLiteral(BigDecimal.ZERO);
      builder.push(crel);
      builder.project(ImmutableList.<RexNode>of(expr), ImmutableList.of("DUMMY"));
      return result(builder.build(), mapping);
    }

    final List<SchemaPath> paths = new ArrayList<>();
    final Mapping m = Mappings.create(MappingType.PARTIAL_FUNCTION, crel.getRowType().getFieldCount(), fieldsUsed.cardinality());
    int index = 0;
    for(int i : fieldsUsed) {
      paths.add(SchemaPath.getSimplePath(crel.getRowType().getFieldList().get(i).getName()));
      m.set(i, index);
      index++;
    }

    ScanCrel newCrel = crel.cloneWithProject(paths);
    return result(newCrel, m);
  }


  // Overridden until CALCITE-2260 is fixed.
  @Override
  public TrimResult trimFields(
      SetOp setOp,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    if(!setOp.all) {
      return result(setOp, Mappings.createIdentity(setOp.getRowType().getFieldCount()));
    }
    return super.trimFields(setOp, fieldsUsed, extraFields);
  }

  @Override
  public TrimResult trimFields(Project project, ImmutableBitSet fieldsUsed, Set<RelDataTypeField> extraFields) {
    int count = FlattenVisitors.count(project.getProjects());
    if(count == 0) {
      return super.trimFields(project, fieldsUsed, extraFields);
    }

    // start by trimming based on super.


    // if there are no flatten, trim is fine.
    TrimResult result = super.trimFields(project, fieldsUsed, extraFields);

    if(result.left.getRowType().getFieldCount() != fieldsUsed.cardinality()) {
      // we got a partial trim, which we don't handle. Skip the optimization.
      return result(project, Mappings.createIdentity(project.getRowType().getFieldCount()));

    }
    final RelNode resultRel = result.left;
    final Mapping finalMapping = result.right;

    if(resultRel instanceof Project && FlattenVisitors.count(((Project) resultRel).getProjects()) == count){
      // flatten count didn't change.
      return result;
    }

    /*
     * Flatten count changed. To solve, we'll actually increase the required fields to include the
     * flattens and then put another project on top that drops the extra fields, returning the
     * previously generated mapping.
     */
    ImmutableBitSet.Builder flattenColumnsBuilder = ImmutableBitSet.builder();
    {
      int i =0;
      for(RexNode n : project.getProjects()) {
        try {
          if(fieldsUsed.get(i)) {
            continue;
          }

          if(!FlattenVisitors.hasFlatten(n)) {
            continue;
          }

          // we have a flatten in an unused field.
          flattenColumnsBuilder.set(i);

        } finally {
          i++;
        }
      }
    }

    ImmutableBitSet unreferencedFlattenProjects = flattenColumnsBuilder.build();

    if(unreferencedFlattenProjects.isEmpty()) {
      // this should be impossible. fall back to using the base case (no column trim) as it means we just optimize less.
      logger.info("Failure while trying to trim flatten expression. Expressions {}, Columns to trim to: {}", project.getProjects(), fieldsUsed);
      return result(project, Mappings.createIdentity(project.getRowType().getFieldCount()));
    }

    final ImmutableBitSet fieldsIncludingFlattens = fieldsUsed.union(unreferencedFlattenProjects);
    final TrimResult result2 = super.trimFields(project, fieldsIncludingFlattens, extraFields);

    List<RexNode> finalProj = new ArrayList<>();
    builder.push(result2.left);

    int i = 0;
    for(int index : fieldsIncludingFlattens) {
      if(fieldsUsed.get(index)) {
        finalProj.add(builder.field(i));
      }
      i++;
    }


    // drop the flatten columns in a subsequent projection.
    return result(builder.project(finalProj).build(), finalMapping);

  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for {@link MultiJoin}.
   */
  @SuppressWarnings("unused")
  public TrimResult trimFields(
      MultiJoin multiJoin,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields
  ) {
    Util.discard(extraFields); // unlike #trimFields in RelFieldTrimmer

    final List<RelNode> originalInputs = multiJoin.getInputs();
    final RexNode originalJoinFilter = multiJoin.getJoinFilter();
    final List<RexNode> originalOuterJoinConditions = multiJoin.getOuterJoinConditions();
    final RexNode originalPostJoinFilter = multiJoin.getPostJoinFilter();

    int fieldCount = 0;
    for (RelNode input : originalInputs) {
      fieldCount += input.getRowType().getFieldCount();
    }

    // add in fields used in the all the conditions; including the ones requested in "fieldsUsed"
    final RelOptUtil.InputFinder inputFinder = new RelOptUtil.InputFinder();
    inputFinder.inputBitSet.addAll(fieldsUsed);
    originalJoinFilter.accept(inputFinder);
    originalOuterJoinConditions.forEach(
        rexNode -> {
          if (rexNode != null) {
            rexNode.accept(inputFinder);
          }
        });
    if (originalPostJoinFilter != null) {
      originalPostJoinFilter.accept(inputFinder);
    }
    final ImmutableBitSet fieldsUsedPlus = inputFinder.inputBitSet.build();

    int offset = 0;
    int changeCount = 0;
    int newFieldCount = 0;

    final List<RelNode> newInputs = Lists.newArrayListWithExpectedSize(originalInputs.size());
    final List<Mapping> inputMappings = Lists.newArrayList();

    for (RelNode input : originalInputs) {
      final RelDataType inputRowType = input.getRowType();
      final int inputFieldCount = inputRowType.getFieldCount();

      // compute required mapping
      final ImmutableBitSet.Builder inputFieldsUsed = ImmutableBitSet.builder();
      for (int bit : fieldsUsedPlus) {
        if (bit >= offset && bit < offset + inputFieldCount) {
          inputFieldsUsed.set(bit - offset);
        }
      }

      final TrimResult trimResult = trimChild(multiJoin, input, inputFieldsUsed.build(), Collections.emptySet());
      newInputs.add(trimResult.left);
      //noinspection ObjectEquality
      if (trimResult.left != input) {
        ++changeCount;
      }

      final Mapping inputMapping = trimResult.right;
      inputMappings.add(inputMapping);

      // move offset to point to start of next input
      offset += inputFieldCount;
      newFieldCount += inputMapping.getTargetCount();
    }

    final Mapping mapping = Mappings.create(MappingType.INVERSE_SURJECTION, fieldCount, newFieldCount);

    offset = 0;
    int newOffset = 0;
    for (final Mapping inputMapping : inputMappings) {
      ImmutableBitSet.Builder projBuilder = ImmutableBitSet.builder();
      for (final IntPair pair : inputMapping) {
        mapping.set(pair.source + offset, pair.target + newOffset);
      }

      offset += inputMapping.getSourceCount();
      newOffset += inputMapping.getTargetCount();
    }

    if (changeCount == 0 && mapping.isIdentity()) {
      result(multiJoin, Mappings.createIdentity(fieldCount));
    }

    // build new MultiJoin

    final RexVisitor<RexNode> inputFieldPermuter =
        new RexPermuteInputsShuttle(mapping, newInputs.toArray(new RelNode[0]));

    final RexNode newJoinFilter = originalJoinFilter.accept(inputFieldPermuter);

    // row type is simply re-mapped
    final List<RelDataTypeField> originalFieldList = multiJoin.getRowType().getFieldList();
    final RelDataType newRowType =
        new RelRecordType(StreamSupport.stream(mapping.spliterator(), false)
            .map(pair -> pair.source)
            .map(originalFieldList::get)
            .map(originalField ->
                new RelDataTypeFieldImpl(originalField.getName(),
                    mapping.getTarget(originalField.getIndex()),
                    originalField.getType()))
            .collect(Collectors.toList()));

    final List<RexNode> newOuterJoinConditions = originalOuterJoinConditions.stream()
        .map(expr -> expr == null ? null : expr.accept(inputFieldPermuter))
        .collect(Collectors.toList());

    // see MultiJoin#getProjFields; ideally all input fields must be used, and this is a list of "nulls"
    final List<ImmutableBitSet> newProjFields = Lists.newArrayList();

    for (final Ord<Mapping> inputMapping : Ord.zip(inputMappings)) {
      if (multiJoin.getProjFields().get(inputMapping.i) == null) {
        newProjFields.add(null);
        continue;
      }

      ImmutableBitSet.Builder projBuilder = ImmutableBitSet.builder();
      for (final IntPair pair : inputMapping.e) {
        if (multiJoin.getProjFields().get(inputMapping.i).get(pair.source)) {
          projBuilder.set(pair.target);
        }
      }
      newProjFields.add(projBuilder.build());
    }

    final ImmutableMap<Integer, ImmutableIntList> newJoinFieldRefCountsMap =
        computeJoinFieldRefCounts(newInputs, newFieldCount, newJoinFilter);

    final RexNode newPostJoinFilter =
        originalPostJoinFilter == null
            ? null
            : originalPostJoinFilter.accept(inputFieldPermuter);

    final MultiJoin newMultiJoin = new MultiJoin(
        multiJoin.getCluster(),
        newInputs,
        newJoinFilter,
        newRowType,
        multiJoin.isFullOuterJoin(),
        newOuterJoinConditions,
        multiJoin.getJoinTypes(),
        newProjFields,
        newJoinFieldRefCountsMap,
        newPostJoinFilter);

    return result(newMultiJoin, mapping);
  }

  public TrimResult trimFields(LogicalWindow window, ImmutableBitSet fieldsUsed, Set<RelDataTypeField> extraFields) {
    // Fields:
    //
    // Window rowtype
    // | input fields | agg functions |
    //
    // Rowtype used internally by the Window operator for groups:
    // | input fields | constants (hold by Window node) |
    //
    // Input rowtype:
    // | input fields |
    //
    // Trimming operations:
    // 1. If an agg is not used, don't include it in the group
    // 2. If a group is not used, remove the group
    // 3. If no group, skip the operator
    // 4. If a constant is not used, don't include it in the new window operator
    //
    // General description of the algorithm:
    // 1. Identify input fields and constants in use
    //    - input fields directly used by caller
    //    - input fields used by agg call, if agg call used by caller
    //    - input fields used by window group if at least one agg from the group is used by caller
    //    - constants used by call call, if agg call used by caller
    // 2. Trim input
    //    - only use list of used input fields (do not include constants) when calling for trimChild
    //      as it will confuse callee.
    // 3. Create new operator and final mapping
    //    - create a mapping combining input mapping and constants in use to rewrite expressions
    //    - if no agg is actually used by caller, return early with the new input and a copy of the
    //      input mapping matching the number of fields (skip the current operator)
    //    - Go over each group/agg call used by caller, and rewrite them by visiting them with the
    //      mapping combining input and constants
    //    - create a new window operator and return operator and mapping to caller


    final RelDataType rowType = window.getRowType();
    final int fieldCount = rowType.getFieldCount();

    final RelNode input = window.getInput();
    final int inputFieldCount = input.getRowType().getFieldCount();

    final Set<RelDataTypeField> inputExtraFields = new LinkedHashSet<>(extraFields);
    final RelOptUtil.InputFinder inputFinder = new RelOptUtil.InputFinder(inputExtraFields);

    //
    // 1. Identify input fields and constants in use
    //
    for(Integer bit: fieldsUsed) {
      if (bit >= inputFieldCount) {
        // exit if it goes over the input fields
        break;
      }
      inputFinder.inputBitSet.set(bit);
    }

    // number of agg calls and agg calls actually used
    int aggCalls = 0;
    int aggCallsUsed = 0;

    // Capture which input fields and constants are used by the agg calls
    // thanks to the visitor
    for (final Window.Group group: window.groups) {
      boolean groupUsed = false;
      for(final RexWinAggCall aggCall: group.aggCalls) {
        int offset = inputFieldCount + aggCalls;
        aggCalls++;

        if (!fieldsUsed.get(offset)) {
          continue;
        }

        aggCallsUsed++;
        groupUsed = true;
        aggCall.accept(inputFinder);
      }

      // If no agg from the group are being used, do not include group fields
      if (!groupUsed) {
        continue;
      }

      group.lowerBound.accept(inputFinder);
      group.upperBound.accept(inputFinder);

      // Add partition fields
      inputFinder.inputBitSet.addAll(group.keys);

      // Add collation fields
      for(RelFieldCollation fieldCollation: group.collation().getFieldCollations()) {
        inputFinder.inputBitSet.set(fieldCollation.getFieldIndex());
      }
    }
    // Create the final bitset containing both input and constants used
    final ImmutableBitSet inputAndConstantsFieldsUsed = inputFinder.inputBitSet.build();

    //
    // 2. Trim input
    //

    // Create input with trimmed columns. Need a bitset containing only input fields
    final ImmutableBitSet inputFieldsUsed = inputAndConstantsFieldsUsed.intersect(ImmutableBitSet.range(inputFieldCount));
    final int constantsUsed = inputAndConstantsFieldsUsed.cardinality() - inputFieldsUsed.cardinality();
    final TrimResult trimResult = trimChild(window, input, inputFieldsUsed, inputExtraFields);
    RelNode newInput = trimResult.left;
    Mapping inputMapping = trimResult.right;

    // If the input is unchanged, and we need to project all columns,
    // there's nothing we can do.
    if (newInput == input && fieldsUsed.cardinality() == fieldCount) {
      return result(window, Mappings.createIdentity(fieldCount));
    }

    //
    // 3. Create new operator and final mapping
    //
    // if new input cardinality is 0, create a dummy project
    // Note that the returned mapping is not a valid INVERSE_SURJECTION mapping
    // as it does not include a source for each target!
    if (inputFieldsUsed.cardinality() == 0) {
      final TrimResult dummyResult = dummyProject(inputFieldCount, newInput);
      newInput = dummyResult.left;
      inputMapping = dummyResult.right;
    }

    // Create a new input mapping which includes constants
    final int newInputFieldCount = newInput.getRowType().getFieldCount();
    final Mapping inputAndConstantsMapping = Mappings.create(MappingType.INVERSE_SURJECTION,
        inputFieldCount + window.constants.size(), newInputFieldCount + constantsUsed);
    // include input mappping
    inputMapping.forEach(pair -> inputAndConstantsMapping.set(pair.source, pair.target));

    // Add constant mapping (while trimming list of constants)
    final List<RexLiteral> newConstants = new ArrayList<>();
    for(int i = 0; i < window.constants.size(); i++) {
      int index = inputFieldCount + i;
      if (inputAndConstantsFieldsUsed.get(index)) {
        inputAndConstantsMapping.set(index, newInputFieldCount + newConstants.size());
        newConstants.add(window.constants.get(i));
      }
    }

    // Create a new mapping. Include all the input fields
    final Mapping mapping = Mappings.create(MappingType.INVERSE_SURJECTION, fieldCount, newInputFieldCount + aggCallsUsed);
    // Remap the field projects (1:1 remap)
    inputMapping.forEach(pair -> mapping.set(pair.source, pair.target));

    // Degenerated case: no agg calls being used, skip this operator!
    if (aggCallsUsed == 0) {
      return result(newInput, mapping);
    }

    // Create/Rewrite a new window operator by dropping unnecessary groups/agg calls
    // and permutting the inputs
    final RexPermuteInputsShuttle shuttle = new RexPermuteInputsShuttle(inputAndConstantsMapping, newInput);
    final List<Window.Group> newGroups = new ArrayList<>();

    int oldOffset = inputFieldCount;
    int newOffset = newInputFieldCount;
    for (final Window.Group group: window.groups) {
      final List<RexWinAggCall> newCalls = new ArrayList<>();
      for(final RexWinAggCall agg: group.aggCalls) {
        if (!fieldsUsed.get(oldOffset)) {
          // Skip unused aggs
          oldOffset++;
          continue;
        }

        RexWinAggCall newCall = permuteWinAggCall(agg, shuttle, newCalls.size());
        newCalls.add(newCall);
        mapping.set(oldOffset, newOffset);
        oldOffset++;
        newOffset++;
      }

      // If no agg from the group, let's skip the group
      if (newCalls.isEmpty()) {
        continue;
      }

      final RexWindowBound newLowerBound = group.lowerBound.accept(shuttle);
      final RexWindowBound newUpperBound = group.upperBound.accept(shuttle);

      // Remap partition fields
      final ImmutableBitSet newKeys = Mappings.apply(inputAndConstantsMapping, group.keys);

      // Remap collation fields
      final List<RelFieldCollation> newFieldCollations = new ArrayList<>();
      for(RelFieldCollation fieldCollation: group.collation().getFieldCollations()) {
        newFieldCollations.add(fieldCollation.copy(inputAndConstantsMapping.getTarget(fieldCollation.getFieldIndex())));
      }

      final Window.Group newGroup = new Window.Group(newKeys, group.isRows, newLowerBound, newUpperBound, RelCollations.of(newFieldCollations), newCalls);
      newGroups.add(newGroup);
    }
    final Mapping permutationMapping;

    // If no input column being used, still need to include the dummy column in the row type
    // by temporarily adding it to the mapping
    if (inputFieldsUsed.cardinality() != 0) {
      permutationMapping = mapping;
    } else {
      permutationMapping = Mappings.create(MappingType.INVERSE_SURJECTION, mapping.getSourceCount(), mapping.getTargetCount());
      mapping.forEach(pair -> permutationMapping.set(pair.source, pair.target));
      // set a fake mapping for the dummy project
      permutationMapping.set(0, 0);
    }
    final RelDataType newRowType = RelOptUtil.permute(window.getCluster().getTypeFactory(), rowType, permutationMapping);

    // TODO: should there be a relbuilder for window?
    final LogicalWindow newWindow = LogicalWindow.create(window.getTraitSet(), newInput, newConstants, newRowType, newGroups);
    return result(newWindow, mapping);
  }

  private static RexWinAggCall permuteWinAggCall(RexWinAggCall call, RexPermuteInputsShuttle shuttle, int newOrdinal) {
    // Cast should be safe as the shuttle creates new RexCall instance (but not RexWinAggCall)
    RexCall newCall = (RexCall) call.accept(shuttle);
    if (newCall == call && newOrdinal == call.ordinal) {
      return call;
    }

    return new RexWinAggCall(
        (SqlAggFunction) call.getOperator(),
        call.getType(),
        newCall.getOperands(),
        // remap partition ordinal
        newOrdinal,
        call.distinct);
  }
  /**
   * Compute the reference counts of fields in the inputs from the new join condition.
   *
   * @param inputs          inputs into the new MultiJoin
   * @param totalFieldCount total number of fields in the MultiJoin
   * @param joinCondition   the new join condition
   * @return Map containing the new join condition
   */
  private static ImmutableMap<Integer, ImmutableIntList> computeJoinFieldRefCounts(
      final List<RelNode> inputs,
      final int totalFieldCount,
      final RexNode joinCondition
  ) {
    // count the input references in the join condition
    final int[] joinCondRefCounts = new int[totalFieldCount];
    joinCondition.accept(new InputReferenceCounter(joinCondRefCounts));

    final Map<Integer, int[]> refCountsMap = Maps.newHashMap();
    final int numInputs = inputs.size();
    int currInput = 0;
    for (final RelNode input : inputs) {
      refCountsMap.put(currInput++, new int[input.getRowType().getFieldCount()]);
    }

    // add on to the counts for each input into the MultiJoin the
    // reference counts computed for the current join condition
    currInput = -1;
    int startField = 0;
    int inputFieldCount = 0;
    for (int i = 0; i < totalFieldCount; i++) {
      if (joinCondRefCounts[i] == 0) {
        continue;
      }
      while (i >= (startField + inputFieldCount)) {
        startField += inputFieldCount;
        currInput++;
        assert currInput < numInputs;
        inputFieldCount =
            inputs.get(currInput)
                .getRowType()
                .getFieldCount();
      }
      final int[] refCounts = refCountsMap.get(currInput);
      refCounts[i - startField] += joinCondRefCounts[i];
    }

    final ImmutableMap.Builder<Integer, ImmutableIntList> builder = ImmutableMap.builder();
    for (final Map.Entry<Integer, int[]> entry : refCountsMap.entrySet()) {
      builder.put(entry.getKey(), ImmutableIntList.of(entry.getValue()));
    }
    return builder.build();
  }

  /**
   * Visitor that keeps a reference count of the inputs used by an expression.
   * <p>
   * Duplicates {@link org.apache.calcite.rel.rules.JoinToMultiJoinRule.InputReferenceCounter}.
   */
  private static class InputReferenceCounter extends RexVisitorImpl<Void> {
    private final int[] refCounts;

    InputReferenceCounter(int[] refCounts) {
      super(true);
      this.refCounts = refCounts;
    }

    @Override
    public Void visitInputRef(RexInputRef inputRef) {
      refCounts[inputRef.getIndex()]++;
      return null;
    }
  }
}
