/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
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
 * </p>
 */

package io.shardingsphere.core.parsing.antlr.extractor.segment.impl;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import io.shardingsphere.core.constant.ShardingOperator;
import io.shardingsphere.core.parsing.antlr.extractor.segment.OptionalSQLSegmentExtractor;
import io.shardingsphere.core.parsing.antlr.extractor.segment.constant.LogicalOperator;
import io.shardingsphere.core.parsing.antlr.extractor.segment.constant.Paren;
import io.shardingsphere.core.parsing.antlr.extractor.segment.constant.RuleName;
import io.shardingsphere.core.parsing.antlr.extractor.util.ExtractorUtils;
import io.shardingsphere.core.parsing.antlr.sql.segment.column.ColumnSegment;
import io.shardingsphere.core.parsing.antlr.sql.segment.condition.AndConditionSegment;
import io.shardingsphere.core.parsing.antlr.sql.segment.condition.ConditionSegment;
import io.shardingsphere.core.parsing.antlr.sql.segment.condition.OrConditionSegment;
import io.shardingsphere.core.parsing.antlr.sql.segment.condition.PredicateSegment;
import io.shardingsphere.core.parsing.antlr.sql.segment.expr.BetweenValueExpressionSegment;
import io.shardingsphere.core.parsing.antlr.sql.segment.expr.CommonExpressionSegment;
import io.shardingsphere.core.parsing.antlr.sql.segment.expr.EqualsValueExpressionSegment;
import io.shardingsphere.core.parsing.antlr.sql.segment.expr.ExpressionSegment;
import io.shardingsphere.core.parsing.antlr.sql.segment.expr.InValueExpressionSegment;
import io.shardingsphere.core.parsing.lexer.token.DefaultKeyword;
import io.shardingsphere.core.parsing.lexer.token.Symbol;
import io.shardingsphere.core.util.NumberUtil;
import lombok.RequiredArgsConstructor;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Predicate extractor.
 *
 * @author duhongjun
 */
@RequiredArgsConstructor
public final class PredicateExtractor implements OptionalSQLSegmentExtractor {
    
    private final Map<String, String> tableAlias;
    
    @Override
    public Optional<PredicateSegment> extract(final ParserRuleContext ancestorNode) {
        throw new RuntimeException();
    }
    
    /**
     * Extract SQL segment from SQL AST.
     *
     * @param questionNodeIndexMap question node map
     * @param exprNode             expression node of AST
     * @return or condition
     */
    public Optional<OrConditionSegment> extractCondition(final Map<ParserRuleContext, Integer> questionNodeIndexMap, final ParserRuleContext exprNode) {
        int index = -1;
        for (int i = 0; i < exprNode.getChildCount(); i++) {
            if (LogicalOperator.isLogicalOperator(exprNode.getChild(i).getText())) {
                index = i;
                break;
            }
        }
        if (index > 0) {
            Optional<OrConditionSegment> leftOrCondition = extractCondition(questionNodeIndexMap, (ParserRuleContext) exprNode.getChild(index - 1));
            Optional<OrConditionSegment> rightOrCondition = extractCondition(questionNodeIndexMap, (ParserRuleContext) exprNode.getChild(index + 1));
            return mergeCondition(leftOrCondition, rightOrCondition, exprNode.getChild(index).getText());
        } else {
            return extractConditionForParen(questionNodeIndexMap, exprNode);
        }
    }
    
    private Optional<OrConditionSegment> mergeCondition(final Optional<OrConditionSegment> leftOrCondition, final Optional<OrConditionSegment> rightOrCondition, final String operator) {
        if (!leftOrCondition.isPresent() && !rightOrCondition.isPresent()) {
            return Optional.absent();
        }
        if (leftOrCondition.isPresent() && !rightOrCondition.isPresent()) {
            return leftOrCondition;
        }
        if (rightOrCondition.isPresent() && !leftOrCondition.isPresent()) {
            return rightOrCondition;
        }
        if (LogicalOperator.isOrOperator(operator)) {
            leftOrCondition.get().getAndConditions().addAll(rightOrCondition.get().getAndConditions());
            return leftOrCondition;
        }
        if (LogicalOperator.isAndOperator(operator)) {
            OrConditionSegment result = new OrConditionSegment();
            for (AndConditionSegment each : leftOrCondition.get().getAndConditions()) {
                for (AndConditionSegment eachRightOr : rightOrCondition.get().getAndConditions()) {
                    AndConditionSegment tempList = new AndConditionSegment();
                    tempList.getConditions().addAll(each.getConditions());
                    tempList.getConditions().addAll(eachRightOr.getConditions());
                    result.getAndConditions().add(tempList);
                }
            }
            return Optional.of(result);
        }
        return Optional.absent();
    }
    
    private Optional<OrConditionSegment> extractConditionForParen(final Map<ParserRuleContext, Integer> questionNodeIndexMap, final ParserRuleContext exprNode) {
        int index = -1;
        for (int i = 0; i < exprNode.getChildCount(); i++) {
            if (Paren.isLeftParen(exprNode.getChild(i).getText())) {
                index = i;
                break;
            }
        }
        if (0 <= index) {
            Preconditions.checkState(exprNode.getChildCount() == index + 3, "Invalid expression.");
            Preconditions.checkState(Paren.match(exprNode.getChild(index).getText(), exprNode.getChild(index + 2).getText()), "Missing right paren.");
            if (index >= 0 && RuleName.EXPR.getName().equals(exprNode.getChild(index + 1).getClass().getSimpleName())) {
                return extractCondition(questionNodeIndexMap, (ParserRuleContext) exprNode.getChild(index + 1));
            }
            return Optional.absent();
        }
        Optional<ConditionSegment> condition = buildCondition(questionNodeIndexMap, exprNode);
        if (condition.isPresent()) {
            OrConditionSegment result = new OrConditionSegment();
            AndConditionSegment newAndCondition = new AndConditionSegment();
            newAndCondition.getConditions().add(condition.get());
            result.getAndConditions().add(newAndCondition);
            return Optional.of(result);
        }
        return Optional.absent();
    }
    
    private Optional<ConditionSegment> buildCondition(final Map<ParserRuleContext, Integer> questionNodeIndexMap, final ParserRuleContext exprNode) {
        Optional<ConditionSegment> result = buildEqualCondition(questionNodeIndexMap, exprNode);
        if (result.isPresent()) {
            return result;
        }
        return buildPredicateCondition(questionNodeIndexMap, exprNode);
    }
    
    private Optional<ConditionSegment> buildEqualCondition(final Map<ParserRuleContext, Integer> questionNodeIndexMap, final ParserRuleContext exprNode) {
        Optional<ParserRuleContext> comparisionNode = ExtractorUtils.findFirstChildNode(exprNode, RuleName.COMPARISON_OPERATOR);
        if (!comparisionNode.isPresent()) {
            return Optional.absent();
        }
        if (3 != comparisionNode.get().getParent().getChildCount()) {
            return Optional.absent();
        }
        if (!Symbol.EQ.getLiterals().equalsIgnoreCase(comparisionNode.get().getText())) {
            return Optional.absent();
        }
        Optional<ParserRuleContext> leftNode = ExtractorUtils.findFirstChildNode((ParserRuleContext) comparisionNode.get().parent.getChild(0), RuleName.COLUMN_NAME);
        Optional<ParserRuleContext> rightNode = ExtractorUtils.findFirstChildNode((ParserRuleContext) comparisionNode.get().parent.getChild(2), RuleName.COLUMN_NAME);
        if (!leftNode.isPresent() && !rightNode.isPresent()) {
            return Optional.absent();
        }
        if (leftNode.isPresent() && rightNode.isPresent()) {
            Optional<ColumnSegment> column = buildColumn(leftNode.get());
            Optional<ColumnSegment> rightColumn = buildColumn(rightNode.get());
            return Optional.of(new ConditionSegment(column.get(), ShardingOperator.EQUAL, rightColumn.get()));
        }
        Optional<ColumnSegment> column = buildColumn(exprNode);
        ParserRuleContext valueNode = null;
        if (leftNode.isPresent()) {
            valueNode = (ParserRuleContext) comparisionNode.get().parent.getChild(2);
        } else if (rightNode.isPresent()) {
            valueNode = (ParserRuleContext) comparisionNode.get().parent.getChild(0);
        }
        Optional<ExpressionSegment> sqlExpression = buildExpression(questionNodeIndexMap, valueNode);
        if (!sqlExpression.isPresent()) {
            return Optional.absent();
        }
        return Optional.of(new ConditionSegment(column.get(), ShardingOperator.EQUAL, new EqualsValueExpressionSegment(sqlExpression.get())));
    }
    
    private Optional<ExpressionSegment> buildExpression(final Map<ParserRuleContext, Integer> questionNodeIndexMap, final ParserRuleContext valueNode) {
        Optional<ExpressionSegment> sqlExpression = new ExpressionExtractor().extract(valueNode);
        if (sqlExpression.isPresent() && sqlExpression.get() instanceof CommonExpressionSegment) {
            CommonExpressionSegment commonExpressionSegment = (CommonExpressionSegment)sqlExpression.get();
            Optional<ParserRuleContext> expressionNode = ExtractorUtils.findFirstChildNode(valueNode, RuleName.QUESTION);
            if (expressionNode.isPresent()) {
                Integer index = questionNodeIndexMap.get(expressionNode.get());
                commonExpressionSegment.setIndex(index);
            } else {
                expressionNode = ExtractorUtils.findFirstChildNode(valueNode, RuleName.NUMBER);
                if (expressionNode.isPresent()) {
                    commonExpressionSegment.setValue(NumberUtil.getExactlyNumber(expressionNode.get().getText(), 10));
                }
            } 
        }
        return sqlExpression;
    }
    
    private Optional<ConditionSegment> buildPredicateCondition(final Map<ParserRuleContext, Integer> questionNodeIndexMap, final ParserRuleContext exprNode) {
        Optional<ParserRuleContext> predicateNode = ExtractorUtils.findFirstChildNode(exprNode, RuleName.PREDICATE);
        if (!predicateNode.isPresent()) {
            return Optional.absent();
        }
        if (1 != predicateNode.get().getParent().getChildCount()) {
            return Optional.absent();
        }
        if (5 == predicateNode.get().getChildCount() && DefaultKeyword.BETWEEN.name().equalsIgnoreCase(predicateNode.get().getChild(1).getText())) {
            Optional<ConditionSegment> result = buildBetweenCondition(questionNodeIndexMap, predicateNode.get());
            if (result.isPresent()) {
                return result;
            }
        }
        if (5 <= predicateNode.get().getChildCount() && DefaultKeyword.IN.name().equalsIgnoreCase(predicateNode.get().getChild(1).getText())) {
            Optional<ConditionSegment> result = buildInCondition(questionNodeIndexMap, predicateNode.get());
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.absent();
    }
    
    private Optional<ConditionSegment> buildBetweenCondition(final Map<ParserRuleContext, Integer> questionNodeIndexMap, final ParserRuleContext predicateNode) {
        Optional<ColumnSegment> column = buildColumn((ParserRuleContext) predicateNode.getChild(0));
        if (!column.isPresent()) {
            return Optional.absent();
        }
        Optional<ExpressionSegment> beginSQLExpression = buildExpression(questionNodeIndexMap, (ParserRuleContext) predicateNode.getChild(2));
        Optional<ExpressionSegment> endSQLExpression = buildExpression(questionNodeIndexMap, (ParserRuleContext) predicateNode.getChild(4));
        if (beginSQLExpression.isPresent() && endSQLExpression.isPresent()) {
            return Optional.of(new ConditionSegment(column.get(), ShardingOperator.BETWEEN, new BetweenValueExpressionSegment(beginSQLExpression.get(), endSQLExpression.get())));
        }
        return Optional.absent();
    }
    
    private Optional<ConditionSegment> buildInCondition(final Map<ParserRuleContext, Integer> questionNodeIndexMap, final ParserRuleContext predicateNode) {
        Optional<ColumnSegment> column = buildColumn((ParserRuleContext) predicateNode.getChild(0));
        if (!column.isPresent()) {
            return Optional.absent();
        }
        List<ExpressionSegment> sqlExpressions = new LinkedList<>();
        for (int i = 3; i < predicateNode.getChildCount(); i++) {
            if (RuleName.SIMPLE_EXPR.getName().equals(predicateNode.getChild(i).getClass().getSimpleName())) {
                Optional<ExpressionSegment> expression = buildExpression(questionNodeIndexMap, (ParserRuleContext) predicateNode.getChild(i));
                if (!expression.isPresent()) {
                    sqlExpressions.clear();
                    break;
                }
                sqlExpressions.add(expression.get());
            }
        }
        if (!sqlExpressions.isEmpty()) {
            InValueExpressionSegment inExpressionSegment = new InValueExpressionSegment();
            inExpressionSegment.getSqlExpressions().addAll(sqlExpressions);
            return Optional.of(new ConditionSegment(column.get(), ShardingOperator.IN, inExpressionSegment));
        }
        return Optional.absent();
    }
    
    private Optional<ColumnSegment> buildColumn(final ParserRuleContext parentNode) {
        return new ColumnSegmentExtractor(tableAlias).extract(parentNode);
    }
}
