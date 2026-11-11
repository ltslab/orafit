package io.github.orafit.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.ColumnTypeResolver;
import io.github.orafit.translation.ResultMetadataPlan;
import io.github.orafit.translation.Translation;

import org.junit.jupiter.api.Test;

/**
 * NVL over a physical CHAR column is VARCHAR2 in Oracle, with the largest argument width. A UNION
 * branch with such an NVL must translate when the column widths are known (Oracle 18c XE: NVL of
 * CHAR(3) with ' ' is VARCHAR2(3); the set with a CHAR(2) branch is VARCHAR2(3)).
 */
public final class NvlCharSetOperationTest {
    /** Same physical columns as the bs_char_width fixture: code2 CHAR(2), code3 CHAR(3). */
    private static final ColumnTypeResolver PHYSICAL =
            new ColumnTypeResolver() {
                @Override
                public Kind resolve(String schema, String table, String column) {
                    return switch (column.toLowerCase(java.util.Locale.ROOT)) {
                        case "code2", "code3" -> Kind.FIXED_CHAR;
                        case "text3" -> Kind.TEXT;
                        default -> Kind.UNKNOWN;
                    };
                }

                @Override
                public Integer charLength(String schema, String table, String column) {
                    return switch (column.toLowerCase(java.util.Locale.ROOT)) {
                        case "code2" -> 2;
                        case "code3", "text3" -> 3;
                        default -> null;
                    };
                }
            };

    @Test
    void nvlOverCharPadsItsArgumentBeforeTheFallbackOverload() throws Exception {
        Translation translation =
                new OrafitEngine()
                        .translate(
                                "SELECT NVL(code3, 'Z') AS value FROM bs_char_width "
                                        + "UNION SELECT code2 FROM bs_char_width",
                                PHYSICAL);

        assertTrue(
                translation.sql().contains("orafit.nvl(orafit.char_text(code3), 'Z')"),
                "NVL over CHAR must pad its argument: orafit.nvl(text, text) trims CHAR blanks, "
                        + "but actual: "
                        + translation.sql());
    }

    @Test
    void nvlOfPhysicalCharBecomesVarchar2OfLargestWidth() throws Exception {
        Translation translation =
                new OrafitEngine()
                        .translate(
                                "SELECT NVL(code3, 'Z') AS value FROM bs_char_width "
                                        + "UNION SELECT code2 FROM bs_char_width",
                                PHYSICAL);

        ResultMetadataPlan.Column column = translation.metadata().column(1);
        assertEquals(ResultMetadataPlan.Kind.VARCHAR2, column.kind());
        assertEquals(3, column.precision());
        assertTrue(
                translation.sql().contains("orafit.char_text"),
                "CHAR branches of a VARCHAR2 set must keep their blank padding");
    }

    @Test
    void nvlWidthIsTheLargerOfItsArguments() throws Exception {
        Translation translation =
                new OrafitEngine()
                        .translate(
                                "SELECT NVL(code2, 'ABCDE') AS value FROM bs_char_width "
                                        + "UNION SELECT code2 FROM bs_char_width",
                                PHYSICAL);

        ResultMetadataPlan.Column column = translation.metadata().column(1);
        assertEquals(ResultMetadataPlan.Kind.VARCHAR2, column.kind());
        assertEquals(5, column.precision());
    }
}
