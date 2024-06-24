package dev.ybrig.concord.dependencydownloader;

import com.walmartlabs.concord.dependencymanager.*;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.*;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyGraphTransformer;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.collection.DependencyTraverser;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.util.artifact.DefaultArtifactTypeRegistry;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.ExclusionsDependencyFilter;

import org.eclipse.aether.util.graph.manager.ClassicDependencyManager;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.selector.ExclusionDependencySelector;
import org.eclipse.aether.util.graph.selector.OptionalDependencySelector;
import org.eclipse.aether.util.graph.selector.ScopeDependencySelector;
import org.eclipse.aether.util.graph.transformer.*;
import org.eclipse.aether.util.graph.traverser.FatArtifactTraverser;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

// TODO: release new concord version with new listener
public class DependencyManager {

    public static final String MAVEN_SCHEME = "mvn";
    private static final String FILES_CACHE_DIR = "files";

    private final Path localCacheDir;
    private final RepositorySystem maven;
    private final List<RemoteRepository> repositories;
    private final Object mutex = new Object();
    private final boolean strictRepositories = false;

    private final List<String> defaultExclusions = Collections.emptyList();

    public DependencyManager(DependencyManagerConfiguration cfg) {
        this.maven = RepositorySystemFactory.create();
        this.repositories = toRemote(cfg.repositories());
        this.localCacheDir = Paths.get(System.getProperty("user.home")).resolve(".m2/repository");
    }

    public Collection<DependencyEntity> resolve(Collection<URI> items, ProgressListener listener) throws IOException {
        if (items == null || items.isEmpty()) {
            return Collections.emptySet();
        }

        ResolveExceptionConverter exceptionConverter = new ResolveExceptionConverter(items);
        ProgressNotifier progressNotifier = new ProgressNotifier(listener, exceptionConverter);

        // ensure stable order
        List<URI> uris = new ArrayList<>(items);
        Collections.sort(uris);

        DependencyList deps = categorize(uris);

        Collection<DependencyEntity> result = new HashSet<>();

        result.addAll(resolveMavenTransitiveDependencies(deps.mavenTransitiveDependencies, deps.mavenExclusions, progressNotifier).stream()
                .map(DependencyManager::toDependency)
                .toList());

        result.addAll(resolveMavenSingleDependencies(deps.mavenSingleDependencies, progressNotifier).stream()
                .map(DependencyManager::toDependency)
                .toList());

        return result;
    }

    private Collection<Artifact> resolveMavenSingleDependencies(Collection<MavenDependency> deps, ProgressNotifier progressNotifier) throws IOException {
        Collection<Artifact> paths = new HashSet<>();
        for (MavenDependency dep : deps) {
            paths.add(resolveMavenSingle(dep, progressNotifier));
        }
        return paths;
    }

    private Artifact resolveMavenSingle(MavenDependency dep, ProgressNotifier progressNotifier) throws IOException {
        RepositorySystemSession session = newRepositorySystemSession(maven, progressNotifier);

        ArtifactRequest req = new ArtifactRequest();
        req.setArtifact(dep.artifact);
        req.setRepositories(repositories);

        synchronized (mutex) {
            try {
                ArtifactResult r = maven.resolveArtifact(session, req);
                return r.getArtifact();
            } catch (ArtifactResolutionException e) {
                throw new IOException(e);
            }
        }
    }

    private Collection<Artifact> resolveMavenTransitiveDependencies(Collection<MavenDependency> deps, List<String> exclusions, ProgressNotifier progressNotifier) throws IOException {
        // TODO: why we need new RepositorySystem?
        RepositorySystem system = RepositorySystemFactory.create();
        RepositorySystemSession session = newRepositorySystemSession(system, progressNotifier);

        CollectRequest req = new CollectRequest();
        req.setDependencies(deps.stream()
                .map(d -> new Dependency(d.artifact, d.scope))
                .collect(Collectors.toList()));
        req.setRepositories(repositories);

        List<String> excludes = new ArrayList<>(exclusions);
        excludes.addAll(defaultExclusions);

        DependencyRequest dependencyRequest = new DependencyRequest(req, new ExclusionsDependencyFilter(excludes));

        synchronized (mutex) {
            try {
                return system.resolveDependencies(session, dependencyRequest)
                        .getArtifactResults().stream()
                        .map(ArtifactResult::getArtifact)
                        .collect(Collectors.toSet());
            } catch (DependencyResolutionException e) {
                throw new IOException(e);
            }
        }
    }

    private DefaultRepositorySystemSession newRepositorySystemSession(RepositorySystem system, ProgressNotifier progressNotifier) {
        DefaultRepositorySystemSession session = newSession();
        session.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
        session.setIgnoreArtifactDescriptorRepositories(strictRepositories);

        LocalRepository localRepo = new LocalRepository(localCacheDir.toFile());
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        session.setTransferListener(new AbstractTransferListener() {
            @Override
            public void transferFailed(TransferEvent event) {
                progressNotifier.transferFailed(event);
            }
        });

        session.setRepositoryListener(new AbstractRepositoryListener() {
            @Override
            public void artifactResolved(RepositoryEvent event) {
                progressNotifier.artifactResolved(event);
            }
        });

        session.setOffline(false);

        return session;
    }

    private static List<RemoteRepository> toRemote(List<MavenRepository> l) {
        return l.stream()
                .map(DependencyManager::toRemote)
                .collect(Collectors.toList());
    }

    private static RemoteRepository toRemote(MavenRepository r) {
        RemoteRepository.Builder b = new RemoteRepository.Builder(r.id(), r.contentType(), r.url());

        MavenRepositoryPolicy releasePolicy = r.releasePolicy();
        if (releasePolicy != null) {
            b.setReleasePolicy(new RepositoryPolicy(releasePolicy.enabled(), releasePolicy.updatePolicy(), releasePolicy.checksumPolicy()));
        }

        MavenRepositoryPolicy snapshotPolicy = r.snapshotPolicy();
        if (snapshotPolicy != null) {
            b.setSnapshotPolicy(new RepositoryPolicy(snapshotPolicy.enabled(), snapshotPolicy.updatePolicy(), snapshotPolicy.checksumPolicy()));
        }

        Map<String, String> auth = r.auth();
        if (auth != null) {
            AuthenticationBuilder ab = new AuthenticationBuilder();
            auth.forEach(ab::addString);
            b.setAuthentication(ab.build());
        }

        MavenProxy proxy = r.proxy();
        if (proxy != null) {
            b.setProxy(new Proxy(proxy.type(), proxy.host(), proxy.port()));
        }

        return b.build();
    }

    private static DependencyEntity toDependency(Artifact artifact) {
        return new DependencyEntity(artifact.getFile().toPath(),
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
    }

    private DependencyList categorize(List<URI> items) throws IOException {
        List<MavenDependency> mavenTransitiveDependencies = new ArrayList<>();
        List<MavenDependency> mavenSingleDependencies = new ArrayList<>();
        List<String> mavenExclusions = new ArrayList<>();
        List<URI> directLinks = new ArrayList<>();

        for (URI item : items) {
            String scheme = item.getScheme();
            if (MAVEN_SCHEME.equalsIgnoreCase(scheme)) {
                String id = item.getAuthority();

                Artifact artifact = new DefaultArtifact(id);

                Map<String, List<String>> cfg = splitQuery(item);
                String scope = getSingleValue(cfg, "scope", JavaScopes.COMPILE);
                boolean transitive = Boolean.parseBoolean(getSingleValue(cfg, "transitive", "true"));

                if (transitive) {
                    mavenTransitiveDependencies.add(new MavenDependency(artifact, scope));
                } else {
                    mavenSingleDependencies.add(new MavenDependency(artifact, scope));
                }

                mavenExclusions.addAll(cfg.getOrDefault("exclude", Collections.emptyList()));
            } else {
                directLinks.add(item);
            }
        }

        return new DependencyList(mavenTransitiveDependencies, mavenSingleDependencies, mavenExclusions, directLinks);
    }

    private static String getSingleValue(Map<String, List<String>> m, String k, String defaultValue) {
        List<String> vv = m.get(k);
        if (vv == null || vv.isEmpty()) {
            return defaultValue;
        }
        return vv.get(0);
    }

    private static Map<String, List<String>> splitQuery(URI uri) throws UnsupportedEncodingException {
        String query = uri.getQuery();
        if (query == null || query.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<String>> m = new LinkedHashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);

            List<String> vv = m.computeIfAbsent(k, s -> new ArrayList<>());
            vv.add(v);
            m.put(k, vv);
        }
        return m;
    }

    private static final class DependencyList {

        private final List<MavenDependency> mavenTransitiveDependencies;
        private final List<MavenDependency> mavenSingleDependencies;

        private final List<String> mavenExclusions;

        private final List<URI> directLinks;

        private DependencyList(List<MavenDependency> mavenTransitiveDependencies,
                               List<MavenDependency> mavenSingleDependencies,
                               List<String> mavenExclusions,
                               List<URI> directLinks) {

            this.mavenTransitiveDependencies = mavenTransitiveDependencies;
            this.mavenSingleDependencies = mavenSingleDependencies;
            this.mavenExclusions = mavenExclusions;
            this.directLinks = directLinks;
        }
    }

    private static final class MavenDependency {

        private final Artifact artifact;
        private final String scope;

        private MavenDependency(Artifact artifact, String scope) {
            this.artifact = artifact;
            this.scope = scope;
        }
    }

    private static class ProgressNotifier implements RetryUtils.RetryListener {

        private final ProgressListener listener;
        private final ResolveExceptionConverter exceptionConverter;

        private ProgressNotifier(ProgressListener listener, ResolveExceptionConverter exceptionConverter) {
            this.listener = listener;
            this.exceptionConverter = exceptionConverter;
        }

        @Override
        public void onRetry(int tryCount, int retryCount, long retryInterval, Exception e) {
            if (listener == null) {
                return;
            }

            DependencyManagerException ex = exceptionConverter.convert(e);
            listener.onRetry(tryCount, retryCount, retryInterval, ex.getMessage());
        }

        public void transferFailed(TransferEvent event) {
            if (listener == null || event == null) {
                return;
            }

            String error = Optional.ofNullable(event.getException()).map(Throwable::toString).orElse("n/a");
            listener.onTransferFailed(event + ", error: " + error);
        }

        public void artifactResolved(RepositoryEvent event) {
            if (listener == null || event == null) {
                return;
            }

            listener.onDependencyResolved(toDependency(event.getArtifact()));
        }
    }


    public static DefaultRepositorySystemSession newSession()
    {
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();

        DependencyTraverser depTraverser = new FatArtifactTraverser();
        session.setDependencyTraverser( depTraverser );

        org.eclipse.aether.collection.DependencyManager depManager = new ClassicDependencyManager();
        session.setDependencyManager( depManager );

        DependencySelector depFilter =
                new AndDependencySelector( new ScopeDependencySelector( "test", "provided" ),
                        new OptionalDependencySelector(), new ExclusionDependencySelector() );
        session.setDependencySelector( depFilter );

        DependencyGraphTransformer transformer =
                new ConflictResolver( new NearestVersionSelector(), new JavaScopeSelector(),
                        new SimpleOptionalitySelector(), new JavaScopeDeriver() );
        transformer = new ChainedDependencyGraphTransformer( transformer, new JavaDependencyContextRefiner() );
        session.setDependencyGraphTransformer( transformer );

        DefaultArtifactTypeRegistry stereotypes = new DefaultArtifactTypeRegistry();
        stereotypes.add( new DefaultArtifactType( "pom" ) );
        stereotypes.add( new DefaultArtifactType( "maven-plugin", "jar", "", "java" ) );
        stereotypes.add( new DefaultArtifactType( "jar", "jar", "", "java" ) );
        stereotypes.add( new DefaultArtifactType( "ejb", "jar", "", "java" ) );
        stereotypes.add( new DefaultArtifactType( "ejb-client", "jar", "client", "java" ) );
        stereotypes.add( new DefaultArtifactType( "test-jar", "jar", "tests", "java" ) );
        stereotypes.add( new DefaultArtifactType( "javadoc", "jar", "javadoc", "java" ) );
        stereotypes.add( new DefaultArtifactType( "java-source", "jar", "sources", "java", false, false ) );
        stereotypes.add( new DefaultArtifactType( "war", "war", "", "java", false, true ) );
        stereotypes.add( new DefaultArtifactType( "ear", "ear", "", "java", false, true ) );
        stereotypes.add( new DefaultArtifactType( "rar", "rar", "", "java", false, true ) );
        stereotypes.add( new DefaultArtifactType( "par", "par", "", "java", false, true ) );
        session.setArtifactTypeRegistry( stereotypes );

        session.setArtifactDescriptorPolicy( new SimpleArtifactDescriptorPolicy( true, true ) );

        final Properties systemProperties = new Properties();

        // MNG-5670 guard against ConcurrentModificationException
        // MNG-6053 guard against key without value
        Properties sysProp = System.getProperties();
        synchronized ( sysProp )
        {
            systemProperties.putAll( sysProp );
        }

        session.setSystemProperties( systemProperties );
        session.setConfigProperties( systemProperties );

        return session;
    }
}
