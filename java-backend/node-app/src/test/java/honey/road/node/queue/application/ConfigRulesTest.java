package honey.road.node.queue.application;

import honey.road.node.queue.domain.DomainException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Config merge and local override bounds (sect 4.8): pure logic, no DB. */
class ConfigRulesTest {
    private static final JsonMapper M = JsonMapper.builder().build();

    private static JsonNode j(String json) {
        return M.readTree(json);
    }

    private static final JsonNode BOUNDS = j("""
            {"slack_min":{"min":0,"max":15},"target_wait_min":{"min":5,"max":60},"call_timeout_sec":{"min":30,"max":300}}""");

    @Test
    void mergeIsDeepAndDoesNotMutateInputs() {
        var base = j("{\"a\":1,\"target_wait_min\":{\"qr\":{\"default\":20,\"pensioner\":15},\"walkin\":{\"default\":30}}}");
        var patch = j("{\"a\":2,\"target_wait_min\":{\"qr\":{\"default\":25}}}");

        var merged = ConfigRules.merge(base, patch);

        assertEquals(2, merged.get("a").asInt());
        assertEquals(25, merged.get("target_wait_min").get("qr").get("default").asInt());
        assertEquals(15, merged.get("target_wait_min").get("qr").get("pensioner").asInt());   // sibling keys kept
        assertEquals(30, merged.get("target_wait_min").get("walkin").get("default").asInt());
        assertEquals(1, base.get("a").asInt());                                        // input unchanged
        assertEquals(20, base.get("target_wait_min").get("qr").get("default").asInt());
    }

    @Test
    void mergeWithNullOrNonObjectPatchKeepsBase() {
        var base = j("{\"a\":1}");
        assertEquals(base, ConfigRules.merge(base, null));
        assertEquals(base, ConfigRules.merge(base, j("[1,2]")));
    }

    @Test
    void valueInsideBoundsIsAccepted() {
        ConfigRules.checkWithinBounds(j("{\"slack_min\":8,\"call_timeout_sec\":60}"), BOUNDS);
        ConfigRules.checkWithinBounds(j("{}"), BOUNDS);
    }

    @Test
    void nestedValuesAreCheckedLeafByLeaf() {
        ConfigRules.checkWithinBounds(j("{\"target_wait_min\":{\"qr\":{\"default\":30},\"walkin\":{\"default\":45}}}"), BOUNDS);
        var e = assertThrows(DomainException.class,
                () -> ConfigRules.checkWithinBounds(j("{\"target_wait_min\":{\"qr\":{\"default\":30,\"pensioner\":2}}}"), BOUNDS));
        assertEquals("CONFIG_OUT_OF_BOUNDS", e.code());
    }

    @Test
    void valueOutsideBoundsIsRejected() {
        assertEquals("CONFIG_OUT_OF_BOUNDS", assertThrows(DomainException.class,
                () -> ConfigRules.checkWithinBounds(j("{\"slack_min\":16}"), BOUNDS)).code());
        assertEquals("CONFIG_OUT_OF_BOUNDS", assertThrows(DomainException.class,
                () -> ConfigRules.checkWithinBounds(j("{\"call_timeout_sec\":10}"), BOUNDS)).code());
    }

    @Test
    void parameterWithoutBoundsCannotBeOverridden() {
        assertEquals("CONFIG_OUT_OF_BOUNDS", assertThrows(DomainException.class,
                () -> ConfigRules.checkWithinBounds(j("{\"max_recalls\":5}"), BOUNDS)).code());
        assertEquals("CONFIG_OUT_OF_BOUNDS", assertThrows(DomainException.class,
                () -> ConfigRules.checkWithinBounds(j("{\"slack_min\":5}"), j("{}"))).code());
    }

    @Test
    void nonNumericValueIsRejected() {
        assertEquals("CONFIG_OUT_OF_BOUNDS", assertThrows(DomainException.class,
                () -> ConfigRules.checkWithinBounds(j("{\"slack_min\":\"many\"}"), BOUNDS)).code());
    }

    @Test
    void pruneDropsOnlyOverridesThatLeftTheNewBounds() {
        var overrides = j("{\"slack_min\":12,\"call_timeout_sec\":60}");
        var tighter = j("{\"slack_min\":{\"min\":0,\"max\":8},\"call_timeout_sec\":{\"min\":30,\"max\":300}}");

        var kept = ConfigRules.pruneOutOfBounds(overrides, tighter);

        assertFalse(kept.has("slack_min"));
        assertEquals(60, kept.get("call_timeout_sec").asInt());
        assertEquals(12, overrides.get("slack_min").asInt());                          // input unchanged
    }
}
