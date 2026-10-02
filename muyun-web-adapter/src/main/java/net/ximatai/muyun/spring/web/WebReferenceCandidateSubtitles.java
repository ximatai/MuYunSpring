package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceSelectionProjection;
import java.util.List;
import java.util.Map;

/** Adapter projection shared by static and dynamic source-owned reference responses. */
public final class WebReferenceCandidateSubtitles {
    private WebReferenceCandidateSubtitles() { }
    public static WebReferenceResolveResponse apply(WebReferenceResolveResponse response, ReferenceTarget target,
                                                    ReferenceSelectionProjection projection, ReferenceTargetResolver resolver) {
        if (projection == null) return response;
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        response.options().forEach(item -> ids.add(item.id()));
        response.results().forEach(result -> {
            if (result.item() != null) ids.add(result.item().id());
            result.candidates().forEach(item -> ids.add(item.id()));
        });
        collectTreeIds(response.tree(), ids);
        Map<String, String> subtitles = net.ximatai.muyun.spring.ability.reference.ReferenceCandidateSubtitleReader.read(
                target, List.copyOf(ids), projection, resolver);
        return new WebReferenceResolveResponse(response.status(), response.mode(),
                response.options().stream().map(item -> item.withSubtitle(subtitles.get(item.id()))).toList(),
                response.results().stream().map(result -> new WebReferenceResolveResult(result.input(), result.status(),
                        result.matchedBy(), result.item() == null ? null : result.item().withSubtitle(subtitles.get(result.item().id())),
                        result.candidates().stream().map(item -> item.withSubtitle(subtitles.get(item.id()))).toList())).toList(),
                response.offset(), response.limit(), response.total(), subtitleTree(response.tree(), subtitles));
    }

    private static void collectTreeIds(List<WebTreeNode<WebReferenceResolveItem>> tree, java.util.Set<String> ids) {
        for (var node : tree) {
            ids.add(node.record().id());
            collectTreeIds(node.children(), ids);
        }
    }

    private static List<WebTreeNode<WebReferenceResolveItem>> subtitleTree(
            List<WebTreeNode<WebReferenceResolveItem>> tree, Map<String, String> subtitles) {
        return tree.stream().map(node -> new WebTreeNode<>(node.record().withSubtitle(subtitles.get(node.record().id())),
                subtitleTree(node.children(), subtitles))).toList();
    }
}
