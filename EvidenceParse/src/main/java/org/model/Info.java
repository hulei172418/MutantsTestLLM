package org.model;

import java.util.*;

public class Info {
    public List<InfoItem> Info = new ArrayList<>();

    public static class InfoItem {
        public String Path;
        public CFG CFG;
        public DFG DFG;

        public InfoItem() {
            this.Path = "";
            this.CFG = new CFG();
            this.DFG = new DFG();
        }
    }
}
