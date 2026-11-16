-- VARCHAR2 and Oracle string semantics.

CREATE FUNCTION orafit._number_to_varchar2(value numeric, nls_numeric_characters text)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    result text;
    decimal_character text;
    magnitude numeric;
    rounded numeric;
    sign_length integer;
    int_digits integer;
    exponent integer;
    digits integer;
BEGIN
    IF value IS NULL THEN RETURN NULL; END IF;
    IF nls_numeric_characters IS NULL
       OR length(nls_numeric_characters) <> 2
       OR substr(nls_numeric_characters, 1, 1) = substr(nls_numeric_characters, 2, 1) THEN
        RAISE EXCEPTION 'invalid NLS_NUMERIC_CHARACTERS value: %', nls_numeric_characters
            USING ERRCODE = '22023';
    END IF;
    decimal_character := substr(nls_numeric_characters, 1, 1);
    -- Oracle NUMBER stores a value, not a declared column scale. Its default character
    -- conversion therefore omits insignificant fractional zeros, unlike PostgreSQL numeric.
    result := pg_catalog.trim_scale(value)::text;
    result := regexp_replace(result, '^(-?)0[.]', '\1.');
    IF length(result) > 40 THEN
        -- Oracle prints at most forty characters. A value of 1E-5 or more that still fits is
        -- rounded to forty characters; everything else switches to scientific notation.
        magnitude := abs(value);
        sign_length := CASE WHEN value < 0 THEN 1 ELSE 0 END;
        int_digits := CASE WHEN magnitude < 1 THEN 0 ELSE length(trunc(magnitude)::text) END;
        IF magnitude >= 0.00001 AND int_digits + sign_length <= 40 THEN
            result := pg_catalog.trim_scale(
                round(value, greatest(40 - sign_length - int_digits - 1, 0)))::text;
            result := regexp_replace(result, '^(-?)0[.]', '\1.');
            IF length(result) > 40 THEN
                RAISE EXCEPTION 'Orafit: TO_CHAR of this NUMBER has no PostgreSQL equivalent'
                    USING ERRCODE = '0A000';
            END IF;
        ELSE
            exponent := floor(log(10::numeric, magnitude))::integer;
            WHILE magnitude < ('1e' || exponent)::numeric LOOP exponent := exponent - 1; END LOOP;
            WHILE magnitude >= ('1e' || (exponent + 1))::numeric LOOP exponent := exponent + 1; END LOOP;
            digits := 34 - sign_length;
            rounded := round(magnitude, digits - exponent);
            IF rounded >= ('1e' || (exponent + 1))::numeric THEN exponent := exponent + 1; END IF;
            IF abs(exponent) >= 100 THEN
                RAISE EXCEPTION 'Orafit: TO_CHAR of this NUMBER has no PostgreSQL equivalent'
                    USING ERRCODE = '0A000';
            END IF;
            result := CASE WHEN value < 0 THEN '-' ELSE '' END
                || round(rounded * ('1e' || (-exponent))::numeric, digits)::text
                || 'E' || CASE WHEN exponent < 0 THEN '-' ELSE '+' END
                || lpad(abs(exponent)::text, 2, '0');
        END IF;
    END IF;
    IF decimal_character <> '.' THEN result := replace(result, '.', decimal_character); END IF;
    RETURN result;
END
$$;

CREATE FUNCTION orafit.to_varchar2(
    value text, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT NULLIF($1, '') $$;
CREATE FUNCTION orafit.to_varchar2(
    value numeric, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._number_to_varchar2($1, $5) $$;
CREATE FUNCTION orafit.to_varchar2(
    value smallint, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._number_to_varchar2($1::numeric, $5) $$;
CREATE FUNCTION orafit.to_varchar2(
    value integer, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._number_to_varchar2($1::numeric, $5) $$;
CREATE FUNCTION orafit.to_varchar2(
    value bigint, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._number_to_varchar2($1::numeric, $5) $$;
CREATE FUNCTION orafit.to_varchar2(
    value real, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._number_to_varchar2($1::numeric, $5) $$;
CREATE FUNCTION orafit.to_varchar2(
    value double precision, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._number_to_varchar2($1::numeric, $5) $$;
CREATE FUNCTION orafit.to_varchar2(
    value date, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1::timestamp, orafit._pg_datetime_format($2)) $$;
CREATE FUNCTION orafit.to_varchar2(
    value timestamp without time zone, date_format text, timestamp_format text,
    timestamp_tz_format text, nls_numeric_characters text)
RETURNS text LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1, orafit._pg_datetime_format($3)) $$;
CREATE FUNCTION orafit.to_varchar2(
    value timestamp with time zone, date_format text, timestamp_format text,
    timestamp_tz_format text, nls_numeric_characters text)
RETURNS text LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1, orafit._pg_datetime_format($4)) $$;

-- Explicit TO_CHAR formats use PostgreSQL type dispatch instead of JDBC schema lookups.
CREATE FUNCTION orafit.to_char_format(value date, format text)
RETURNS text
LANGUAGE sql STABLE STRICT PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1::timestamp, orafit._pg_datetime_format($2)) $$;

CREATE FUNCTION orafit.to_char_format(value timestamp without time zone, format text)
RETURNS text
LANGUAGE sql STABLE STRICT PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1, orafit._pg_datetime_format($2)) $$;

CREATE FUNCTION orafit.to_char_format(value timestamp with time zone, format text)
RETURNS text
LANGUAGE sql STABLE STRICT PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1, orafit._pg_datetime_format($2)) $$;

CREATE FUNCTION orafit.to_char_format(value anyelement, format text)
RETURNS text
LANGUAGE plpgsql STABLE STRICT PARALLEL SAFE
AS $$
BEGIN
    RAISE EXCEPTION 'ORA-01481: invalid number format model' USING ERRCODE = '72000';
END
$$;

CREATE FUNCTION orafit.concat_varchar2(left_value text, right_value text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(COALESCE($1, '') || COALESCE($2, ''), '') $$;

CREATE FUNCTION orafit.replace(value text, search text, replacement text DEFAULT NULL)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(pg_catalog.replace($1, COALESCE($2, ''), COALESCE($3, '')), '') $$;

CREATE FUNCTION orafit.translate(value text, source text, target text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(pg_catalog.translate(NULLIF($1, ''), NULLIF($2, ''), NULLIF($3, '')), '') $$;

CREATE FUNCTION orafit.substr(value text, start_position numeric)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    p numeric;
    source_length integer;
BEGIN
    IF value IS NULL OR value = '' OR start_position IS NULL THEN RETURN NULL; END IF;
    source_length := char_length(value);
    p := trunc(start_position);
    IF p = 0 THEN p := 1; END IF;
    IF p < 0 THEN p := source_length + p + 1; END IF;
    IF p < 1 OR p > source_length THEN RETURN NULL; END IF;
    RETURN NULLIF(pg_catalog.substr(value, p::integer), '');
END
$$;

CREATE FUNCTION orafit.substr(value text, start_position numeric, substring_length numeric)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    p numeric;
    requested numeric;
    source_length integer;
    actual_length integer;
BEGIN
    IF value IS NULL OR value = '' OR start_position IS NULL OR substring_length IS NULL THEN
        RETURN NULL;
    END IF;
    source_length := char_length(value);
    p := trunc(start_position);
    requested := trunc(substring_length);
    IF requested < 1 THEN RETURN NULL; END IF;
    IF p = 0 THEN p := 1; END IF;
    IF p < 0 THEN p := source_length + p + 1; END IF;
    IF p < 1 OR p > source_length THEN RETURN NULL; END IF;
    actual_length := LEAST(requested, source_length - p + 1)::integer;
    RETURN NULLIF(pg_catalog.substr(value, p::integer, actual_length), '');
END
$$;

CREATE FUNCTION orafit.instr(
    value text, needle text, start_position numeric, occurrence numeric)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    p numeric;
    wanted numeric;
    source_length integer;
    needle_length integer;
    cursor_pos integer;
    found_count integer := 0;
BEGIN
    IF value IS NULL OR value = '' OR needle IS NULL OR needle = ''
       OR start_position IS NULL OR occurrence IS NULL THEN RETURN NULL; END IF;
    p := trunc(start_position);
    wanted := trunc(occurrence);
    IF p = 0 THEN RETURN 0; END IF;
    IF wanted < 1 THEN
        RAISE EXCEPTION 'ORA-01428: occurrence must be positive' USING ERRCODE = '72000';
    END IF;
    IF wanted > 2147483647 THEN RETURN 0; END IF;
    source_length := char_length(value);
    needle_length := char_length(needle);
    IF p > 0 THEN
        IF p > source_length THEN RETURN 0; END IF;
        cursor_pos := p::integer;
        WHILE cursor_pos <= source_length - needle_length + 1 LOOP
            IF pg_catalog.substr(value, cursor_pos, needle_length) = needle THEN
                found_count := found_count + 1;
                IF found_count = wanted::integer THEN RETURN cursor_pos; END IF;
            END IF;
            cursor_pos := cursor_pos + 1;
        END LOOP;
        RETURN 0;
    END IF;
    p := source_length + p + 1;
    IF p < 1 THEN RETURN 0; END IF;
    cursor_pos := LEAST(p, source_length - needle_length + 1)::integer;
    WHILE cursor_pos >= 1 LOOP
        IF pg_catalog.substr(value, cursor_pos, needle_length) = needle THEN
            found_count := found_count + 1;
            IF found_count = wanted::integer THEN RETURN cursor_pos; END IF;
        END IF;
        cursor_pos := cursor_pos - 1;
    END LOOP;
    RETURN 0;
END
$$;

CREATE FUNCTION orafit.instr(value text, needle text)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.instr($1, $2, 1::numeric, 1::numeric) $$;
CREATE FUNCTION orafit.instr(value text, needle text, start_position numeric)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.instr($1, $2, $3, 1::numeric) $$;

-- Oracle NVL chooses the first argument family and converts the fallback only when needed.
CREATE FUNCTION orafit.nvl(value text, fallback numeric)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
SELECT CASE
    WHEN NULLIF($1, '') IS NOT NULL THEN NULLIF($1, '')
    ELSE orafit._number_to_varchar2($2, '.,')
END
$$;

CREATE FUNCTION orafit.nvl(value numeric, fallback text)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
BEGIN
    IF value IS NOT NULL THEN RETURN value; END IF;
    RETURN orafit.to_number(fallback);
END
$$;

CREATE FUNCTION orafit.nvl(value numeric, fallback integer)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT COALESCE($1, $2::numeric) $$;

CREATE FUNCTION orafit.nvl(value numeric, fallback bigint)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT COALESCE($1, $2::numeric) $$;

CREATE FUNCTION orafit.nvl(value numeric, fallback smallint)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT COALESCE($1, $2::numeric) $$;

-- LPAD and RPAD share Oracle length, empty-string, and range handling.
-- Oracle counts display columns for LPAD/RPAD: East Asian Wide and Fullwidth characters take two,
-- everything else (including combining marks and zero-width characters) takes one. A column that a
-- wide character cannot fill is padded with a space.
CREATE FUNCTION orafit._wide_class()
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
SELECT '[' || chr(x'1100'::int) || '-' || chr(x'115F'::int)
    || chr(x'2E80'::int) || '-' || chr(x'303E'::int)
    || chr(x'3041'::int) || '-' || chr(x'33FF'::int)
    || chr(x'3400'::int) || '-' || chr(x'4DBF'::int)
    || chr(x'4E00'::int) || '-' || chr(x'9FFF'::int)
    || chr(x'A000'::int) || '-' || chr(x'A4CF'::int)
    || chr(x'A960'::int) || '-' || chr(x'A97F'::int)
    || chr(x'AC00'::int) || '-' || chr(x'D7A3'::int)
    || chr(x'F900'::int) || '-' || chr(x'FAFF'::int)
    || chr(x'FE10'::int) || '-' || chr(x'FE19'::int)
    || chr(x'FE30'::int) || '-' || chr(x'FE6F'::int)
    || chr(x'FF00'::int) || '-' || chr(x'FF60'::int)
    || chr(x'FFE0'::int) || '-' || chr(x'FFE6'::int)
    || chr(x'1F300'::int) || '-' || chr(x'1F64F'::int)
    || chr(x'1F900'::int) || '-' || chr(x'1F9FF'::int)
    || chr(x'20000'::int) || '-' || chr(x'3FFFD'::int) || ']'
$$;

-- Symbol blocks whose width Oracle was not compared against; refuse rather than guess.
CREATE FUNCTION orafit._unverified_width_class()
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
SELECT '[' || chr(x'2300'::int) || '-' || chr(x'23FF'::int)
    || chr(x'2600'::int) || '-' || chr(x'2604'::int)
    || chr(x'2606'::int) || '-' || chr(x'27BF'::int)
    || chr(x'1F000'::int) || '-' || chr(x'1F2FF'::int)
    || chr(x'1F650'::int) || '-' || chr(x'1F8FF'::int)
    || chr(x'1FA00'::int) || '-' || chr(x'1FAFF'::int) || ']'
$$;

CREATE FUNCTION orafit._display_width(value text)
RETURNS integer LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
SELECT 2 * char_length($1) - char_length(regexp_replace($1, orafit._wide_class(), '', 'g'))
$$;

CREATE FUNCTION orafit._pad(
    value text, target_length numeric, fill text, left_side boolean)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    requested integer;
    padded text;
    wide text := orafit._wide_class();
    value_width integer;
    remaining integer;
    used integer := 0;
    cols integer := 0;
    character text;
    character_width integer;
    taken text := '';
    pad text := '';
    position integer := 1;
    fill_length integer := char_length(fill);
BEGIN
    IF value IS NULL OR value = '' OR target_length IS NULL OR fill IS NULL OR fill = '' THEN
        RETURN NULL;
    END IF;
    requested := pg_catalog.trunc(target_length)::integer;
    IF value ~ wide OR fill ~ wide THEN
        IF value ~ orafit._unverified_width_class() OR fill ~ orafit._unverified_width_class() THEN
            RAISE EXCEPTION 'Orafit: LPAD/RPAD width of this character block is not verified against Oracle'
                USING ERRCODE = '0A000';
        END IF;
        IF requested < 1 THEN RETURN NULL; END IF;
        value_width := orafit._display_width(value);
        IF value_width >= requested THEN
            FOREACH character IN ARRAY string_to_array(value, NULL) LOOP
                character_width := CASE WHEN character ~ wide THEN 2 ELSE 1 END;
                EXIT WHEN cols + character_width > requested;
                taken := taken || character;
                cols := cols + character_width;
            END LOOP;
            pad := repeat(' ', requested - cols);
            padded := CASE WHEN left_side THEN pad || taken ELSE taken || pad END;
        ELSE
            remaining := requested - value_width;
            LOOP
                character := substr(fill, position, 1);
                character_width := CASE WHEN character ~ wide THEN 2 ELSE 1 END;
                EXIT WHEN used + character_width > remaining;
                pad := pad || character;
                used := used + character_width;
                position := position % fill_length + 1;
            END LOOP;
            taken := repeat(' ', remaining - used);
            padded := CASE WHEN left_side THEN taken || pad || value ELSE value || pad || taken END;
        END IF;
        RETURN padded;
    END IF;
    IF left_side THEN
        padded := pg_catalog.lpad(value, requested, fill);
    ELSE
        padded := pg_catalog.rpad(value, requested, fill);
    END IF;
    RETURN NULLIF(padded, '');
EXCEPTION
    WHEN numeric_value_out_of_range THEN
        RAISE EXCEPTION 'Orafit: Oracle padding length is outside the supported integer range'
            USING ERRCODE = '0A000';
END
$$;

CREATE FUNCTION orafit.lpad(value text, target_length numeric, fill text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._pad($1, $2, $3, true) $$;

CREATE FUNCTION orafit.lpad(value text, target_length numeric)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._pad($1, $2, ' ', true) $$;

CREATE FUNCTION orafit.rpad(value text, target_length numeric, fill text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._pad($1, $2, $3, false) $$;

CREATE FUNCTION orafit.rpad(value text, target_length numeric)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._pad($1, $2, ' ', false) $$;

-- TRIM uses a single trim character; LTRIM/RTRIM use a character set.
CREATE FUNCTION orafit.trim(value text, direction text)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
BEGIN
    IF value IS NULL OR value = '' THEN RETURN NULL; END IF;
    RETURN NULLIF(
        CASE upper(direction)
            WHEN 'LEADING' THEN pg_catalog.ltrim(value, ' ')
            WHEN 'TRAILING' THEN pg_catalog.rtrim(value, ' ')
            WHEN 'BOTH' THEN pg_catalog.btrim(value, ' ')
            ELSE NULL
        END,
        '');
END
$$;

CREATE FUNCTION orafit.trim(value text, trim_character text, direction text)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
BEGIN
    IF value IS NULL OR value = '' OR trim_character IS NULL OR trim_character = '' THEN
        RETURN NULL;
    END IF;
    IF char_length(trim_character) <> 1 THEN
        RAISE EXCEPTION 'ORA-30001: trim set should have only one character'
            USING ERRCODE = '72000';
    END IF;
    RETURN NULLIF(
        CASE upper(direction)
            WHEN 'LEADING' THEN pg_catalog.ltrim(value, trim_character)
            WHEN 'TRAILING' THEN pg_catalog.rtrim(value, trim_character)
            WHEN 'BOTH' THEN pg_catalog.btrim(value, trim_character)
            ELSE NULL
        END,
        '');
END
$$;

CREATE FUNCTION orafit.ltrim(value text, trim_set text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(pg_catalog.ltrim(NULLIF($1, ''), NULLIF($2, '')), '') $$;

CREATE FUNCTION orafit.ltrim(value text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(pg_catalog.ltrim(NULLIF($1, ''), ' '), '') $$;

CREATE FUNCTION orafit.rtrim(value text, trim_set text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(pg_catalog.rtrim(NULLIF($1, ''), NULLIF($2, '')), '') $$;

CREATE FUNCTION orafit.rtrim(value text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(pg_catalog.rtrim(NULLIF($1, ''), ' '), '') $$;
-- Casting bpchar to text discards trailing blanks in PostgreSQL. Recover only
-- those padding bytes; all non-padding characters retain their original width.
CREATE FUNCTION orafit.char_text(value bpchar)
RETURNS text LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT rpad($1::text, char_length($1::text) + octet_length($1) - octet_length($1::text), ' ') $$;

CREATE FUNCTION orafit.length(value bpchar)
RETURNS numeric LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT char_length(orafit.char_text($1))::numeric $$;

CREATE FUNCTION orafit.cast_char(value text, width integer, byte_semantics boolean)
RETURNS bpchar LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE
AS $$
DECLARE padding integer;
BEGIN
    IF value = '' THEN RETURN NULL; END IF;
    padding := width - CASE WHEN byte_semantics THEN octet_length(value) ELSE char_length(value) END;
    IF padding < 0 THEN
        RAISE EXCEPTION 'Orafit: overlength CHAR cast is unsupported' USING ERRCODE = '0A000';
    END IF;
    RETURN (value || repeat(' ', padding))::bpchar;
END
$$;

CREATE FUNCTION orafit.length(value text)
RETURNS numeric LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT char_length(NULLIF($1, ''))::numeric $$;

CREATE FUNCTION orafit.length(value numeric)
RETURNS numeric LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT char_length(orafit._number_to_varchar2($1, '.,'))::numeric $$;
