package demo.m1;

public class test {
    public static boolean PoolEntry(int tagValue, int entries) {
        int numEntries = 0;
        while (entries > 0) {
            numEntries += entries;
            entries--;
        }
        System.out.println("numEntries = " + numEntries + ", tagValue = " + tagValue);
        return numEntries < tagValue;
    }

    public static boolean PoolEntryROR(int tagValue, int entries) {
        int numEntries = 0;
        while (entries >= 0) {
            numEntries += entries;
            entries--;
        }
        System.out.println("numEntries = " + numEntries + ", tagValue = " + tagValue);
        return numEntries < tagValue;
    }

    public static boolean PoolEntryAOIS(int tagValue, int entries) {
        int numEntries = 0;
        while (entries > 0) {
            numEntries += entries;
            entries--;
        }
        System.out.println("numEntries = " + numEntries + ", tagValue = " + tagValue);
        return numEntries < ++tagValue;
    }

    public static void main(String[] args) {
        int tagValue = 6;
        int entries = 3;
        System.out.println("origin == ROR : " + (PoolEntry(tagValue, entries) == PoolEntryROR(tagValue, entries)));
        System.out.println("origin == AOIS: " + (PoolEntry(tagValue, entries) == PoolEntryAOIS(tagValue, entries)));
        System.out.println();

        for (int i = -10; i < 50; i++) {
            System.out.println("tagValue = " + tagValue + ", entries = " + i);
            System.out.println("origin == ROR : " + (PoolEntry(i, entries) == PoolEntryROR(i, entries)));
            System.out.println("origin == AOIS: " + (PoolEntry(i, entries) == PoolEntryAOIS(i, entries)));
            System.out.println();
        }
    }
}
