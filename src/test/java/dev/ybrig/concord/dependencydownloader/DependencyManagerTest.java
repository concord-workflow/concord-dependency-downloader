package dev.ybrig.concord.dependencydownloader;

import com.walmartlabs.concord.dependencymanager.DependencyEntity;
//import com.walmartlabs.concord.dependencymanager.DependencyManager;
import com.walmartlabs.concord.dependencymanager.DependencyManagerConfiguration;

import dev.ybrig.concord.dependencydownloader.DependencyManager.*;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;

@Disabled
public class DependencyManagerTest {

    @Test
    public void testResolve() throws Exception {
        Path tmpDir = Files.createTempDirectory("test");

        URI uriA = new URI("mvn://ca.vanzyl.concord.ck8s:ck8s-plugin:3.0.5-SNAPSHOT");

        DependencyManager m = new DependencyManager(DependencyManagerConfiguration.of(tmpDir));
        Collection<DependencyEntity> paths = m.resolve(Arrays.asList(uriA), new ProgressListener() {
            @Override
            public void onDependencyResolved(DependencyEntity dependency) {
                System.out.println(">>>" + dependency);
            }
        });

        paths.forEach(p -> System.out.println(p));
    }
}
