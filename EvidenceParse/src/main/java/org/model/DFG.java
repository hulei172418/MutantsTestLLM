package org.model;

import java.util.*;

import org.model.Bundle.CommentedList;

public class DFG {
        public CommentedList<KV> defs_point = new CommentedList<>(
                        "Set of variable definitions related to mutation within this path slice (including the mutation point itself)");
        public CommentedList<SinkUse> uses_toward_output = new CommentedList<>(
                        "Explicit evidence chain showing whether the data impact at the mutation point propagates to observable sinks — core to P=Propagation in the RIP framework. Without it, you can detect Infection but not whether it reaches return/exception/external state (Observability)");
        public CommentedList<Kill> kill_set = new CommentedList<>(
                        "Set of overwriting redefinitions of the same variable before reaching sink, used to judge whether propagation is \"killed\"");
        public CommentedList<Heap> heap_access = new CommentedList<>(
                        "Summary of heap/array/field accesses (e.g., arr[i] writes, obj.f writes), used to capture externally visible state changes");
        public CommentedList<String> may_throw = new CommentedList<>(
                        "Summary of potential exception throw sites or call sites (e.g., throw/invoke), to assist propagation analysis for exception sinks");
        public CommentedList<List<String>> alias_groups = new CommentedList<>(
                        "Alias sets (different expressions referring to the same object/memory), used to determine indirect impact and overwrite relations");

        public static class KV {
                public String var = "";
                public String unit = "";
        }

        public static class SinkUse {
                public String var = "";
                public String unit = "";
                public String sink = "";
        }

        public static class Kill {
                public String var = "";
                public String unit = "";
                public boolean postdominated_by_sink = false;
        }

        public static class Heap {
                public String base = "";
                public String index = "";
                public String kind = "";
        }
}