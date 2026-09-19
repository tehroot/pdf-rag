package org.hayden;

import org.hayden.ingest.TextSanitizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextSanitizerTest {

    @Test
    void keepsCleanTextAndValidPairs() {
        String pair = "a😀b";
        assertThat(TextSanitizer.stripUnpairedSurrogates(pair)).isSameAs(pair);
        assertThat(TextSanitizer.stripUnpairedSurrogates("plain")).isSameAs("plain");
        assertThat(TextSanitizer.stripUnpairedSurrogates(null)).isNull();
    }

    @Test
    void replacesLoneSurrogates() {
        assertThat(TextSanitizer.stripUnpairedSurrogates("x\uD800y")).isEqualTo("x�y");
        assertThat(TextSanitizer.stripUnpairedSurrogates("x\uDC00")).isEqualTo("x�");
        assertThat(TextSanitizer.stripUnpairedSurrogates("\uD800😀")).isEqualTo("�😀");
        // a pair split at a chunk boundary leaves a leading half in one chunk
        // and a trailing half in the next; both must become U+FFFD
        assertThat(TextSanitizer.stripUnpairedSurrogates("end\uD83D")).isEqualTo("end�");
        assertThat(TextSanitizer.stripUnpairedSurrogates("\uDE00start")).isEqualTo("�start");
    }
}
