package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.metadata.ColumnMaskPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnMaskSqlTest {

    @Test
    void noneLeavesTheReferenceAlone() {
        assertEquals("src.aadhaar", ColumnMaskSql.maskExpression("src.aadhaar", ColumnMaskPolicy.NONE, 4));
    }

    @Test
    void fullReplacesClearValues() {
        String sql = ColumnMaskSql.maskExpression("src.aadhaar", ColumnMaskPolicy.FULL, 4);
        assertTrue(sql.contains("'****'"), sql);
        assertTrue(sql.contains("src.aadhaar IS NULL"), sql);
        assertFalse(sql.equals("src.aadhaar"), sql);
    }

    @Test
    void partialKeepsTrailingCharacters() {
        String sql = ColumnMaskSql.maskExpression("src.aadhaar", ColumnMaskPolicy.PARTIAL, 4);
        assertTrue(sql.contains("SUBSTR"), sql);
        assertTrue(sql.contains(" - 4 + 1"), sql);
    }

    @Test
    void hashUsesStableDigest() {
        String sql = ColumnMaskSql.maskExpression("src.aadhaar", ColumnMaskPolicy.HASH, 4);
        assertTrue(sql.contains("sha256"), sql);
        assertTrue(sql.contains("to_hex"), sql);
    }
}
