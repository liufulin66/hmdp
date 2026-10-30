package com.hmdp.service.impl;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** 有数量上限的一批用户变更；liked 是实时总数，不代表已处理全部关系。 */
public record BlogLikeSnapshot(long blogId, int liked, long version, List<BlogLikeChange> changes) {
    public BlogLikeSnapshot {
        if (blogId <= 0 || liked < 0 || version < 0) {
            throw new IllegalArgumentException("Invalid blog like snapshot");
        }
        changes = List.copyOf(changes);
        if (changes.stream().anyMatch(change -> change.version() > version)) {
            throw new IllegalArgumentException("User version exceeds snapshot version");
        }
        Set<Long> users = new HashSet<>();
        if (changes.stream().anyMatch(change -> !users.add(change.userId()))) {
            throw new IllegalArgumentException("Duplicate user in snapshot");
        }
    }
}
