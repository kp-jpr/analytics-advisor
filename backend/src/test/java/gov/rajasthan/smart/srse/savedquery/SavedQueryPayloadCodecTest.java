package gov.rajasthan.smart.srse.savedquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.analysis.MatchCriterion;
import gov.rajasthan.smart.srse.analysis.RecordMatchRequest;
import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedQueryPayloadCodecTest {

    private final SavedQueryPayloadCodec codec = new SavedQueryPayloadCodec(new ObjectMapper());

    @Test
    void roundTripPreservesTypedRuleOperands() {
        Ast.PredicateNode fuzzy = new Ast.PredicateNode(
                new QualifiedColumn("iceberg", "srse", "beneficiary", "father_name"),
                Ast.Operator.FUZZY_MATCH,
                List.of("Geeta Kumari", 85));
        Ast.PredicateSpec rules = new Ast.PredicateSpec(fuzzy);
        MatchCriterion c = new MatchCriterion("iceberg", "srse", "beneficiary", "id", null);
        RecordMatchRequest request = new RecordMatchRequest(
                List.of(c), List.of(c), List.of(), List.of(), List.of(), false, null, null,
                List.of(), false, rules, null, false, List.of(), List.of(), null, false, List.of());

        String text = codec.encode(request);
        RecordMatchRequest restored = codec.decode(text);

        assertTrue(restored.sourceRules().root() instanceof Ast.PredicateNode);
        Ast.PredicateNode node = (Ast.PredicateNode) restored.sourceRules().root();
        assertEquals(List.of("Geeta Kumari", 85), node.value());
    }

    @Test
    void unknownVersionFailsLoudly() {
        String payload = """
                {"version":0,"kind":"record_match","request":{}}
                """;
        SavedQueryPayloadException ex = assertThrows(
                SavedQueryPayloadException.class, () -> codec.decode(payload));
        assertTrue(ex.getMessage().contains("version 0"));
    }
}
