package dev.ybrig.concord.dependencydownloader;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.walmartlabs.concord.dependencymanager.DependencyEntity;
import com.walmartlabs.concord.dependencymanager.DependencyManagerConfiguration;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Mojo(name = "download", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class DependencyDownloaderMojo extends AbstractMojo {

    @Parameter(property = "skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "debug", defaultValue = "false")
    private boolean debug;

    @Parameter(property = "plugins", required = true)
    List<String> plugins;

    @Parameter(property = "downloadedFilesPath", required = true)
    String downloadedFilesPath;

    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping plugin execution as per configuration");
            return;
        }

        getLog().info("Downloading dependencies for plugins:");
        plugins.forEach(p -> getLog().info(p));

        try {
            Path tmpDir = Files.createTempDirectory("test");
            DependencyManager m = new DependencyManager(DependencyManagerConfiguration.of(tmpDir));

            for (String p : plugins) {
                URI uri = toURI(p);

                m.resolve(List.of(uri), new ArtifactSaver(Paths.get(downloadedFilesPath)));
            }

        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private static class ArtifactSaver implements ProgressListener {

        private final Path m2Home = Paths.get(System.getProperty("user.home"), ".m2");

        private final Path downloadedFilesPath;

        private ArtifactSaver(Path downloadedFilesPath) {
            this.downloadedFilesPath = ensureDirectory(downloadedFilesPath);
        }

        @Override
        public void onDependencyResolved(DependencyEntity dependency) {
            Path artifactTargetPath = downloadedFilesPath.resolve(m2Home.relativize(dependency.getPath()));
            ensureDirectory(artifactTargetPath.getParent());

            try {
                Files.copy(dependency.getPath(), artifactTargetPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static URI toURI(String p) {
        try {
            return new URI(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Path ensureDirectory(Path p) {
        if (p == null) {
            return null;
        }

        if (Files.notExists(p)) {
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return p;
    }
}
