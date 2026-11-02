package com.g1do.anvil.extract;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.g1do.anvil.eval.EvalSet;
import com.g1do.anvil.eval.Fact;
import com.g1do.anvil.eval.RulesBaseline;
import com.g1do.anvil.extract.Extractor.Outcome;
import com.g1do.anvil.extract.Extractor.Prepared;
import com.g1do.anvil.extract.Extractor.Reason;
import com.g1do.anvil.extract.Extractor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The extractor's side of the cache and the fallback (#38, record 0023), with
 * no database: the cache key's content, re-checking a cached entry before it
 * is reused, and the rules fallback making no model call. The service's side
 * is {@code ExtractionCostTest}.
 */
class ExtractionFallbackTest {

    private static final Path SET = Path.of("eval/sets/pilot-v1");

    /** A model that fails the test if it is ever asked. */
    private static ScriptedModel never() {
        return new ScriptedModel(d -> {
            throw new AssertionError("the model was called");
        });
    }

    // Acceptance: the cache key's content is the text the model would see.

    @Test
    void theContentKeyIsTheBoundedTextTheModelWouldSee() {
        Extractor x = new Extractor(never());
        String head = "Acme Ltd signed on 3 March 2026. " + "x".repeat(500);
        Prepared a = x.prepare(head + " one tail", "text/plain");
        Prepared b = x.prepare("   \n" + head + " another tail", "text/plain; charset=utf-8");

        // Same first 500 characters after leading whitespace: the same key, since the model sees the same text.
        assertThat(a.contentSha256()).matches("[0-9a-f]{64}").isEqualTo(b.contentSha256());
        assertThat(a.contentSha256()).isEqualTo(Extractor.sha256(a.bounded().text()));
        assertThat(x.prepare("Acme Ltd signed on 4 March 2026.", "text/plain").contentSha256())
                .isNotEqualTo(x.prepare("Acme Ltd signed on 3 March 2026.", "text/plain").contentSha256());
    }

    @Test
    void screenedDocumentsHaveNoKeyAndCantBeAskedOrFallenBackOn() {
        Extractor x = new Extractor(never());
        Prepared p = x.prepare("hidden‮text", "text/plain");
        assertThat(p.ready()).isFalse();
        assertThat(p.contentSha256()).isNull();
        assertThat(p.screened().quarantined()).first().extracting(q -> q.reason()).isEqualTo(Reason.NOT_TEXT);
        assertThatThrownBy(() -> x.ask(p)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> x.rules(p)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> x.reuse(p, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    // Acceptance: a poisoned cache entry is never served.

    @Test
    void aCachedEntryIsReusedOnlyWhenEveryFactPassesTheChecksAgain() {
        Extractor x = new Extractor(never());
        Prepared p = x.prepare("Acme Ltd paid USD 500 on 3 March 2026.", "text/plain");
        Fact good = new Fact("party", "Acme Ltd", "Acme Ltd paid");
        Fact date = new Fact("date", "3 March 2026", "on 3 March 2026");

        Optional<Outcome> hit = x.reuse(p, List.of(good, date));
        assertThat(hit).isPresent();
        assertThat(hit.get().status()).isEqualTo(Status.ACCEPTED);
        assertThat(hit.get().modelCalled()).isFalse();
        assertThat(hit.get().accepted()).containsExactly(good, date);

        // An entry holding any fact the checks reject now is poisoned: nothing from it is reused.
        assertThat(x.reuse(p, List.of(good, new Fact("party", "Evil Corp", "Evil Corp paid")))).isEmpty();
        assertThat(x.reuse(p, List.of(good, new Fact("amount", "USD 5", "USD 500")))).isEmpty();
        assertThat(x.reuse(p, List.of(new Fact("secret", "Acme Ltd", "Acme Ltd paid")))).isEmpty();
        assertThat(x.reuse(p, List.of(new Fact("party", "Acme Ltd", "")))).isEmpty();
        assertThat(x.reuse(p, java.util.Collections.nCopies(Extractor.MAX_FACTS + 1, good))).isEmpty();
    }

    // Acceptance: the fallback makes zero model calls.

    @Test
    void theRulesFallbackMakesNoModelCallAndKeepsOnlyCheckedFacts() {
        ScriptedModel model = never();
        Extractor x = new Extractor(model);
        Outcome o = x.rules(x.prepare("Ms Ada Lund of Acme Ltd paid EUR 1,200 on 3 March 2026.", "text/plain"));

        assertThat(model.calls()).isEmpty();
        assertThat(o.modelCalled()).isFalse();
        assertThat(o.usage()).isNull();
        assertThat(o.status()).isEqualTo(Status.ACCEPTED);
        assertThat(o.accepted()).extracting(Fact::field, Fact::value).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("party", "Acme Ltd"),
                org.assertj.core.groups.Tuple.tuple("person", "Ada Lund"),
                org.assertj.core.groups.Tuple.tuple("amount", "EUR 1,200"),
                org.assertj.core.groups.Tuple.tuple("date", "3 March 2026"));
    }

    @Test
    void theRulesSeeOnlyTheBoundedText() {
        Extractor x = new Extractor(never());
        Outcome o = x.rules(x.prepare("x".repeat(600) + " Acme Ltd paid EUR 9 on 3 March 2026.", "text/plain"));
        assertThat(o.truncated()).isTrue();
        assertThat(o.accepted()).isEmpty();
    }

    /**
     * The fallback is exactly the frozen baseline on every document of the
     * set, so its quality there is the baseline's published score (record
     * 0021): F1 0.758 [0.702, 0.808], 3 of 12 attacks succeeding.
     */
    @Test
    void overTheFrozenSetTheFallbackIsExactlyTheBaseline() throws Exception {
        EvalSet set = EvalSet.load(SET);
        Extractor x = new Extractor(never());
        Map<String, List<Fact>> fallback = new LinkedHashMap<>();
        set.quality().forEach(d -> fallback.put(d.id(), x.rules(x.prepare(d.text(), "text/plain")).accepted()));
        set.injection().forEach(c -> fallback.put(c.id(), x.rules(x.prepare(c.text(), "text/plain")).accepted()));

        assertThat(fallback.keySet()).containsExactlyInAnyOrderElementsOf(set.baseline().keySet());
        set.baseline().forEach((id, p) -> assertThat(fallback.get(id)).as(id).isEqualTo(p.facts()));
    }

    @Test
    void theRulesHaveTheirOwnKeySoAFallbackNeverTakesTheModelsPlace() {
        assertThat(RulesBaseline.SHA256).matches("[0-9a-f]{64}").isNotEqualTo(Prompt.SHA256);
        assertThat(RulesBaseline.ID).isNotEqualTo(ExtractHarness.DEFAULT_MODEL);
    }
}
