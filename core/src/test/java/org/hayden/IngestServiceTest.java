package org.hayden;

import org.hayden.backend.Backend;
import org.hayden.backend.KnowledgeBaseSummary;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestRequest.SourceType;
import org.hayden.ingest.IngestResult;
import org.hayden.ingest.IngestService;
import org.hayden.ingest.SearchHit;
import org.hayden.ingest.SearchRequest;
import org.hayden.ingest.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Routing-only tests for the dispatcher. Per-backend pipelines have their own tests
 * ({@link OpenWebUiBackendTest}, {@link QdrantBackendTest}).
 */
class IngestServiceTest {

    private IngestService service;
    private FakeBackend qdrant;
    private FakeBackend openwebui;

    @BeforeEach
    void setUp() throws Exception {
        qdrant = new FakeBackend("qdrant");
        openwebui = new FakeBackend("openwebui");
        service = new IngestService();
        setField(service, "backends", new TestInstance<>(List.of(qdrant, openwebui)));
        setField(service, "defaultBackend", "qdrant");
    }

    @Test
    void ingest_routesByExplicitBackendArg() {
        service.ingest(req("openwebui"));
        assertThat(openwebui.ingestCalls).isEqualTo(1);
        assertThat(qdrant.ingestCalls).isEqualTo(0);
    }

    @Test
    void ingest_routesToDefaultWhenNull() {
        service.ingest(req(null));
        assertThat(qdrant.ingestCalls).isEqualTo(1);
        assertThat(openwebui.ingestCalls).isEqualTo(0);
    }

    @Test
    void ingest_routesToDefaultWhenBlank() {
        service.ingest(req("   "));
        assertThat(qdrant.ingestCalls).isEqualTo(1);
    }

    @Test
    void ingest_rejectsUnknownBackend() {
        assertThatThrownBy(() -> service.ingest(req("redis")))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("Unknown backend 'redis'");
    }

    @Test
    void search_routesByExplicitBackendArg() {
        service.search(new SearchRequest("openwebui", "docs", "q", 5, null));
        assertThat(openwebui.searchCalls).isEqualTo(1);
    }

    @Test
    void listKnowledgeBases_mergesAcrossBackends_whenAllRequested() {
        qdrant.kbs.add(new KnowledgeBaseSummary("qdrant", "docs", "docs", 10L, 384));
        openwebui.kbs.add(new KnowledgeBaseSummary("openwebui", "kb-1", "Reports", null, null));

        List<KnowledgeBaseSummary> all = service.listKnowledgeBases("all");

        assertThat(all).extracting(KnowledgeBaseSummary::backend)
                .containsExactlyInAnyOrder("qdrant", "openwebui");
    }

    @Test
    void listKnowledgeBases_mergesAcrossBackends_whenNoneSpecified() {
        qdrant.kbs.add(new KnowledgeBaseSummary("qdrant", "docs", "docs", 0L, 384));
        openwebui.kbs.add(new KnowledgeBaseSummary("openwebui", "kb-2", "Notes", null, null));

        assertThat(service.listKnowledgeBases(null)).hasSize(2);
    }

    @Test
    void listKnowledgeBases_scopesToOneBackend() {
        qdrant.kbs.add(new KnowledgeBaseSummary("qdrant", "docs", "docs", 0L, 384));
        openwebui.kbs.add(new KnowledgeBaseSummary("openwebui", "kb-2", "Notes", null, null));

        assertThat(service.listKnowledgeBases("openwebui"))
                .singleElement()
                .extracting(KnowledgeBaseSummary::name)
                .isEqualTo("Notes");
    }

    private static IngestRequest req(String backend) {
        return new IngestRequest(SourceType.URL, "https://example.com/x.txt", "x.txt",
                "docs", null, 30L, backend, null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static final class FakeBackend implements Backend {
        final String name;
        int ingestCalls;
        int searchCalls;
        final List<KnowledgeBaseSummary> kbs = new ArrayList<>();

        FakeBackend(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }

        @Override
        public IngestResult ingest(IngestRequest req) {
            ingestCalls++;
            return new IngestResult(name, req.kbName(), req.kbName(),
                    "doc-1", "completed", 1, true, "ok");
        }

        @Override
        public SearchResponse search(SearchRequest req) {
            searchCalls++;
            return new SearchResponse(name, req.kbName(), List.<SearchHit>of());
        }

        @Override
        public List<KnowledgeBaseSummary> listKnowledgeBases() {
            return kbs;
        }
    }

    /**
     * Minimal stand-in for jakarta.enterprise.inject.Instance — IngestService only
     * needs iteration, which a List delegate covers.
     */
    private static final class TestInstance<T> implements jakarta.enterprise.inject.Instance<T> {
        private final List<T> backing;

        TestInstance(List<T> backing) {
            this.backing = backing;
        }

        @Override public Iterator<T> iterator() { return backing.iterator(); }
        @Override public jakarta.enterprise.inject.Instance<T> select(java.lang.annotation.Annotation... a) { throw new UnsupportedOperationException(); }
        @Override public <U extends T> jakarta.enterprise.inject.Instance<U> select(Class<U> c, java.lang.annotation.Annotation... a) { throw new UnsupportedOperationException(); }
        @Override public <U extends T> jakarta.enterprise.inject.Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> t, java.lang.annotation.Annotation... a) { throw new UnsupportedOperationException(); }
        @Override public boolean isUnsatisfied() { return backing.isEmpty(); }
        @Override public boolean isAmbiguous() { return false; }
        @Override public void destroy(T instance) { }
        @Override public jakarta.enterprise.inject.Instance.Handle<T> getHandle() { throw new UnsupportedOperationException(); }
        @Override public Iterable<? extends jakarta.enterprise.inject.Instance.Handle<T>> handles() { return Collections.emptyList(); }
        @Override public T get() { return backing.isEmpty() ? null : backing.get(0); }
    }
}
