package org.hayden;

import org.hayden.backend.qdrant.fusion.FusionEngine;
import org.hayden.ingest.IngestException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FusionEngine#resolveMode}. The full end-to-end
 * orchestration is exercised by {@link QdrantBackendTest}'s search path.
 */
class FusionEngineTest {

    @Test
    void autoMode_visualAvailable_picksFusion() throws Exception {
        FusionEngine e = newEngine("auto");
        List<String> warnings = new ArrayList<>();
        assertThat(e.resolveMode("auto", true, warnings)).isEqualTo("fusion");
        assertThat(warnings).isEmpty();
    }

    @Test
    void autoMode_visualUnavailable_picksTextOnly() throws Exception {
        FusionEngine e = newEngine("auto");
        List<String> warnings = new ArrayList<>();
        assertThat(e.resolveMode("auto", false, warnings)).isEqualTo("text_only");
        assertThat(warnings).isEmpty();
    }

    @Test
    void explicitFusion_visualUnavailable_fallsBackWithWarning() throws Exception {
        FusionEngine e = newEngine("auto");
        List<String> warnings = new ArrayList<>();
        String mode = e.resolveMode("fusion", false, warnings);
        assertThat(mode).isEqualTo("text_only_fallback");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("falling back");
    }

    @Test
    void explicitFusion_visualAvailable_picksFusion() throws Exception {
        FusionEngine e = newEngine("auto");
        List<String> warnings = new ArrayList<>();
        assertThat(e.resolveMode("fusion", true, warnings)).isEqualTo("fusion");
    }

    @Test
    void textOnly_alwaysReturnsTextOnly() throws Exception {
        FusionEngine e = newEngine("auto");
        List<String> warnings = new ArrayList<>();
        assertThat(e.resolveMode("text_only", true, warnings)).isEqualTo("text_only");
        assertThat(e.resolveMode("text_only", false, warnings)).isEqualTo("text_only");
        assertThat(warnings).isEmpty();
    }

    @Test
    void colpaliOnly_visualAvailable_works() throws Exception {
        FusionEngine e = newEngine("auto");
        List<String> warnings = new ArrayList<>();
        assertThat(e.resolveMode("colpali_only", true, warnings)).isEqualTo("colpali_only");
    }

    @Test
    void colpaliOnly_visualUnavailable_throws() throws Exception {
        FusionEngine e = newEngine("auto");
        assertThatThrownBy(() -> e.resolveMode("colpali_only", false, new ArrayList<>()))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("requires a visual index");
    }

    @Test
    void unknownMode_throws() throws Exception {
        FusionEngine e = newEngine("auto");
        assertThatThrownBy(() -> e.resolveMode("hybrid", true, new ArrayList<>()))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("Unknown retrieval_mode");
    }

    @Test
    void caseInsensitive_modeMatching() throws Exception {
        FusionEngine e = newEngine("auto");
        List<String> warnings = new ArrayList<>();
        assertThat(e.resolveMode("AUTO", true, warnings)).isEqualTo("fusion");
        assertThat(e.resolveMode("Fusion", true, warnings)).isEqualTo("fusion");
        assertThat(e.resolveMode("Text_Only", true, warnings)).isEqualTo("text_only");
    }

    private static FusionEngine newEngine(String defaultMode) throws Exception {
        FusionEngine e = new FusionEngine();
        // For resolveMode we only need the defaultMode field set;
        // dependency injects aren't touched by resolveMode itself.
        Field f = FusionEngine.class.getDeclaredField("defaultMode");
        f.setAccessible(true);
        f.set(e, defaultMode);
        return e;
    }
}
