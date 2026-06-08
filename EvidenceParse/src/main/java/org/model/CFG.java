package org.model;

import org.model.Bundle.CommentedList;

public class CFG {
    // public Site site = new Site();

    // public static class Site {
    // public String unit = "";
    // public String loc = "";
    // }

    // public List<String> pred_bbs = new ArrayList<>();
    // public List<String> succ_bbs = new ArrayList<>();

    public CommentedList<String> dom = new CommentedList<String>(
            "Summary of dominator chain reaching the mutation point (block/statement), for reachability analysis");
    public CommentedList<String> path_predicates = new CommentedList<String>(
            "Conditional predicates (e.g., if conditions) that must be satisfied to reach mutation point along this path; used to characterize path constraints");
    public CommentedList<String> control_deps_out = new CommentedList<String>(
            "Use post-dominator analysis to determine which if-conditions control the reachability of the mutation point");

    // public java.util.Map<String, Object> loop = new LinkedHashMap<>();
    // public String reachability = "UNKNOWN";
}
