package io.github.orafit.rewrite;

import io.github.orafit.translation.ColumnTypeResolver;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies implicit Oracle character-to-NUMBER conversion only where physical metadata proves it.
 */
public final class DatabaseTypeCoercionRule {
    /** Resolves expression families for scalar lowering and its JDBC metadata plan. */
    public static ColumnTypeResolver.Kind expressionKind(
            Expression value, PlainSelect select, ColumnTypeResolver resolver)
            throws TranslationException {
        try {
            return kind(value, Scope.from(select, resolver));
        } catch (ResolutionFailure failure) {
            throw failure.cause;
        }
    }

    public boolean rewrite(Statement statement, ColumnTypeResolver resolver)
            throws TranslationException {
        Change change = new Change();
        try {
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited =
                    statement instanceof Select select ? cteKinds(select, resolver) : Map.of();
            for (PlainSelect select : SelectTrees.plain(statement)) {
                Scope scope = Scope.from(select, resolver, inherited);
                Coercer coercer = new Coercer(scope, change);
                if (select.getSelectItems() != null)
                    for (SelectItem<?> item : select.getSelectItems())
                        coercer.apply(item.getExpression());
                coercer.apply(select.getWhere());
                coercer.apply(select.getHaving());
                if (select.getJoins() != null)
                    for (Join join : select.getJoins())
                        if (join.getOnExpressions() != null)
                            for (Expression on : join.getOnExpressions()) coercer.apply(on);
            }
            for (SetOperationList set : SelectTrees.sets(statement))
                reconcileSet(set, resolver, inherited, change);
            if (statement instanceof Update update) rewriteUpdate(update, resolver, change);
            if (statement instanceof Insert insert) rewriteInsert(insert, resolver, change);
        } catch (ResolutionFailure failure) {
            throw failure.cause;
        }
        return change.changed;
    }

    private static void reconcileSet(
            SetOperationList set,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited,
            Change change) {
        if (set.getSelects() == null || set.getSelects().size() < 2) return;
        int width = Integer.MAX_VALUE;
        List<PlainSelect> branches = new java.util.ArrayList<>();
        for (Select branch : set.getSelects()) {
            PlainSelect select = plainBranch(branch);
            if (select == null || select.getSelectItems() == null) return;
            branches.add(select);
            int branchWidth = outputWidth(select);
            if (branchWidth == 0) return;
            width = Math.min(width, branchWidth);
        }
        for (int index = 0; index < width; index++) {
            var setType = io.github.orafit.metadata.ResultMetadataPlanner.setColumnType(set, index);
            boolean varchar2 =
                    setType.kind() == io.github.orafit.translation.ResultMetadataPlan.Kind.VARCHAR2;
            Integer varcharWidth = varchar2 ? setType.precision() : null;
            if (setType.kind() == io.github.orafit.translation.ResultMetadataPlan.Kind.AUTO) {
                boolean fixed = false;
                for (PlainSelect select : branches)
                    fixed |=
                            outputKind(select, index, resolver, inherited)
                                    == ColumnTypeResolver.Kind.FIXED_CHAR;
                if (fixed) {
                    CharSetType physical = physicalSetType(branches, index, resolver, inherited);
                    varchar2 = !physical.fixed();
                    varcharWidth = physical.width();
                }
            }
            if (varchar2) {
                for (PlainSelect select : branches)
                    if (outputKind(select, index, resolver, inherited)
                            == ColumnTypeResolver.Kind.FIXED_CHAR)
                        preserveCharText(select, index, varcharWidth, resolver, inherited, change);
            }
            boolean number = false;
            for (PlainSelect select : branches) {
                ColumnTypeResolver.Kind type = outputKind(select, index, resolver, inherited);
                number |= type == ColumnTypeResolver.Kind.NUMBER;
            }
            if (!number) continue;
            for (PlainSelect select : branches)
                coerceOutput(select, index, resolver, inherited, change);
        }
    }

    /** Oracle character datatype of one set-operation output column. */
    public record CharSetType(boolean fixed, Integer width) {}

    /**
     * Resolves the Oracle datatype of a character set-operation column from physical metadata, or
     * returns {@code null} when no branch is a physical CHAR or the type cannot be proven.
     */
    public static CharSetType charSetType(
            SetOperationList set, int index, ColumnTypeResolver resolver) {
        if (resolver == ColumnTypeResolver.NONE
                || set.getSelects() == null
                || set.getSelects().size() < 2) return null;
        try {
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited = cteKinds(set, resolver);
            List<PlainSelect> branches = new java.util.ArrayList<>();
            for (Select branch : set.getSelects()) {
                PlainSelect select = plainBranch(branch);
                if (select == null || select.getSelectItems() == null) return null;
                branches.add(select);
            }
            boolean fixed = false;
            for (PlainSelect select : branches)
                fixed |=
                        outputKind(select, index, resolver, inherited)
                                == ColumnTypeResolver.Kind.FIXED_CHAR;
            return fixed ? physicalSetType(branches, index, resolver, inherited) : null;
        } catch (ResolutionFailure | IndexOutOfBoundsException failure) {
            return null;
        }
    }

    /**
     * Applies Oracle's character set-operation datatype rule when the CHAR widths come from
     * physical metadata: equal CHAR widths keep CHAR(n), while different widths or any VARCHAR2
     * branch produce VARCHAR2 of the largest width. Branches whose character type cannot be proven
     * are rejected.
     */
    private static CharSetType physicalSetType(
            List<PlainSelect> branches,
            int index,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited) {
        Integer fixedWidth = null;
        int maximum = 0;
        boolean varchar2 = false;
        boolean unknownWidth = false;
        for (PlainSelect select : branches) {
            Output output = output(select, index);
            if (output == null) throw setCharWidth();
            Expression value = output.expression();
            if (value instanceof NullValue) continue;
            Scope scope = Scope.from(output.owner(), resolver, inherited);
            Integer current;
            if (value instanceof StringValue literal) {
                current = literalWidth(literal);
            } else {
                ColumnTypeResolver.Kind type = kind(value, scope);
                if (type == ColumnTypeResolver.Kind.TEXT
                        || (type == ColumnTypeResolver.Kind.FIXED_CHAR && nvl(value))) {
                    varchar2 = true;
                    Integer width = textWidth(value, scope);
                    if (width == null && type == ColumnTypeResolver.Kind.FIXED_CHAR)
                        throw setCharWidth();
                    if (width == null) unknownWidth = true;
                    else maximum = Math.max(maximum, width);
                    continue;
                }
                current =
                        type == ColumnTypeResolver.Kind.FIXED_CHAR ? charWidth(value, scope) : null;
            }
            if (current == null) throw setCharWidth();
            maximum = Math.max(maximum, current);
            if (fixedWidth == null) fixedWidth = current;
            else if (!fixedWidth.equals(current)) varchar2 = true;
        }
        if (!varchar2) return new CharSetType(true, fixedWidth);
        return new CharSetType(false, unknownWidth ? null : maximum);
    }

    /** NVL over a character first argument is VARCHAR2 in Oracle (not CHAR). */
    private static boolean nvl(Expression value) {
        return value instanceof Function function
                && function.getName() != null
                && List.of("NVL", "ORAFIT.NVL")
                        .contains(function.getName().toUpperCase(Locale.ROOT));
    }

    /**
     * Declared width of a VARCHAR2 set branch: a column, or NVL(a, b) whose width is the larger of
     * its two arguments (Oracle: NVL(CHAR(3), ' ') is VARCHAR2(3)). Unprovable widths are null.
     */
    private static Integer textWidth(Expression value, Scope scope) {
        if (value instanceof Column column) return scope.width(column);
        if (!nvl(value)) return null;
        ExpressionList<?> arguments = ((Function) value).getParameters();
        if (arguments == null || arguments.size() != 2) return null;
        Integer first = nvlArgument(arguments.get(0), scope);
        Integer second = nvlArgument(arguments.get(1), scope);
        return first == null || second == null ? null : Math.max(first, second);
    }

    private static Integer nvlArgument(Expression argument, Scope scope) {
        return argument instanceof StringValue literal
                ? literalWidth(literal)
                : charWidth(argument, scope);
    }

    private static ResolutionFailure setCharWidth() {
        return new ResolutionFailure(
                new TranslationException(
                        "SET_CHAR_WIDTH",
                        "CHAR set operations require explicit character widths in every branch"));
    }

    /** Oracle text literals are CHAR; only ASCII literals have one width in both length units. */
    private static Integer literalWidth(StringValue literal) {
        if (literal.getPrefix() != null) return null;
        String value = literal.getValue();
        if (value.isEmpty() || value.contains("''")) return null;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 0x7f) return null;
        return value.length();
    }

    private static Integer charWidth(Expression value, Scope scope) {
        if (value instanceof Column column) return scope.width(column);
        if (value instanceof CastExpression cast && cast.getColDataType() != null) {
            String type = cast.getColDataType().getDataType().toUpperCase(Locale.ROOT).strip();
            List<String> arguments = cast.getColDataType().getArgumentsStringList();
            if (!type.matches("CHAR|BPCHAR") || arguments == null || arguments.size() != 1)
                return null;
            String length = arguments.get(0).strip();
            return length.matches("[1-9][0-9]{0,4}") ? Integer.valueOf(length) : null;
        }
        if (value instanceof Function lowered
                && "orafit.cast_char".equalsIgnoreCase(lowered.getName())
                && lowered.getParameters() != null
                && lowered.getParameters().size() == 3
                && lowered.getParameters().get(1) instanceof LongValue length
                && lowered.getParameters().get(2)
                        instanceof net.sf.jsqlparser.expression.BooleanValue bytes
                && bytes.getValue()) return Math.toIntExact(length.getValue());
        if (value instanceof Function function
                && function.getName() != null
                && List.of("MIN", "MAX").contains(function.getName().toUpperCase(Locale.ROOT))
                && !function.isDistinct()
                && function.getParameters() != null
                && function.getParameters().size() == 1)
            return charWidth(function.getParameters().get(0), scope);
        return null;
    }

    private record Output(PlainSelect owner, Expression expression) {}

    /** Finds the projected expression at {@code index}, descending through {@code SELECT *}. */
    private static Output output(PlainSelect select, int index) {
        if (select.getSelectItems().size() == 1
                && select.getSelectItems().get(0).getExpression() instanceof AllColumns) {
            if (!(select.getFromItem() instanceof ParenthesedSelect nested)
                    || select.getJoins() != null) return null;
            PlainSelect inner = plainBranch(nested.getSelect());
            return inner == null || inner.getSelectItems() == null ? null : output(inner, index);
        }
        if (index >= select.getSelectItems().size()) return null;
        Expression expression = select.getSelectItems().get(index).getExpression();
        return expression instanceof AllColumns ? null : new Output(select, expression);
    }

    private static void preserveCharText(
            PlainSelect select,
            int index,
            Integer width,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited,
            Change change) {
        SelectItem<?> first = select.getSelectItems().get(0);
        if (first.getExpression() instanceof AllColumns
                && select.getFromItem() instanceof ParenthesedSelect nested) {
            PlainSelect inner = plainBranch(nested.getSelect());
            if (inner == null || inner.getSelectItems() == null) throw setCharWidth();
            preserveCharText(inner, index, width, resolver, inherited, change);
            return;
        }
        var item = select.getSelectItems().get(index);
        if (kind(item.getExpression(), Scope.from(select, resolver, inherited))
                != ColumnTypeResolver.Kind.FIXED_CHAR) return;
        // orafit.char_text keeps CHAR blank padding; the bounded VARCHAR cast reports the
        // Oracle VARCHAR2 width even where the set is nested below another projection.
        Expression text = charText(item.getExpression());
        if (width != null) text = new CastExpression(text, "varchar(" + width + ")");
        select.getSelectItems().set(index, SelectItem.from(text, outputAlias(item)));
        change.changed = true;
    }

    /**
     * Pads CHAR blanks for a VARCHAR2 set branch. NVL over a CHAR argument pads that argument:
     * orafit.nvl(text, text) would trim the blanks if it received the padded result.
     */
    private static Expression charText(Expression value) {
        if (!nvl(value)) return new Function("orafit.char_text", value);
        ExpressionList<?> arguments = ((Function) value).getParameters();
        return new Function(
                "orafit.nvl", new Function("orafit.char_text", arguments.get(0)), arguments.get(1));
    }

    /** Keeps the projected column name when wrapping a bare column reference. */
    private static net.sf.jsqlparser.expression.Alias outputAlias(SelectItem<?> item) {
        if (item.getAlias() != null) return item.getAlias();
        if (item.getExpression() instanceof Column column)
            return new net.sf.jsqlparser.expression.Alias(column.getColumnName(), true);
        return null;
    }

    private static PlainSelect plainBranch(Select select) {
        while (select instanceof ParenthesedSelect nested) select = nested.getSelect();
        return select instanceof PlainSelect plain ? plain : null;
    }

    private static int outputWidth(PlainSelect select) {
        if (select.getSelectItems().size() != 1
                || !(select.getSelectItems().get(0).getExpression() instanceof AllColumns))
            return select.getSelectItems().size();
        if (select.getFromItem() instanceof ParenthesedSelect nested)
            return selectWidth(nested.getSelect());
        return 0;
    }

    private static int selectWidth(Select select) {
        if (select instanceof ParenthesedSelect nested) return selectWidth(nested.getSelect());
        if (select instanceof PlainSelect plain) return outputWidth(plain);
        if (select instanceof SetOperationList set
                && set.getSelects() != null
                && !set.getSelects().isEmpty()) return selectWidth(set.getSelects().get(0));
        return 0;
    }

    private static ColumnTypeResolver.Kind outputKind(
            PlainSelect select,
            int index,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited) {
        SelectItem<?> item = select.getSelectItems().get(0);
        if (item.getExpression() instanceof AllColumns
                && select.getFromItem() instanceof ParenthesedSelect nested)
            return outputKind(nested.getSelect(), index, resolver, inherited);
        return index < select.getSelectItems().size()
                ? kind(
                        select.getSelectItems().get(index).getExpression(),
                        Scope.from(select, resolver, inherited))
                : ColumnTypeResolver.Kind.UNKNOWN;
    }

    private static ColumnTypeResolver.Kind outputKind(
            Select select,
            int index,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited) {
        if (select instanceof ParenthesedSelect nested)
            return outputKind(nested.getSelect(), index, resolver, inherited);
        if (select instanceof PlainSelect plain)
            return outputKind(plain, index, resolver, inherited);
        if (select instanceof SetOperationList set && set.getSelects() != null) {
            ColumnTypeResolver.Kind result = ColumnTypeResolver.Kind.UNKNOWN;
            for (Select branch : set.getSelects())
                result = merge(result, outputKind(branch, index, resolver, inherited));
            return result;
        }
        return ColumnTypeResolver.Kind.UNKNOWN;
    }

    private static void coerceOutput(
            PlainSelect select,
            int index,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited,
            Change change) {
        SelectItem<?> item = select.getSelectItems().get(0);
        if (item.getExpression() instanceof AllColumns
                && select.getFromItem() instanceof ParenthesedSelect nested) {
            coerceOutput(nested.getSelect(), index, resolver, inherited, change);
            return;
        }
        item = select.getSelectItems().get(index);
        ColumnTypeResolver.Kind current =
                kind(item.getExpression(), Scope.from(select, resolver, inherited));
        Expression converted;
        if (textual(current)) {
            throw new ResolutionFailure(
                    new TranslationException(
                            "SET_DATATYPE",
                            "ORA-01790: expression must have same datatype as corresponding expression"));
        } else if (current == ColumnTypeResolver.Kind.UNKNOWN
                && item.getExpression() instanceof NullValue) {
            converted = new CastExpression(item.getExpression(), "numeric");
        } else {
            return;
        }
        select.getSelectItems().set(index, SelectItem.from(converted, item.getAlias()));
        change.changed = true;
    }

    private static void coerceOutput(
            Select select,
            int index,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited,
            Change change) {
        if (select instanceof ParenthesedSelect nested) {
            coerceOutput(nested.getSelect(), index, resolver, inherited, change);
        } else if (select instanceof PlainSelect plain) {
            coerceOutput(plain, index, resolver, inherited, change);
        } else if (select instanceof SetOperationList set && set.getSelects() != null) {
            for (Select branch : set.getSelects())
                coerceOutput(branch, index, resolver, inherited, change);
        }
    }

    private static void rewriteUpdate(Update update, ColumnTypeResolver resolver, Change change) {
        if (update.getTable() == null) return;
        Scope scope = Scope.single(update.getTable(), resolver);
        Coercer coercer = new Coercer(scope, change);
        coercer.apply(update.getWhere());
        if (update.getUpdateSets() == null) return;
        for (UpdateSet set : update.getUpdateSets()) {
            for (int i = 0; i < Math.min(set.getColumns().size(), set.getValues().size()); i++) {
                Expression value = set.getValue(i);
                coercer.apply(value);
                if (scope.target(set.getColumn(i)) == ColumnTypeResolver.Kind.NUMBER
                        && kind(value, scope) != ColumnTypeResolver.Kind.NUMBER
                        && !(value instanceof NullValue)) {
                    setValue(set.getValues(), i, OracleCoercion.numberDeferred(value));
                    change.changed = true;
                }
            }
        }
    }

    private static void rewriteInsert(Insert insert, ColumnTypeResolver resolver, Change change) {
        if (insert.getTable() == null || insert.getColumns() == null || insert.getSelect() == null)
            return;
        Scope target = Scope.single(insert.getTable(), resolver);
        if (insert.getSelect() instanceof Values values) {
            coerceTargets(insert, values.getExpressions(), target, change);
        } else {
            coerceSelectTargets(insert.getSelect(), insert, target, change);
        }
    }

    private static void coerceSelectTargets(
            Select select, Insert insert, Scope target, Change change) {
        if (select instanceof ParenthesedSelect nested) {
            coerceSelectTargets(nested.getSelect(), insert, target, change);
            return;
        }
        if (select instanceof SetOperationList set && set.getSelects() != null) {
            for (Select branch : set.getSelects())
                coerceSelectTargets(branch, insert, target, change);
            return;
        }
        if (!(select instanceof PlainSelect plain) || plain.getSelectItems() == null) return;
        for (int i = 0;
                i < Math.min(insert.getColumns().size(), plain.getSelectItems().size());
                i++) {
            SelectItem<?> item = plain.getSelectItems().get(i);
            Expression converted =
                    coerceTarget(item.getExpression(), target, insert.getColumns().get(i));
            if (converted != item.getExpression()) {
                plain.getSelectItems().set(i, SelectItem.from(converted, item.getAlias()));
                change.changed = true;
            }
        }
    }

    private static void coerceTargets(
            Insert insert, ExpressionList<Expression> values, Scope target, Change change) {
        int count = Math.min(insert.getColumns().size(), values.size());
        for (int i = 0; i < count; i++) {
            Expression value = values.get(i);
            Expression converted = coerceTarget(value, target, insert.getColumns().get(i));
            if (converted != value) {
                values.set(i, converted);
                change.changed = true;
            }
        }
    }

    private static Expression coerceTarget(Expression value, Scope target, Column column) {
        ColumnTypeResolver.Kind targetKind = target.target(column);
        if (targetKind == ColumnTypeResolver.Kind.NUMBER
                && kind(value, target) != ColumnTypeResolver.Kind.NUMBER
                && !(value instanceof NullValue)) return OracleCoercion.numberDeferred(value);
        if (temporalTarget(targetKind) && temporalCandidate(value))
            return new CastExpression(value, postgresType(targetKind));
        return value;
    }

    private static boolean temporalTarget(ColumnTypeResolver.Kind kind) {
        return switch (kind) {
            case DATE, TIME, TIMESTAMP, TIMESTAMP_WITH_TIMEZONE -> true;
            default -> false;
        };
    }

    private static boolean temporalCandidate(Expression value) {
        return value instanceof JdbcParameter
                || value instanceof JdbcNamedParameter
                || value instanceof NullValue;
    }

    private static String postgresType(ColumnTypeResolver.Kind kind) {
        return switch (kind) {
            case DATE -> "date";
            case TIME -> "time";
            case TIMESTAMP -> "timestamp";
            case TIMESTAMP_WITH_TIMEZONE -> "timestamptz";
            default -> throw new IllegalArgumentException("Not a temporal kind: " + kind);
        };
    }

    private static final class Coercer extends ExpressionVisitorAdapter<Void> {
        private final Scope scope;
        private final Change change;

        Coercer(Scope scope, Change change) {
            this.scope = scope;
            this.change = change;
        }

        void apply(Expression expression) {
            if (expression != null) expression.accept(this, null);
        }

        @Override
        protected <S> Void visitBinaryExpression(BinaryExpression binary, S context) {
            super.visitBinaryExpression(binary, context);
            preserveFixedCharPaddingComparison(binary);
            if (scope.resolver == ColumnTypeResolver.NONE || !numericContext(binary)) return null;
            ColumnTypeResolver.Kind left = kind(binary.getLeftExpression(), scope);
            ColumnTypeResolver.Kind right = kind(binary.getRightExpression(), scope);
            if (left == ColumnTypeResolver.Kind.NUMBER
                    && numericCandidate(binary.getRightExpression(), right)) {
                binary.setRightExpression(
                        OracleCoercion.numberDeferred(binary.getRightExpression()));
                change.changed = true;
            } else if (right == ColumnTypeResolver.Kind.NUMBER
                    && numericCandidate(binary.getLeftExpression(), left)) {
                binary.setLeftExpression(OracleCoercion.numberDeferred(binary.getLeftExpression()));
                change.changed = true;
            }
            return null;
        }

        private void preserveFixedCharPaddingComparison(BinaryExpression binary) {
            if (!comparison(binary)) return;
            ColumnTypeResolver.Kind left = kind(binary.getLeftExpression(), scope);
            ColumnTypeResolver.Kind right = kind(binary.getRightExpression(), scope);
            if (left == ColumnTypeResolver.Kind.FIXED_CHAR
                    && right == ColumnTypeResolver.Kind.TEXT
                    && !(binary.getRightExpression() instanceof StringValue)
                    && !paddingFunction(binary.getRightExpression())) {
                binary.setLeftExpression(
                        new Function("orafit.char_text", binary.getLeftExpression()));
                change.changed = true;
            } else if (right == ColumnTypeResolver.Kind.FIXED_CHAR
                    && left == ColumnTypeResolver.Kind.TEXT
                    && !(binary.getLeftExpression() instanceof StringValue)
                    && !paddingFunction(binary.getLeftExpression())) {
                binary.setRightExpression(
                        new Function("orafit.char_text", binary.getRightExpression()));
                change.changed = true;
            } else if (left == ColumnTypeResolver.Kind.FIXED_CHAR
                    && paddingFunction(binary.getRightExpression())) {
                binary.setRightExpression(
                        new CastExpression(binary.getRightExpression(), "bpchar"));
                change.changed = true;
            } else if (right == ColumnTypeResolver.Kind.FIXED_CHAR
                    && paddingFunction(binary.getLeftExpression())) {
                binary.setLeftExpression(new CastExpression(binary.getLeftExpression(), "bpchar"));
                change.changed = true;
            }
        }

        @Override
        public <S> Void visit(Function function, S context) {
            super.visit(function, context);
            if ((OracleCoercion.unqualified(function, "NULLIF")
                            || OracleCoercion.unqualified(function, "COALESCE"))
                    && function.getParameters() != null) {
                boolean number = false, text = false;
                for (Expression argument : function.getParameters()) {
                    ColumnTypeResolver.Kind type = kind(argument, scope);
                    if (argument instanceof JdbcNamedParameter
                            || argument instanceof JdbcParameter) {
                        throw new ResolutionFailure(
                                new TranslationException(
                                        "FUNCTION_BIND_TYPE",
                                        "NULLIF/COALESCE bind arguments require an explicit CAST to preserve Oracle datatype validation"));
                    }
                    number |= type == ColumnTypeResolver.Kind.NUMBER;
                    text |= textual(type);
                }
                if (number && text)
                    throw new ResolutionFailure(
                            new TranslationException(
                                    "FUNCTION_DATATYPE", "ORA-00932: inconsistent datatypes"));
            }
            if (scope.resolver != ColumnTypeResolver.NONE
                    && function.getName() != null
                    && List.of("SUM", "AVG").contains(function.getName().toUpperCase(Locale.ROOT))
                    && function.getParameters() != null
                    && function.getParameters().size() == 1) {
                Expression value = function.getParameters().get(0);
                if (kind(value, scope) != ColumnTypeResolver.Kind.NUMBER
                        && !(value instanceof NullValue)) {
                    setValue(function.getParameters(), 0, OracleCoercion.numberDeferred(value));
                    change.changed = true;
                }
            }
            return null;
        }

        @Override
        public <S> Void visit(InExpression in, S context) {
            super.visit(in, context);
            if (!(in.getRightExpression() instanceof ExpressionList<?> values)) return null;
            if (in.getLeftExpression() instanceof ExpressionList<?> columns) {
                for (Expression value : values) {
                    if (!(value instanceof ExpressionList<?> row) || row.size() != columns.size())
                        continue;
                    for (int i = 0; i < columns.size(); i++) {
                        if (kind(columns.get(i), scope) == ColumnTypeResolver.Kind.FIXED_CHAR
                                && paddingFunction(row.get(i))) {
                            setValue(row, i, new CastExpression(row.get(i), "bpchar"));
                            change.changed = true;
                        }
                    }
                }
                return null;
            }
            ColumnTypeResolver.Kind left = kind(in.getLeftExpression(), scope);
            boolean number = false;
            boolean text = false;
            for (Expression value : values) {
                ColumnTypeResolver.Kind current = kind(value, scope);
                number |= current == ColumnTypeResolver.Kind.NUMBER;
                text |= textual(current);
            }
            if (textual(left) && number) {
                in.setLeftExpression(OracleCoercion.numberDeferred(in.getLeftExpression()));
                change.changed = true;
            } else if (left == ColumnTypeResolver.Kind.NUMBER && text) {
                for (int i = 0; i < values.size(); i++)
                    if (textual(kind(values.get(i), scope))) {
                        setValue(values, i, OracleCoercion.numberDeferred(values.get(i)));
                        change.changed = true;
                    }
            }
            return null;
        }
    }

    private static ColumnTypeResolver.Kind kind(Expression value, Scope scope) {
        if (value instanceof net.sf.jsqlparser.expression.DateTimeLiteralExpression literal)
            return literal.getType()
                            == net.sf.jsqlparser.expression.DateTimeLiteralExpression.DateTime.DATE
                    ? ColumnTypeResolver.Kind.DATE
                    : ColumnTypeResolver.Kind.TIMESTAMP;
        if (value == null || value instanceof NullValue) return ColumnTypeResolver.Kind.UNKNOWN;
        if (value instanceof StringValue || OracleCoercion.knownText(value))
            return ColumnTypeResolver.Kind.TEXT;
        if (value instanceof LongValue
                || value instanceof DoubleValue
                || value instanceof SignedExpression
                || OracleCoercion.knownNumber(value)
                || value instanceof Addition
                || value instanceof Subtraction
                || value instanceof Multiplication
                || value instanceof Division) return ColumnTypeResolver.Kind.NUMBER;
        if (value instanceof Column column) return scope.resolve(column);
        if (value instanceof CastExpression cast && cast.getColDataType() != null) {
            String type =
                    cast.getColDataType()
                            .getDataType()
                            .toUpperCase(Locale.ROOT)
                            .replaceFirst("\\s*\\(.*", "")
                            .strip();
            if (type.equals("DATE")) return ColumnTypeResolver.Kind.DATE;
            if (type.equals("TIMESTAMP")) return ColumnTypeResolver.Kind.TIMESTAMP;
            if (type.matches("NUMBER|NUMERIC|DECIMAL|INTEGER|BIGINT|SMALLINT|REAL|FLOAT|DOUBLE"))
                return ColumnTypeResolver.Kind.NUMBER;
            if (type.matches("CHAR|NCHAR|BPCHAR")) return ColumnTypeResolver.Kind.FIXED_CHAR;
            if (type.matches("VARCHAR|VARCHAR2|NVARCHAR2|TEXT|CLOB"))
                return ColumnTypeResolver.Kind.TEXT;
        }
        if (value instanceof Function function && function.getName() != null) {
            String name = function.getName().toUpperCase(Locale.ROOT);
            if (List.of(
                            "TO_DATE",
                            "ADD_MONTHS",
                            "LAST_DAY",
                            "ORAFIT.TO_DATE",
                            "ORAFIT.ADD_MONTHS",
                            "ORAFIT.LAST_DAY",
                            "ORAFIT.CAST_DATE",
                            "ORAFIT.SYSDATE")
                    .contains(name)) return ColumnTypeResolver.Kind.DATE;
            if (name.equals("ORAFIT.CAST_CHAR")) return ColumnTypeResolver.Kind.FIXED_CHAR;
            if (List.of("COUNT", "SUM", "AVG", "ORAFIT.DIVIDE").contains(name))
                return ColumnTypeResolver.Kind.NUMBER;
            if (List.of("NVL", "ORAFIT.NVL", "MIN", "MAX").contains(name)
                    && function.getParameters() != null
                    && !function.getParameters().isEmpty())
                return kind(function.getParameters().get(0), scope);
        }
        if (value instanceof CaseExpression caseExpression) {
            ColumnTypeResolver.Kind result = kind(caseExpression.getElseExpression(), scope);
            if (caseExpression.getWhenClauses() != null)
                for (Expression clause : caseExpression.getWhenClauses())
                    if (clause instanceof net.sf.jsqlparser.expression.WhenClause when)
                        result = merge(result, kind(when.getThenExpression(), scope));
            return result;
        }
        return ColumnTypeResolver.Kind.UNKNOWN;
    }

    private static boolean numericContext(BinaryExpression value) {
        return value instanceof Addition
                || value instanceof Subtraction
                || value instanceof Multiplication
                || value instanceof Division
                || value instanceof EqualsTo
                || value instanceof NotEqualsTo
                || value instanceof GreaterThan
                || value instanceof GreaterThanEquals
                || value instanceof MinorThan
                || value instanceof MinorThanEquals;
    }

    private static boolean comparison(BinaryExpression value) {
        return value instanceof EqualsTo
                || value instanceof NotEqualsTo
                || value instanceof GreaterThan
                || value instanceof GreaterThanEquals
                || value instanceof MinorThan
                || value instanceof MinorThanEquals;
    }

    private static boolean paddingFunction(Expression value) {
        if (!(value instanceof Function function) || function.getName() == null) return false;
        String name = function.getName().toUpperCase(Locale.ROOT);
        return name.equals("LPAD")
                || name.equals("RPAD")
                || name.equals("ORAFIT.LPAD")
                || name.equals("ORAFIT.RPAD");
    }

    private static boolean textual(ColumnTypeResolver.Kind kind) {
        return kind == ColumnTypeResolver.Kind.TEXT || kind == ColumnTypeResolver.Kind.FIXED_CHAR;
    }

    private static boolean numericCandidate(Expression value, ColumnTypeResolver.Kind kind) {
        return textual(kind)
                || (kind == ColumnTypeResolver.Kind.UNKNOWN
                        && (value instanceof Column
                                || value instanceof JdbcParameter
                                || value instanceof JdbcNamedParameter));
    }

    @SuppressWarnings("unchecked")
    private static void setValue(ExpressionList<?> values, int index, Expression value) {
        ((ExpressionList<Expression>) values).set(index, value);
    }

    private record Relation(String schema, String table) {
        static Relation from(Table table) {
            return new Relation(table.getUnquotedSchemaName(), table.getUnquotedName());
        }
    }

    private static Map<String, Map<String, ColumnTypeResolver.Kind>> cteKinds(
            Select select, ColumnTypeResolver resolver) {
        Map<String, Map<String, ColumnTypeResolver.Kind>> visible = new LinkedHashMap<>();
        if (select.getWithItemsList() == null) return visible;
        for (WithItem<?> item : select.getWithItemsList())
            if (item.getSelect() != null && item.getUnquotedAliasName() != null)
                visible.put(
                        item.getUnquotedAliasName().toLowerCase(Locale.ROOT),
                        outputKinds(item.getSelect().getSelect(), resolver, visible));
        return visible;
    }

    private static Map<String, ColumnTypeResolver.Kind> outputKinds(
            Select select,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited) {
        if (select instanceof ParenthesedSelect nested)
            return outputKinds(nested.getSelect(), resolver, inherited);
        if (select instanceof SetOperationList set && set.getSelects() != null) {
            Map<String, ColumnTypeResolver.Kind> result = new LinkedHashMap<>();
            for (Select branch : set.getSelects())
                outputKinds(branch, resolver, inherited)
                        .forEach(
                                (name, type) ->
                                        result.merge(name, type, DatabaseTypeCoercionRule::merge));
            return result;
        }
        if (!(select instanceof PlainSelect plain) || plain.getSelectItems() == null)
            return Map.of();
        Scope scope = Scope.from(plain, resolver, inherited);
        Map<String, ColumnTypeResolver.Kind> result = new LinkedHashMap<>();
        for (SelectItem<?> item : plain.getSelectItems()) {
            if (item.getExpression() instanceof AllColumns
                    && plain.getFromItem() instanceof ParenthesedSelect nested) {
                result.putAll(outputKinds(nested.getSelect(), resolver, inherited));
                continue;
            }
            String name =
                    item.getAlias() != null
                            ? item.getAlias().getUnquotedName()
                            : item.getExpression() instanceof Column column
                                    ? column.getUnquotedColumnName()
                                    : null;
            if (name != null)
                result.put(name.toLowerCase(Locale.ROOT), kind(item.getExpression(), scope));
        }
        return result;
    }

    private static ColumnTypeResolver.Kind merge(
            ColumnTypeResolver.Kind left, ColumnTypeResolver.Kind right) {
        if (left == right || right == ColumnTypeResolver.Kind.UNKNOWN) return left;
        if (left == ColumnTypeResolver.Kind.UNKNOWN) return right;
        if (textual(left) && textual(right)) return ColumnTypeResolver.Kind.TEXT;
        return ColumnTypeResolver.Kind.UNKNOWN;
    }

    /** Resolves the CHAR width of a named output column of a derived table or CTE. */
    private static Integer namedWidth(Select select, String name, ColumnTypeResolver resolver) {
        if (select instanceof ParenthesedSelect nested)
            return namedWidth(nested.getSelect(), name, resolver);
        if (select instanceof SetOperationList set && set.getSelects() != null) {
            Integer result = null;
            for (Select branch : set.getSelects()) {
                Integer current = namedWidth(branch, name, resolver);
                if (current == null || (result != null && !result.equals(current))) return null;
                result = current;
            }
            return result;
        }
        if (!(select instanceof PlainSelect plain) || plain.getSelectItems() == null) return null;
        Scope scope = Scope.from(plain, resolver);
        if (plain.getSelectItems().size() == 1
                && plain.getSelectItems().get(0).getExpression() instanceof AllColumns)
            return scope.width(new Column(name));
        for (SelectItem<?> item : plain.getSelectItems()) {
            String output =
                    item.getAlias() != null
                            ? item.getAlias().getUnquotedName()
                            : item.getExpression() instanceof Column column
                                    ? column.getUnquotedColumnName()
                                    : null;
            if (output != null && output.equalsIgnoreCase(name))
                return charWidth(item.getExpression(), scope);
        }
        return null;
    }

    private static final class Scope {
        private final ColumnTypeResolver resolver;
        private final Map<String, Relation> relations = new LinkedHashMap<>();
        private final Map<String, Map<String, ColumnTypeResolver.Kind>> derived =
                new LinkedHashMap<>();
        private final Map<String, Select> derivedSelects = new LinkedHashMap<>();

        /**
         * Names visible before the FROM clause (inherited scope and this query's WITH items). A
         * FROM subquery is resolved against this map only, so it never sees its FROM siblings.
         */
        private Map<String, Map<String, ColumnTypeResolver.Kind>> outer = Map.of();

        private Scope(ColumnTypeResolver resolver) {
            this.resolver = resolver;
        }

        static Scope from(PlainSelect select, ColumnTypeResolver resolver) {
            return from(select, resolver, Map.of());
        }

        static Scope from(
                PlainSelect select,
                ColumnTypeResolver resolver,
                Map<String, Map<String, ColumnTypeResolver.Kind>> inherited) {
            Scope scope = new Scope(resolver);
            scope.derived.putAll(inherited);
            if (select.getWithItemsList() != null)
                for (WithItem<?> item : select.getWithItemsList())
                    if (item.getParenthesedStatement() instanceof ParenthesedSelect nested) {
                        String name = item.getUnquotedAliasName().toLowerCase(Locale.ROOT);
                        scope.derived.put(
                                name, outputKinds(nested.getSelect(), resolver, scope.derived));
                        scope.derivedSelects.put(name, nested.getSelect());
                    }
            scope.outer = Map.copyOf(scope.derived);
            scope.add(select.getFromItem());
            if (select.getJoins() != null)
                for (Join join : select.getJoins()) scope.add(join.getFromItem());
            return scope;
        }

        static Scope single(Table table, ColumnTypeResolver resolver) {
            Scope scope = new Scope(resolver);
            scope.add(table);
            return scope;
        }

        ColumnTypeResolver.Kind resolve(Column column) {
            String qualifier = column.getUnquotedTableName();
            Relation relation =
                    qualifier == null ? null : relations.get(qualifier.toLowerCase(Locale.ROOT));
            if (relation != null) return resolved(relation, column);
            if (qualifier != null) {
                Map<String, ColumnTypeResolver.Kind> columns =
                        derived.get(qualifier.toLowerCase(Locale.ROOT));
                return columns == null
                        ? ColumnTypeResolver.Kind.UNKNOWN
                        : columns.getOrDefault(
                                column.getUnquotedColumnName().toLowerCase(Locale.ROOT),
                                ColumnTypeResolver.Kind.UNKNOWN);
            }
            return resolveUnqualified(column);
        }

        private ColumnTypeResolver.Kind resolveUnqualified(Column column) {
            ColumnTypeResolver.Kind found = ColumnTypeResolver.Kind.UNKNOWN;
            int matches = 0;
            for (Relation relation : relations.values().stream().distinct().toList()) {
                ColumnTypeResolver.Kind current = resolved(relation, column);
                if (current == ColumnTypeResolver.Kind.UNKNOWN) continue;
                matches++;
                found = current;
            }
            String name = column.getUnquotedColumnName().toLowerCase(Locale.ROOT);
            for (Map<String, ColumnTypeResolver.Kind> columns : derived.values()) {
                if (!columns.containsKey(name)) continue;
                matches++;
                found = columns.get(name);
            }
            return matches == 1 ? found : ColumnTypeResolver.Kind.UNKNOWN;
        }

        private ColumnTypeResolver.Kind resolved(Relation relation, Column column) {
            try {
                return resolver.resolve(
                        relation.schema(), relation.table(), column.getUnquotedColumnName());
            } catch (TranslationException failure) {
                throw new ResolutionFailure(failure);
            }
        }

        /** Resolves the physical CHAR width of a column visible in this scope, if provable. */
        Integer width(Column column) {
            String name = column.getUnquotedColumnName();
            String qualifier = column.getUnquotedTableName();
            if (qualifier != null) {
                String key = qualifier.toLowerCase(Locale.ROOT);
                Relation relation = relations.get(key);
                if (relation != null) return physicalWidth(relation, name);
                Select definition = derivedSelects.get(key);
                return definition == null ? null : namedWidth(definition, name, resolver);
            }
            if (resolveUnqualified(column) == ColumnTypeResolver.Kind.UNKNOWN) return null;
            for (Relation relation : relations.values().stream().distinct().toList())
                if (resolved(relation, column) != ColumnTypeResolver.Kind.UNKNOWN)
                    return physicalWidth(relation, name);
            String key = name.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Map<String, ColumnTypeResolver.Kind>> entry : derived.entrySet())
                if (entry.getValue().containsKey(key)) {
                    Select definition = derivedSelects.get(entry.getKey());
                    return definition == null ? null : namedWidth(definition, name, resolver);
                }
            return null;
        }

        private Integer physicalWidth(Relation relation, String column) {
            try {
                return resolver.charLength(relation.schema(), relation.table(), column);
            } catch (TranslationException failure) {
                throw new ResolutionFailure(failure);
            }
        }

        ColumnTypeResolver.Kind target(Column column) {
            Relation relation = unique();
            if (relation == null) return ColumnTypeResolver.Kind.UNKNOWN;
            return resolved(relation, column);
        }

        private Relation unique() {
            return relations.values().stream().distinct().limit(2).count() == 1
                    ? relations.values().iterator().next()
                    : null;
        }

        private void add(FromItem item) {
            if (item instanceof ParenthesedSelect nested && nested.getAlias() != null) {
                String name = nested.getAlias().getUnquotedName().toLowerCase(Locale.ROOT);
                derived.put(name, outputKinds(nested.getSelect(), resolver, outer));
                derivedSelects.put(name, nested.getSelect());
                return;
            }
            if (!(item instanceof Table table)) return;
            Map<String, ColumnTypeResolver.Kind> cte =
                    derived.get(table.getUnquotedName().toLowerCase(Locale.ROOT));
            if (cte != null) {
                if (table.getAlias() != null) {
                    String alias = table.getAlias().getUnquotedName().toLowerCase(Locale.ROOT);
                    derived.put(alias, cte);
                    Select definition =
                            derivedSelects.get(table.getUnquotedName().toLowerCase(Locale.ROOT));
                    if (definition != null) derivedSelects.put(alias, definition);
                }
                return;
            }
            Relation relation = Relation.from(table);
            relations.putIfAbsent(relation.table().toLowerCase(Locale.ROOT), relation);
            if (table.getAlias() != null)
                relations.put(
                        table.getAlias().getUnquotedName().toLowerCase(Locale.ROOT), relation);
        }
    }

    private static final class Change {
        boolean changed;
    }

    private static final class ResolutionFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final TranslationException cause;

        ResolutionFailure(TranslationException cause) {
            this.cause = cause;
        }
    }
}
