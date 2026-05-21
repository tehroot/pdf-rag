package org.example.backend.openwebui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.example.backend.openwebui.dto.KnowledgeBase;

import java.util.Objects;

@ApplicationScoped
public class KnowledgeService {

    @Inject
    OpenWebUiClient client;

    public KnowledgeBase findOrCreate(String name, String description) {
        Objects.requireNonNull(name, "name");
        for (KnowledgeBase kb : client.listKnowledgeBases()) {
            if (name.equals(kb.name())) {
                return kb;
            }
        }
        return client.createKnowledgeBase(name, description);
    }
}
