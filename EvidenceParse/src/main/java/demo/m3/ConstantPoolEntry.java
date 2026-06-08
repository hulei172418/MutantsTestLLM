package demo.m3;

public class ConstantPoolEntry {

    /**
     * This entry's tag which identifies the type of this constant pool
     * entry.
     */
    private int tag;

    /**
     * The number of slots in the constant pool, occupied by this entry.
     */
    private int numEntries;

    /**
     * A flag which indicates if this entry has been resolved or not.
     */
    private boolean resolved;

    /**
     * Initialise the constant pool entry.
     *
     * @param tagValue the tag value which identifies which type of constant
     *                 pool entry this is.
     * @param entries  the number of constant pool entry slots this entry
     *                 occupies.
     */
    public boolean PoolEntry(int tagValue, int entries) {
        int numEntries = 0;
        while (entries >= 0) {
            numEntries += entries;
            entries--;
        }
        return numEntries < tagValue;
    }
}
