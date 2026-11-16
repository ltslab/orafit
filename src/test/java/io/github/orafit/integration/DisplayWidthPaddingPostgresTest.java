package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * LPAD/RPAD count display columns (East Asian wide = 2). Expected values come from Oracle 18c XE.
 */
final class DisplayWidthPaddingPostgresTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            textBlock =
                    """
                    LPAD('あ', 5, '*')       | ***あ
                    LPAD('あいう', 5, '*')   | ' あい'
                    LPAD('あいう', 4, '*')   | あい
                    LPAD('あいう', 2, '*')   | あ
                    LPAD('あ', 1, '*')       | ' '
                    LPAD('あ', 3, 'あ')      | ' あ'
                    RPAD('あ', 5, '*')       | あ***
                    RPAD('あいう', 5, '*')   | 'あい '
                    RPAD('a', 6, 'あ')       | 'aああ '
                    LPAD('a', 5, 'あ')       | ああa
                    LPAD('a', 6, 'あ')       | ' ああa'
                    LPAD('a', 4, 'あい')     | ' あa'
                    LPAD('ab', 3, 'あい')    | ' ab'
                    LPAD('Ａ', 4, '*')       | **Ａ
                    LPAD('ｱ', 4, '*')        | ***ｱ
                    LPAD('한', 4, '*')       | **한
                    LPAD('漢', 4, '*')       | **漢
                    LPAD('ー', 4, '*')       | **ー
                    LPAD('、', 4, '*')       | **、
                    LPAD('１', 4, '*')       | **１
                    LPAD('＠', 4, '*')       | **＠
                    LPAD('￥', 4, '*')       | **￥
                    LPAD('㈱', 4, '*')       | **㈱
                    LPAD('～', 4, '*')       | **～
                    LPAD('𠮷', 4, '*')       | **𠮷
                    LPAD('🙂', 4, '*')       | **🙂
                    LPAD('é', 4, '*')        | ***é
                    LPAD('Ж', 4, '*')        | ***Ж
                    LPAD('€', 4, '*')        | ***€
                    LPAD('★', 4, '*')        | ***★
                    LPAD('ｶﾞ', 4, '*')      | **ｶﾞ
                    LPAD('ab', 5, '*')       | ***ab
                    RPAD('ab', 5, 'xy')      | abxyx
                    """)
    void paddingCountsDisplayColumns(String expression, String expected) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT " + expression + " FROM dual")) {
            result.next();
            assertEquals(expected, result.getString(1));
        }
    }
}
