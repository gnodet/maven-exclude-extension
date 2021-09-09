package org.jboss.fuse.maven;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.xml.XmlStreamReader;
import org.codehaus.plexus.util.xml.pull.MXParser;
import org.codehaus.plexus.util.xml.pull.XmlPullParser;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jboss.fuse.maven.pull.BufferingParser;
import org.jboss.fuse.maven.pull.XmlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Named
@Singleton
public class ExcludeParticipant extends AbstractMavenLifecycleParticipant {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    //
    // AbstractMavenLifecycleParticipant
    //

    public ExcludeParticipant() {
        logger.debug("***********************************************************");
        logger.debug("ExcludeExtension created");
        logger.debug("***********************************************************");
    }

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        File file = new File(session.getRequest().getMultiModuleProjectDirectory(), ".mvn/excludes.txt");
        if (file.canRead()) {
            ExcludePattern exclusions = getExcludePattern(session, file);
            logger.debug("***********************************************************");
            logger.info("ExcludeExtension initialized");
            logger.info("Using following exclusions: {}", exclusions);
            logger.debug("***********************************************************");
            Map<File, MavenProject> projectsByPomLocation =  session.getAllProjects().stream()
                    .collect(Collectors.toMap(MavenProject::getFile, Function.identity()));
            Map<String, MavenProject> projectsByGroupArtifact =  session.getAllProjects().stream()
                    .collect(Collectors.toMap(p -> p.getGroupId() + ":" + p.getArtifactId(), Function.identity()));
            List<MavenProject> newAllProjects = new ArrayList<>();
            List<MavenProject> newProjects = new ArrayList<>();
            for (MavenProject project : session.getAllProjects()) {
                // Remove this project completely
                if (!exclusions.isMatchingProject(project)) {
                    logger.debug("Project included: " + project);
                    newAllProjects.add(project);
                    if (session.getProjects().contains(project)) {
                        newProjects.add(project);
                    }
                    // Remove modules
                    Map<String, List<InputLocation>> removed = excludeFromPom(exclusions, projectsByPomLocation,
                            projectsByGroupArtifact, project);

                    if (!removed.isEmpty()) {
                        File pomFile = project.getFile();
                        try (XmlStreamReader in = ReaderFactory.newXmlReader(pomFile))
                        {
                            rewritePom(project, pomFile, removed, in);
                        } catch (Exception e) {
                            throw new MavenExecutionException("Unable to write pom", e);
                        }
                    }
                } else {
                    logger.debug("Project excluded: " + project);
                }
            }
            session.setAllProjects(newAllProjects);
            session.setProjects(newProjects);
        } else {
            logger.debug( "*****************************************************************************" );
            logger.warn( "ExcludeExtension initialized but no exclusions provided in ./mvn/excludes.txt" );
            logger.debug( "*****************************************************************************" );
        }
    }

    private ExcludePattern getExcludePattern(MavenSession session, File file) throws MavenExecutionException {
        File reactorDirectory = Optional.ofNullable(session.getRequest().getBaseDirectory())
                .map(File::new).orElse(null);
        ExcludePattern exclusions;
        try {
            exclusions = new ExcludePattern(reactorDirectory, Files.readAllLines(file.toPath(), Charset.defaultCharset()).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList()));
        } catch (IOException e) {
            throw new MavenExecutionException("Unable to read exclusions", e);
        }
        return exclusions;
    }

    private void rewritePom(MavenProject project, File pomFile, Map<String, List<InputLocation>> removed, XmlStreamReader in)
            throws XmlPullParserException, IOException {

        File excludePomFile = new File(pomFile.getParentFile(), ".exclude-pom.xml");
        MXParser mxParser = new MXParser();
        mxParser.setInput(in);
        XmlPullParser parser = new ExclusionParser(mxParser, removed);
        try (OutputStreamWriter writer = new FileWriter(excludePomFile, Charset.forName(in.getEncoding()))) {
            XmlUtils.writeDocument(parser, writer);
        }
        project.setPomFile(excludePomFile);
    }

    private Map<String, List<InputLocation>> excludeFromPom(
            ExcludePattern exclusions,
            Map<File, MavenProject> projectsByPomLocation,
            Map<String, MavenProject> projectsByGroupArtifact,
            MavenProject project) {
        Model model = project.getModel();
        Map<String, List<InputLocation>> removed = new HashMap<>();
        List<InputLocation> removedModules = new ArrayList<>();
        List<Integer> removedIndices = new ArrayList<>();
        for (int i = 0; i < model.getModules().size(); i++) {
            String module = model.getModules().get(i);
            module = module.replace('\\', File.separatorChar).replace('/', File.separatorChar);
            File moduleFile = new File(project.getBasedir(), module);
            if (moduleFile.isDirectory()) {
                moduleFile = new File(moduleFile, "pom.xml");
            }
            MavenProject child = projectsByPomLocation.get(moduleFile);
            if (child != null && exclusions.isMatchingProject(child)) {
                InputLocation loc = model.getLocation("modules");
                if (loc != null) {
                    removedModules.add(loc.getLocation(i));
                }
                removedIndices.add(i);
                logger.debug("Removing module {} from {}", module, project);
            }
        }
        if (!removedIndices.isEmpty()) {
            removed.put("modules", removedModules);
            removeIndices(model.getModules(), removedIndices);
            removedIndices.clear();
        }
        // Remove dependency management
        if (model.getDependencyManagement() != null) {
            List<InputLocation> removedDepMgmt = new ArrayList<>();
            List<Dependency> dependencies = model.getDependencyManagement().getDependencies();
            for (int i = 0; i < dependencies.size(); i++) {
                Dependency dependency = dependencies.get(i);
                String ga = dependency.getGroupId() + ":" + dependency.getArtifactId();
                MavenProject dep = projectsByGroupArtifact.get(ga);
                boolean remove;
                if (dep != null) {
                    remove = exclusions.isMatchingProject(dep);
                } else {
                    remove = exclusions.isMatchingDependency(dependency);
                }
                if (remove) {
                    removedDepMgmt.add(dependency.getLocation(""));
                    removedIndices.add(i);
                    logger.debug("Removing managed dependency {} from {}", ga, project);
                }
            }
            if (!removedDepMgmt.isEmpty()) {
                removed.put("dependencyManagement/dependencies", removedDepMgmt);
                removeIndices(dependencies, removedIndices);
                removedIndices.clear();
            }
        }
        // Remove dependencies
        List<InputLocation> removedDeps = new ArrayList<>();
        List<Dependency> dependencies = model.getDependencies();
        List<String> removedGa = new ArrayList<>();
        for (int i = 0; i < dependencies.size(); i++) {
            Dependency dependency = dependencies.get(i);
            String ga = dependency.getGroupId() + ":" + dependency.getArtifactId();
            MavenProject dep = projectsByGroupArtifact.get(ga);
            boolean remove;
            if (dep != null) {
                remove = exclusions.isMatchingProject(dep);
            } else {
                remove = exclusions.isMatchingDependency(dependency);
            }
            if (remove) {
                removedDeps.add(dependency.getLocation(""));
                removedIndices.add(i);
                removedGa.add(ga);
                logger.debug("Removing dependency {} from {}", ga, project);
            }
        }
        if (!removedDeps.isEmpty()) {
            removed.put("dependencies", removedDeps);
            removeIndices(dependencies, removedIndices);
        }
        return removed;
    }

    private void removeIndices(List<?> l, List<Integer> li) {
        li.stream().sorted(Comparator.reverseOrder()).forEach(i -> l.remove((int) i));
    }

    static class ExclusionParser extends BufferingParser {
        private final Map<String, List<InputLocation>> removed;
        private boolean inModules;
        private boolean inDepMgmt;
        private boolean inDependencies;
        private List<Event> buffer;

        public ExclusionParser(MXParser mxParser, Map<String, List<InputLocation>> removed) {
            super(mxParser);
            this.removed = removed;
        }

        @Override
        protected boolean accept() throws XmlPullParserException {
            if (!inModules && getEventType() == START_TAG && "modules".equals(getName())) {
                inModules = true;
                buffer = new ArrayList<>();
            } else if (!inDepMgmt && getEventType() == START_TAG && "dependencyManagement".equals(getName())) {
                inDepMgmt = true;
            } else if (!inDependencies && getEventType() == START_TAG && "dependencies".equals(getName())) {
                inDependencies = true;
                buffer = new ArrayList<>();
            }
            if (inModules) {
                buffer.add(bufferEvent());
                if (getEventType() == END_TAG && "modules".equals(getName())) {
                    processRemoval("modules", "module");
                    inModules = false;
                }
                return false;
            } else if (inDependencies) {
                buffer.add(bufferEvent());
                if (getEventType() == END_TAG && "dependencies".equals(getName())) {
                    processRemoval(inDepMgmt ? "dependencyManagement/dependencies" : "dependencies", "dependency");
                    inDependencies = false;
                }
                return false;
            } else if (inDepMgmt) {
                if (getEventType() == END_TAG && "dependencyManagement".equals(getName())) {
                    inDepMgmt = false;
                }
                return true;
            } else {
                return true;
            }
        }

        private void processRemoval(String key, String nodeName) {
            List<Event> newBuffer = new ArrayList<>();
            List<Event> tmpBuffer = null;
            List<Event> spcBuffer = new ArrayList<>();
            StringBuilder sb = null;
            List<InputLocation> toRemove = removed.get(key);
            if (toRemove != null) {
                int index = 0;
                boolean discard = false;
                for (Event e : buffer) {
                    if (e.event == START_TAG && nodeName.equals(e.name)) {
                        discard = toRemove.stream().anyMatch(l -> l.getLineNumber() == e.line && l.getColumnNumber() == e.column);
                        tmpBuffer = new ArrayList<>();
                        tmpBuffer.add(e);
                        sb = new StringBuilder();
                    } else if (tmpBuffer != null) {
                        tmpBuffer.add(e);
                        if (e.event == TEXT) {
                            sb.append(e.text);
                        } else if (e.event == END_TAG && nodeName.equals(e.name)) {
                            if (!discard) {
                                newBuffer.addAll(spcBuffer);
                                newBuffer.addAll(tmpBuffer);
                            }
                            tmpBuffer = null;
                            spcBuffer.clear();
                            index++;
                        }
                    } else {
                        if (e.event == TEXT && e.text.isBlank() || e.event == COMMENT) {
                            spcBuffer.add(e);
                        } else if (e.event == END_TAG) {
                            newBuffer.addAll(spcBuffer);
                            newBuffer.add(e);
                            spcBuffer = new ArrayList<>();
                        } else {
                            newBuffer.add(e);
                        }
                    }
                }
                newBuffer.forEach(this::pushEvent);
            } else {
                buffer.forEach(this::pushEvent);
            }
        }
    }

    class ExcludePattern {
        private final List<String> selectors;
        private final Map<String, Set<String>> gaSelectors;
        private final Map<File, String> fileSelectors;
        private final Map<File, String> dirSelectors;

        public ExcludePattern(File reactorDirectory, List<String> selectors) {
            this.selectors = selectors;
            // [groupId]:artifactId
            this.gaSelectors = new HashMap<>();
            for (String s : selectors) {
                int idx = s.indexOf(':');
                if (idx >= 0) {
                    gaSelectors.computeIfAbsent(s.substring(0, idx), k -> new HashSet<>()).add(s.substring(idx + 1));
                }
            }
            this.fileSelectors = new HashMap<>();
            this.dirSelectors = new HashMap<>();
            for (String selector : selectors) {
                if (selector.indexOf(':') < 0) {
                    File f = new File(new File(reactorDirectory, selector).toURI().normalize());
                    if (f.isFile()) {
                        fileSelectors.put(f, selector);
                    } else if (f.isDirectory()) {
                        dirSelectors.put(f, selector);
                    }
                }
            }
        }

        public boolean isMatchingDependency(Dependency dependency) {
            if (gaSelectors.get("").contains(dependency.getArtifactId())) {
                logger.debug("Dependency {} matches ':{}'", dependency, dependency.getArtifactId());
                return true;
            }

            if (gaSelectors.computeIfAbsent(dependency.getGroupId(), k -> new HashSet<>()).contains(dependency.getArtifactId())) {
                logger.debug("Dependency {} matches '{}:{}'", dependency, dependency.getGroupId(), dependency.getArtifactId());
                return true;
            }
            return false;
        }

        public boolean isMatchingProject(MavenProject project) {
            // [groupId]:artifactId
            if (gaSelectors.get("").contains(project.getArtifactId())) {
                logger.debug("Project {} matches ':{}'", project, project.getArtifactId());
                return true;
            }
            if (gaSelectors.computeIfAbsent(project.getGroupId(), k -> new HashSet<>()).contains(project.getArtifactId())) {
                logger.debug("Project {} matches '{}:{}'", project, project.getGroupId(), project.getArtifactId());
                return true;
            }
            // relative path, e.g. "sub", "../sub" or "."
            if (fileSelectors.containsKey(project.getFile())) {
                logger.debug("Project {} matches '{}'", project, fileSelectors.get(project.getFile()));
                return true;
            }
            if (dirSelectors.containsKey(project.getBasedir())) {
                logger.debug("Project {} matches '{}'", project, dirSelectors.get(project.getBasedir()));
                return true;
            }
            return false;
        }

        @Override
        public String toString() {
            return "ExcludePattern" + selectors;
        }
    }
}
