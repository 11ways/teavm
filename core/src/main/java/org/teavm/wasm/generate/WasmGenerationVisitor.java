/*
 *  Copyright 2016 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.wasm.generate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.teavm.ast.AssignmentStatement;
import org.teavm.ast.BinaryExpr;
import org.teavm.ast.BlockStatement;
import org.teavm.ast.BreakStatement;
import org.teavm.ast.CastExpr;
import org.teavm.ast.ConditionalExpr;
import org.teavm.ast.ConditionalStatement;
import org.teavm.ast.ConstantExpr;
import org.teavm.ast.ContinueStatement;
import org.teavm.ast.Expr;
import org.teavm.ast.ExprVisitor;
import org.teavm.ast.GotoPartStatement;
import org.teavm.ast.IdentifiedStatement;
import org.teavm.ast.InitClassStatement;
import org.teavm.ast.InstanceOfExpr;
import org.teavm.ast.InvocationExpr;
import org.teavm.ast.InvocationType;
import org.teavm.ast.MonitorEnterStatement;
import org.teavm.ast.MonitorExitStatement;
import org.teavm.ast.NewArrayExpr;
import org.teavm.ast.NewExpr;
import org.teavm.ast.NewMultiArrayExpr;
import org.teavm.ast.OperationType;
import org.teavm.ast.PrimitiveCastExpr;
import org.teavm.ast.QualificationExpr;
import org.teavm.ast.ReturnStatement;
import org.teavm.ast.SequentialStatement;
import org.teavm.ast.Statement;
import org.teavm.ast.StatementVisitor;
import org.teavm.ast.SubscriptExpr;
import org.teavm.ast.SwitchClause;
import org.teavm.ast.SwitchStatement;
import org.teavm.ast.ThrowStatement;
import org.teavm.ast.TryCatchStatement;
import org.teavm.ast.UnaryExpr;
import org.teavm.ast.UnwrapArrayExpr;
import org.teavm.ast.VariableExpr;
import org.teavm.ast.WhileStatement;
import org.teavm.interop.Address;
import org.teavm.model.ClassReader;
import org.teavm.model.FieldReference;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.runtime.Allocator;
import org.teavm.runtime.RuntimeClass;
import org.teavm.wasm.model.WasmFunction;
import org.teavm.wasm.model.WasmLocal;
import org.teavm.wasm.model.WasmType;
import org.teavm.wasm.model.expression.WasmBlock;
import org.teavm.wasm.model.expression.WasmBranch;
import org.teavm.wasm.model.expression.WasmBreak;
import org.teavm.wasm.model.expression.WasmCall;
import org.teavm.wasm.model.expression.WasmConditional;
import org.teavm.wasm.model.expression.WasmConversion;
import org.teavm.wasm.model.expression.WasmDrop;
import org.teavm.wasm.model.expression.WasmExpression;
import org.teavm.wasm.model.expression.WasmFloat32Constant;
import org.teavm.wasm.model.expression.WasmFloat64Constant;
import org.teavm.wasm.model.expression.WasmFloatBinary;
import org.teavm.wasm.model.expression.WasmFloatBinaryOperation;
import org.teavm.wasm.model.expression.WasmFloatType;
import org.teavm.wasm.model.expression.WasmGetLocal;
import org.teavm.wasm.model.expression.WasmInt32Constant;
import org.teavm.wasm.model.expression.WasmInt32Subtype;
import org.teavm.wasm.model.expression.WasmInt64Constant;
import org.teavm.wasm.model.expression.WasmInt64Subtype;
import org.teavm.wasm.model.expression.WasmIntBinary;
import org.teavm.wasm.model.expression.WasmIntBinaryOperation;
import org.teavm.wasm.model.expression.WasmIntType;
import org.teavm.wasm.model.expression.WasmLoadFloat32;
import org.teavm.wasm.model.expression.WasmLoadFloat64;
import org.teavm.wasm.model.expression.WasmLoadInt32;
import org.teavm.wasm.model.expression.WasmLoadInt64;
import org.teavm.wasm.model.expression.WasmReturn;
import org.teavm.wasm.model.expression.WasmSetLocal;
import org.teavm.wasm.model.expression.WasmStoreFloat32;
import org.teavm.wasm.model.expression.WasmStoreFloat64;
import org.teavm.wasm.model.expression.WasmStoreInt32;
import org.teavm.wasm.model.expression.WasmStoreInt64;
import org.teavm.wasm.model.expression.WasmSwitch;
import org.teavm.wasm.runtime.WasmRuntime;

class WasmGenerationVisitor implements StatementVisitor, ExprVisitor {
    private WasmGenerationContext context;
    private WasmClassGenerator classGenerator;
    private WasmFunction function;
    private int firstVariable;
    private IdentifiedStatement currentContinueTarget;
    private IdentifiedStatement currentBreakTarget;
    private Map<IdentifiedStatement, WasmBlock> breakTargets = new HashMap<>();
    private Map<IdentifiedStatement, WasmBlock> continueTargets = new HashMap<>();
    private Set<WasmBlock> usedBlocks = new HashSet<>();
    WasmExpression result;

    WasmGenerationVisitor(WasmGenerationContext context, WasmClassGenerator classGenerator,
            WasmFunction function, int firstVariable) {
        this.context = context;
        this.classGenerator = classGenerator;
        this.function = function;
        this.firstVariable = firstVariable;
    }

    @Override
    public void visit(BinaryExpr expr) {
        switch (expr.getOperation()) {
            case ADD:
                generateBinary(WasmIntBinaryOperation.ADD, WasmFloatBinaryOperation.ADD, expr);
                break;
            case SUBTRACT:
                generateBinary(WasmIntBinaryOperation.SUB, WasmFloatBinaryOperation.ADD, expr);
                break;
            case MULTIPLY:
                generateBinary(WasmIntBinaryOperation.MUL, WasmFloatBinaryOperation.ADD, expr);
                break;
            case DIVIDE:
                generateBinary(WasmIntBinaryOperation.DIV_SIGNED, WasmFloatBinaryOperation.DIV, expr);
                break;
            case MODULO: {
                switch (expr.getType()) {
                    case INT:
                    case LONG:
                        generateBinary(WasmIntBinaryOperation.REM_SIGNED, expr);
                        break;
                    default:
                        Class<?> type = convertType(expr.getType());
                        MethodReference method = new MethodReference(WasmRuntime.class, "remainder", type, type, type);
                        WasmCall call = new WasmCall(WasmMangling.mangleMethod(method), false);
                        expr.getFirstOperand().acceptVisitor(this);
                        call.getArguments().add(result);
                        expr.getSecondOperand().acceptVisitor(this);
                        call.getArguments().add(result);
                        result = call;
                        break;
                }

                break;
            }
            case BITWISE_AND:
                generateBinary(WasmIntBinaryOperation.AND, expr);
                break;
            case BITWISE_OR:
                generateBinary(WasmIntBinaryOperation.OR, expr);
                break;
            case BITWISE_XOR:
                generateBinary(WasmIntBinaryOperation.XOR, expr);
                break;
            case EQUALS:
                generateBinary(WasmIntBinaryOperation.EQ, WasmFloatBinaryOperation.EQ, expr);
                break;
            case NOT_EQUALS:
                generateBinary(WasmIntBinaryOperation.NE, WasmFloatBinaryOperation.NE, expr);
                break;
            case GREATER:
                generateBinary(WasmIntBinaryOperation.GT_SIGNED, WasmFloatBinaryOperation.GT, expr);
                break;
            case GREATER_OR_EQUALS:
                generateBinary(WasmIntBinaryOperation.GE_SIGNED, WasmFloatBinaryOperation.GE, expr);
                break;
            case LESS:
                generateBinary(WasmIntBinaryOperation.LT_SIGNED, WasmFloatBinaryOperation.LT, expr);
                break;
            case LESS_OR_EQUALS:
                generateBinary(WasmIntBinaryOperation.LE_SIGNED, WasmFloatBinaryOperation.LE, expr);
                break;
            case LEFT_SHIFT:
                generateBinary(WasmIntBinaryOperation.SHL, expr);
                break;
            case RIGHT_SHIFT:
                generateBinary(WasmIntBinaryOperation.SHR_SIGNED, expr);
                break;
            case UNSIGNED_RIGHT_SHIFT:
                generateBinary(WasmIntBinaryOperation.SHR_UNSIGNED, expr);
                break;
            case COMPARE: {
                Class<?> type = convertType(expr.getType());
                MethodReference method = new MethodReference(WasmRuntime.class, "compare", type, type, int.class);
                WasmCall call = new WasmCall(WasmMangling.mangleMethod(method), false);
                expr.getFirstOperand().acceptVisitor(this);
                call.getArguments().add(result);
                expr.getSecondOperand().acceptVisitor(this);
                call.getArguments().add(result);
                result = call;
                break;
            }
            case AND:
                generateAnd(expr);
                break;
            case OR:
                generateOr(expr);
                break;
        }
    }

    private void generateBinary(WasmIntBinaryOperation intOp, WasmFloatBinaryOperation floatOp, BinaryExpr expr) {
        expr.getFirstOperand().acceptVisitor(this);
        WasmExpression first = result;
        expr.getSecondOperand().acceptVisitor(this);
        WasmExpression second = result;

        if (expr.getType() == null) {
            result = new WasmIntBinary(WasmIntType.INT32, intOp, first, second);
        } else {
            switch (expr.getType()) {
                case INT:
                    result = new WasmIntBinary(WasmIntType.INT32, intOp, first, second);
                    break;
                case LONG:
                    result = new WasmIntBinary(WasmIntType.INT64, intOp, first, second);
                    break;
                case FLOAT:
                    result = new WasmFloatBinary(WasmFloatType.FLOAT32, floatOp, first, second);
                    break;
                case DOUBLE:
                    result = new WasmFloatBinary(WasmFloatType.FLOAT64, floatOp, first, second);
                    break;
            }
        }
    }

    private void generateBinary(WasmIntBinaryOperation intOp, BinaryExpr expr) {
        expr.getFirstOperand().acceptVisitor(this);
        WasmExpression first = result;
        expr.getSecondOperand().acceptVisitor(this);
        WasmExpression second = result;

        switch (expr.getType()) {
            case INT:
                result = new WasmIntBinary(WasmIntType.INT32, intOp, first, second);
                break;
            case LONG:
                result = new WasmIntBinary(WasmIntType.INT64, intOp, first, second);
                break;
            case FLOAT:
            case DOUBLE:
                throw new AssertionError("Can't translate operation " + intOp + " for type " + expr.getType());
        }
    }

    private Class<?> convertType(OperationType type) {
        switch (type) {
            case INT:
                return int.class;
            case LONG:
                return long.class;
            case FLOAT:
                return float.class;
            case DOUBLE:
                return double.class;
        }
        throw new AssertionError(type.toString());
    }

    private void generateAnd(BinaryExpr expr) {
        WasmBlock block = new WasmBlock(false);

        expr.getFirstOperand().acceptVisitor(this);
        WasmBranch branch = new WasmBranch(negate(result), block);
        branch.setResult(new WasmInt32Constant(0));
        block.getBody().add(branch);

        expr.getSecondOperand().acceptVisitor(this);
        block.getBody().add(result);

        result = block;
    }

    private void generateOr(BinaryExpr expr) {
        WasmBlock block = new WasmBlock(false);

        expr.getFirstOperand().acceptVisitor(this);
        WasmBranch branch = new WasmBranch(result, block);
        branch.setResult(new WasmInt32Constant(1));
        block.getBody().add(branch);

        expr.getSecondOperand().acceptVisitor(this);
        block.getBody().add(result);

        result = block;
    }

    @Override
    public void visit(UnaryExpr expr) {
        switch (expr.getOperation()) {
            case INT_TO_BYTE:
                expr.getOperand().acceptVisitor(this);
                result = new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.SHL,
                        result, new WasmInt32Constant(24));
                result = new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.SHR_SIGNED,
                        result, new WasmInt32Constant(24));
                break;
            case INT_TO_SHORT:
                expr.getOperand().acceptVisitor(this);
                result = new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.SHL,
                        result, new WasmInt32Constant(16));
                result = new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.SHR_SIGNED,
                        result, new WasmInt32Constant(16));
                break;
            case INT_TO_CHAR:
                expr.getOperand().acceptVisitor(this);
                result = new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.SHL,
                        result, new WasmInt32Constant(16));
                result = new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.SHR_UNSIGNED,
                        result, new WasmInt32Constant(16));
                break;
            case LENGTH:
                result = new WasmInt32Constant(0);
                break;
            case NOT:
                expr.getOperand().acceptVisitor(this);
                result = negate(result);
                break;
            case NEGATE:
                expr.getOperand().acceptVisitor(this);
                switch (expr.getType()) {
                    case INT:
                        result = new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.SUB,
                                new WasmInt32Constant(0), result);
                        break;
                    case LONG:
                        result = new WasmIntBinary(WasmIntType.INT64, WasmIntBinaryOperation.SUB,
                                new WasmInt64Constant(0), result);
                        break;
                    case FLOAT:
                        result = new WasmFloatBinary(WasmFloatType.FLOAT32, WasmFloatBinaryOperation.SUB,
                                new WasmFloat32Constant(0), result);
                        break;
                    case DOUBLE:
                        result = new WasmFloatBinary(WasmFloatType.FLOAT64, WasmFloatBinaryOperation.SUB,
                                new WasmFloat64Constant(0), result);
                        break;
                }
                break;
            case NULL_CHECK:
                expr.getOperand().acceptVisitor(this);
                break;
        }
    }

    @Override
    public void visit(AssignmentStatement statement) {
        Expr left = statement.getLeftValue();
        if (left == null) {
            statement.getRightValue().acceptVisitor(this);
            result = new WasmDrop(result);
        } else if (left instanceof VariableExpr) {
            VariableExpr varExpr = (VariableExpr) left;
            WasmLocal local = function.getLocalVariables().get(varExpr.getIndex() - firstVariable);
            statement.getRightValue().acceptVisitor(this);
            result = new WasmSetLocal(local, result);
        } else if (left instanceof QualificationExpr) {
            QualificationExpr lhs = (QualificationExpr) left;
            storeField(lhs.getQualified(), lhs.getField(), statement.getRightValue());
        } else {
            throw new UnsupportedOperationException("This expression is not supported yet");
        }
    }

    private void storeField(Expr qualified, FieldReference field, Expr value) {
        WasmExpression address = getAddress(qualified, field);
        ValueType type = context.getFieldType(field);
        value.acceptVisitor(this);
        if (type instanceof ValueType.Primitive) {
            switch (((ValueType.Primitive) type).getKind()) {
                case BOOLEAN:
                case BYTE:
                    result = new WasmStoreInt32(1, address, result, WasmInt32Subtype.INT8);
                    break;
                case SHORT:
                    result = new WasmStoreInt32(2, address, result, WasmInt32Subtype.INT16);
                    break;
                case CHARACTER:
                    result = new WasmStoreInt32(2, address, result, WasmInt32Subtype.UINT16);
                    break;
                case INTEGER:
                    result = new WasmStoreInt32(4, address, result, WasmInt32Subtype.INT32);
                    break;
                case LONG:
                    result = new WasmStoreInt64(8, address, result, WasmInt64Subtype.INT64);
                    break;
                case FLOAT:
                    result = new WasmStoreFloat32(4, address, result);
                    break;
                case DOUBLE:
                    result = new WasmStoreFloat64(8, address, result);
                    break;
            }
        } else {
            result = new WasmStoreInt32(4, address, result, WasmInt32Subtype.INT32);
        }
    }

    @Override
    public void visit(ConditionalExpr expr) {
        expr.getCondition().acceptVisitor(this);
        WasmConditional conditional = new WasmConditional(result);
        expr.getConsequent().acceptVisitor(this);
        conditional.getThenBlock().getBody().add(result);
        expr.getAlternative().acceptVisitor(this);
        conditional.getThenBlock().getBody().add(result);
        result = conditional;
    }

    @Override
    public void visit(SequentialStatement statement) {
        WasmBlock block = new WasmBlock(false);
        for (Statement part : statement.getSequence()) {
            part.acceptVisitor(this);
            if (result != null) {
                block.getBody().add(result);
            }
        }
        result = block;
    }

    @Override
    public void visit(ConstantExpr expr) {
        if (expr.getValue() == null) {
            result = new WasmInt32Constant(0);
        } else if (expr.getValue() instanceof Integer) {
            result = new WasmInt32Constant((Integer) expr.getValue());
        } else if (expr.getValue() instanceof Long) {
            result = new WasmInt64Constant((Long) expr.getValue());
        } else if (expr.getValue() instanceof Float) {
            result = new WasmFloat32Constant((Float) expr.getValue());
        } else if (expr.getValue() instanceof Double) {
            result = new WasmFloat64Constant((Double) expr.getValue());
        } else {
            throw new IllegalArgumentException("Constant unsupported: " + expr.getValue());
        }
    }

    @Override
    public void visit(ConditionalStatement statement) {
        statement.getCondition().acceptVisitor(this);
        WasmConditional conditional = new WasmConditional(result);
        for (Statement part : statement.getConsequent()) {
            part.acceptVisitor(this);
            if (result != null) {
                conditional.getThenBlock().getBody().add(result);
            }
        }
        for (Statement part : statement.getAlternative()) {
            part.acceptVisitor(this);
            if (result != null) {
                conditional.getElseBlock().getBody().add(result);
            }
        }
        result = conditional;
    }

    @Override
    public void visit(VariableExpr expr) {
        result = new WasmGetLocal(function.getLocalVariables().get(expr.getIndex() - firstVariable));
    }

    @Override
    public void visit(SubscriptExpr expr) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public void visit(SwitchStatement statement) {
        List<WasmBlock> wrappers = new ArrayList<>();

        WasmBlock wrapper = new WasmBlock(false);
        statement.getValue().acceptVisitor(this);
        WasmSwitch wasmSwitch = new WasmSwitch(result, wrapper);
        wrapper.getBody().add(wasmSwitch);

        WasmBlock defaultBlock = new WasmBlock(false);
        defaultBlock.getBody().add(wrapper);
        for (Statement part : statement.getDefaultClause()) {
            part.acceptVisitor(this);
            defaultBlock.getBody().add(result);
        }
        wrapper = defaultBlock;

        for (SwitchClause clause : statement.getClauses()) {
            WasmBlock caseBlock = new WasmBlock(false);
            caseBlock.getBody().add(wrapper);
            wasmSwitch.getTargets().add(wrapper);
            for (Statement part : clause.getBody()) {
                part.acceptVisitor(this);
                caseBlock.getBody().add(result);
            }
            wrappers.add(caseBlock);
            wrapper = caseBlock;
        }

        for (WasmBlock nestedWrapper : wrappers) {
            nestedWrapper.getBody().add(new WasmBreak(wrapper));
        }

        result = wrapper;
    }

    @Override
    public void visit(UnwrapArrayExpr expr) {
        expr.getArray().acceptVisitor(this);
    }

    @Override
    public void visit(WhileStatement statement) {
        WasmBlock wrapper = new WasmBlock(false);
        WasmBlock loop = new WasmBlock(true);

        continueTargets.put(statement, loop);
        breakTargets.put(statement, wrapper);
        IdentifiedStatement oldBreakTarget = currentBreakTarget;
        IdentifiedStatement oldContinueTarget = currentContinueTarget;
        currentBreakTarget = statement;
        currentContinueTarget = statement;

        if (statement.getCondition() != null) {
            statement.getCondition().acceptVisitor(this);
            loop.getBody().add(new WasmBranch(negate(result), wrapper));
            usedBlocks.add(wrapper);
        }

        for (Statement part : statement.getBody()) {
            part.acceptVisitor(this);
            if (result != null) {
                loop.getBody().add(result);
            }
        }
        loop.getBody().add(new WasmBreak(loop));

        currentBreakTarget = oldBreakTarget;
        currentContinueTarget = oldContinueTarget;
        continueTargets.remove(statement);
        breakTargets.remove(statement);

        if (usedBlocks.contains(wrapper)) {
            wrapper.getBody().add(loop);
            result = wrapper;
        } else {
            result = loop;
        }
    }

    @Override
    public void visit(InvocationExpr expr) {
        if (expr.getMethod().getClassName().equals(Address.class.getName())) {
            generateAddressInvocation(expr);
            return;
        }

        if (expr.getType() == InvocationType.STATIC || expr.getType() == InvocationType.SPECIAL) {
            String methodName = WasmMangling.mangleMethod(expr.getMethod());

            WasmCall call = new WasmCall(methodName);
            if (context.getImportedMethod(expr.getMethod()) != null) {
                call.setImported(true);
            }
            for (Expr argument : expr.getArguments()) {
                argument.acceptVisitor(this);
                call.getArguments().add(result);
            }
            result = call;
        }
    }

    private void generateAddressInvocation(InvocationExpr expr) {
        switch (expr.getMethod().getName()) {
            case "toInt":
            case "toStructure":
                expr.getArguments().get(0).acceptVisitor(this);
                break;
            case "toLong":
                expr.getArguments().get(0).acceptVisitor(this);
                result = new WasmConversion(WasmType.INT32, WasmType.INT64, false, result);
                break;
            case "fromInt":
                expr.getArguments().get(0).acceptVisitor(this);
                break;
            case "fromLong":
                expr.getArguments().get(0).acceptVisitor(this);
                result = new WasmConversion(WasmType.INT64, WasmType.INT32, false, result);
                break;
            case "add": {
                expr.getArguments().get(0).acceptVisitor(this);
                WasmExpression base = result;
                expr.getArguments().get(1).acceptVisitor(this);
                WasmExpression offset = result;
                if (expr.getMethod().parameterType(0) == ValueType.LONG) {
                    offset = new WasmConversion(WasmType.INT64, WasmType.INT32, false, offset);
                }
                result = new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.ADD, base, offset);
                break;
            }
            case "getByte":
                expr.getArguments().get(0).acceptVisitor(this);
                result = new WasmLoadInt32(1, result, WasmInt32Subtype.INT8);
                break;
            case "getShort":
                expr.getArguments().get(0).acceptVisitor(this);
                result = new WasmLoadInt32(2, result, WasmInt32Subtype.INT16);
                break;
            case "getChar":
                expr.getArguments().get(0).acceptVisitor(this);
                result = new WasmLoadInt32(2, result, WasmInt32Subtype.UINT16);
                break;
            case "getInt":
                expr.getArguments().get(0).acceptVisitor(this);
                result = new WasmLoadInt32(4, result, WasmInt32Subtype.INT32);
                break;
            case "getLong":
                expr.getArguments().get(0).acceptVisitor(this);
                result = new WasmLoadInt64(8, result, WasmInt64Subtype.INT64);
                break;
            case "getFloat":
                expr.getArguments().get(0).acceptVisitor(this);
                result = new WasmLoadFloat32(4, result);
                break;
            case "getDouble":
                expr.getArguments().get(0).acceptVisitor(this);
                result = new WasmLoadFloat64(8, result);
                break;
            case "putByte": {
                expr.getArguments().get(0).acceptVisitor(this);
                WasmExpression address = result;
                expr.getArguments().get(1).acceptVisitor(this);
                result = new WasmStoreInt32(1, address, result, WasmInt32Subtype.INT8);
                break;
            }
            case "putShort": {
                expr.getArguments().get(0).acceptVisitor(this);
                WasmExpression address = result;
                expr.getArguments().get(1).acceptVisitor(this);
                result = new WasmStoreInt32(2, address, result, WasmInt32Subtype.INT16);
                break;
            }
            case "putChar": {
                expr.getArguments().get(0).acceptVisitor(this);
                WasmExpression address = result;
                expr.getArguments().get(1).acceptVisitor(this);
                result = new WasmStoreInt32(2, address, result, WasmInt32Subtype.UINT16);
                break;
            }
            case "putInt": {
                expr.getArguments().get(0).acceptVisitor(this);
                WasmExpression address = result;
                expr.getArguments().get(1).acceptVisitor(this);
                result = new WasmStoreInt32(4, address, result, WasmInt32Subtype.INT32);
                break;
            }
            case "putLong": {
                expr.getArguments().get(0).acceptVisitor(this);
                WasmExpression address = result;
                expr.getArguments().get(1).acceptVisitor(this);
                result = new WasmStoreInt64(8, address, result, WasmInt64Subtype.INT64);
                break;
            }
            case "putFloat": {
                expr.getArguments().get(0).acceptVisitor(this);
                WasmExpression address = result;
                expr.getArguments().get(1).acceptVisitor(this);
                result = new WasmStoreFloat32(4, address, result);
                break;
            }
            case "putDouble": {
                expr.getArguments().get(0).acceptVisitor(this);
                WasmExpression address = result;
                expr.getArguments().get(1).acceptVisitor(this);
                result = new WasmStoreFloat64(8, address, result);
                break;
            }
        }
    }

    @Override
    public void visit(BlockStatement statement) {
        WasmBlock block = new WasmBlock(false);

        if (statement.getId() != null) {
            breakTargets.put(statement, block);
        }

        for (Statement part : statement.getBody()) {
            part.acceptVisitor(this);
            if (result != null) {
                block.getBody().add(result);
            }
        }

        if (statement.getId() != null) {
            breakTargets.remove(statement);
        }

        result = block;
    }

    @Override
    public void visit(QualificationExpr expr) {
        WasmExpression address = getAddress(expr.getQualified(), expr.getField());

        ValueType type = context.getFieldType(expr.getField());
        if (type instanceof ValueType.Primitive) {
            switch (((ValueType.Primitive) type).getKind()) {
                case BOOLEAN:
                case BYTE:
                    result = new WasmLoadInt32(1, address, WasmInt32Subtype.INT8);
                    break;
                case SHORT:
                    result = new WasmLoadInt32(2, address, WasmInt32Subtype.INT16);
                    break;
                case CHARACTER:
                    result = new WasmLoadInt32(2, address, WasmInt32Subtype.UINT16);
                    break;
                case INTEGER:
                    result = new WasmLoadInt32(4, address, WasmInt32Subtype.INT32);
                    break;
                case LONG:
                    result = new WasmLoadInt64(8, address, WasmInt64Subtype.INT64);
                    break;
                case FLOAT:
                    result = new WasmLoadFloat32(4, address);
                    break;
                case DOUBLE:
                    result = new WasmLoadFloat64(8, address);
                    break;
            }
        } else {
            result = new WasmLoadInt32(4, address, WasmInt32Subtype.INT32);
        }
    }

    private WasmExpression getAddress(Expr qualified, FieldReference field) {
        int offset = classGenerator.getFieldOffset(field);
        if (qualified == null) {
            return new WasmInt32Constant(offset);
        } else {
            qualified.acceptVisitor(this);
            return new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.ADD, result,
                    new WasmInt32Constant(offset));
        }
    }

    @Override
    public void visit(BreakStatement statement) {
        IdentifiedStatement target = statement.getTarget();
        if (target == null) {
            target = currentBreakTarget;
        }
        WasmBlock wasmTarget = breakTargets.get(target);
        usedBlocks.add(wasmTarget);
        result = new WasmBreak(wasmTarget);
    }

    @Override
    public void visit(NewExpr expr) {
        int tag = classGenerator.getClassPointer(expr.getConstructedClass());
        String allocName = WasmMangling.mangleMethod(new MethodReference(Allocator.class, "allocate",
                RuntimeClass.class, Address.class));
        WasmCall call = new WasmCall(allocName);
        call.getArguments().add(new WasmInt32Constant(tag));
        result = call;
    }

    @Override
    public void visit(ContinueStatement statement) {
        IdentifiedStatement target = statement.getTarget();
        if (target == null) {
            target = currentContinueTarget;
        }
        WasmBlock wasmTarget = continueTargets.get(target);
        usedBlocks.add(wasmTarget);
        result = new WasmBreak(wasmTarget);
    }

    @Override
    public void visit(NewArrayExpr expr) {
    }

    @Override
    public void visit(NewMultiArrayExpr expr) {
    }

    @Override
    public void visit(ReturnStatement statement) {
        if (statement.getResult() != null) {
            statement.getResult().acceptVisitor(this);
        } else {
            result = null;
        }
        result = new WasmReturn(result);
    }

    @Override
    public void visit(InstanceOfExpr expr) {

    }

    @Override
    public void visit(ThrowStatement statement) {

    }

    @Override
    public void visit(CastExpr expr) {
        expr.getValue().acceptVisitor(this);
    }

    @Override
    public void visit(InitClassStatement statement) {
        if (hasClinit(statement.getClassName())) {
            result = new WasmCall(WasmMangling.mangleInitializer(statement.getClassName()));
        } else {
            result = null;
        }
    }

    private boolean hasClinit(String className) {
        if (classGenerator.isStructure(className)) {
            return false;
        }
        ClassReader cls = context.getClassSource().get(className);
        if (cls == null) {
            return false;
        }
        return cls.getMethod(new MethodDescriptor("<clinit>", ValueType.VOID)) != null;
    }

    @Override
    public void visit(PrimitiveCastExpr expr) {
        expr.getValue().acceptVisitor(this);
        result = new WasmConversion(WasmGeneratorUtil.mapType(expr.getSource()),
                WasmGeneratorUtil.mapType(expr.getTarget()), true, result);

    }

    @Override
    public void visit(TryCatchStatement statement) {
    }

    @Override
    public void visit(GotoPartStatement statement) {
    }

    @Override
    public void visit(MonitorEnterStatement statement) {
    }

    @Override
    public void visit(MonitorExitStatement statement) {
    }

    private WasmExpression negate(WasmExpression expr) {
        if (expr instanceof WasmIntBinary) {
            WasmIntBinary binary = (WasmIntBinary) expr;
            if (binary.getType() == WasmIntType.INT32 && binary.getOperation() == WasmIntBinaryOperation.XOR) {
                if (isOne(binary.getFirst())) {
                    return binary.getSecond();
                }
                if (isOne(binary.getSecond())) {
                    return binary.getFirst();
                }
            }

            WasmIntBinaryOperation negatedOp = negate(binary.getOperation());
            if (negatedOp != null) {
                return new WasmIntBinary(binary.getType(), negatedOp, binary.getFirst(), binary.getSecond());
            }
        } else if (expr instanceof WasmFloatBinary) {
            WasmFloatBinary binary = (WasmFloatBinary) expr;
            WasmFloatBinaryOperation negatedOp = negate(binary.getOperation());
            if (negatedOp != null) {
                return new WasmFloatBinary(binary.getType(), negatedOp, binary.getFirst(), binary.getSecond());
            }
        }

        return new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.XOR, expr, new WasmInt32Constant(1));
    }

    private boolean isOne(WasmExpression expression) {
        return expression instanceof WasmInt32Constant && ((WasmInt32Constant) expression).getValue() == 1;
    }

    private WasmIntBinaryOperation negate(WasmIntBinaryOperation op) {
        switch (op) {
            case EQ:
                return WasmIntBinaryOperation.NE;
            case NE:
                return WasmIntBinaryOperation.EQ;
            case LT_SIGNED:
                return WasmIntBinaryOperation.GE_SIGNED;
            case LT_UNSIGNED:
                return WasmIntBinaryOperation.GE_UNSIGNED;
            case LE_SIGNED:
                return WasmIntBinaryOperation.GT_SIGNED;
            case LE_UNSIGNED:
                return WasmIntBinaryOperation.GT_UNSIGNED;
            case GT_SIGNED:
                return WasmIntBinaryOperation.LE_SIGNED;
            case GT_UNSIGNED:
                return WasmIntBinaryOperation.LE_UNSIGNED;
            case GE_SIGNED:
                return WasmIntBinaryOperation.LT_SIGNED;
            case GE_UNSIGNED:
                return WasmIntBinaryOperation.LT_UNSIGNED;
            default:
                return null;
        }
    }

    private WasmFloatBinaryOperation negate(WasmFloatBinaryOperation op) {
        switch (op) {
            case EQ:
                return WasmFloatBinaryOperation.NE;
            case NE:
                return WasmFloatBinaryOperation.EQ;
            case LT:
                return WasmFloatBinaryOperation.GE;
            case LE:
                return WasmFloatBinaryOperation.GT;
            case GT:
                return WasmFloatBinaryOperation.LE;
            case GE:
                return WasmFloatBinaryOperation.LT;
            default:
                return null;
        }
    }
}
