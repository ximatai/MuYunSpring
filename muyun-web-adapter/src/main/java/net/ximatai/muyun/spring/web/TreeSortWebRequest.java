package net.ximatai.muyun.spring.web;

public record TreeSortWebRequest(String previousId, String nextId, String parentId,
                                 TreeSortScopeRequest scope) {
    public TreeSortWebRequest(String previousId, String nextId, String parentId) {
        this(previousId, nextId, parentId, null);
    }
}
