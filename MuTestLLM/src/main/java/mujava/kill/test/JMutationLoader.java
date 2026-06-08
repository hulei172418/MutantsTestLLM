package mujava.kill.test;

import mujava.MutationSystem;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Mutant-program isolated loader after common logic extraction. */
public class JMutationLoader extends AbstractIsolatedProjectLoader {

    private final String mutantName;

    public JMutationLoader(String mutantName) {
        this(mutantName, null, null, null);
    }

    public JMutationLoader(String mutantName, List<String> classDirs, List<String> jarPaths) {
        this(mutantName, classDirs, jarPaths, null);
    }

    public JMutationLoader(String mutantName, List<String> classDirs, List<String> jarPaths, String preferLocalPrefix) {
        super(classDirs, jarPaths, preferLocalPrefix);
        this.mutantName = mutantName;
    }

    @Override
    protected byte[] tryLoadPrimaryBytes(String name) throws IOException {
        String mutantDir = MutationSystem.MUTANT_PATH + File.separator + mutantName;
        try {
            return getClassDataNoCache(name, mutantDir);
        } catch (FileNotFoundException ignored) {
        }

        int startIndex = name.lastIndexOf('.');
        if (startIndex >= 0) {
            String simpleName = name.substring(startIndex + 1);
            return getClassDataNoCache(simpleName, mutantDir);
        }
        throw new FileNotFoundException("Mutant class not found: " + name);
    }
}
