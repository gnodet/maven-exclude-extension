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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Named;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
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
public class ExcludeParticipant extends AbstractMavenLifecycleParticipant {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExcludeParticipant.class);

    //
    // AbstractMavenLifecycleParticipant
    //

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        File file = new File(session.getRequest().getMultiModuleProjectDirectory(), ".mvn/excludes.txt");
        if (file.canRead()) {
            List<String> exclusions;
            try {
                exclusions = Files.readAllLines(file.toPath(), Charset.defaultCharset());
            } catch (IOException e) {
                throw new MavenExecutionException("Unable to read exclusions", e);
            }
            LOGGER.info("***********************************************************");
            LOGGER.info("ExcludeExtension initialized with the following exclusions:");
            for (String exclusion : exclusions) {
                LOGGER.info("    {}", exclusion);
            }
            LOGGER.info("***********************************************************");
            Map<File, MavenProject> projectsByPomLocation =  session.getAllProjects().stream()
                    .collect(Collectors.toMap(MavenProject::getFile, Function.identity()));
            Map<String, MavenProject> projectsByGroupArtifact =  session.getAllProjects().stream()
                    .collect(Collectors.toMap(p -> p.getGroupId() + ":" + p.getArtifactId(), Function.identity()));
            File reactorDirectory = Optional.ofNullable(session.getRequest().getBaseDirectory())
                    .map(File::new).orElse(null);
            List<MavenProject> newAllProjects = new ArrayList<>();
            List<MavenProject> newProjects = new ArrayList<>();
            for (MavenProject project : session.getAllProjects()) {
                // Remove this project completely
                if (exclusions.stream().noneMatch(e -> isMatchingProject(project, e, reactorDirectory))) {
                    LOGGER.info("Project included: " + project);
                    newAllProjects.add(project);
                    if (session.getProjects().contains(project)) {
                        newProjects.add(project);
                    }
                    // Remove modules
                    Map<String, List<Integer>> removed = excludeFromPom(exclusions, projectsByPomLocation, projectsByGroupArtifact,
                            reactorDirectory, project, project.getModel());

                    if (!removed.isEmpty()) {
                        File pomFile = project.getFile();
                        try (XmlStreamReader in = ReaderFactory.newXmlReader(pomFile))
                        {
                            rewritePom(project, pomFile, removed, in);
                        } catch (Exception e) {
                            throw new MavenExecutionException("Unable to write pom", e);
                        }
                    }
                }
            }
            session.setAllProjects(newAllProjects);
            session.setProjects(newProjects);
        } else {
            LOGGER.info( "*****************************************************************************" );
            LOGGER.info( "ExcludeExtension initialized but no exclusions provided in ./mvn/excludes.txt" );
            LOGGER.info( "*****************************************************************************" );
        }
    }

    private void rewritePom(MavenProject project, File pomFile, Map<String, List<Integer>> removed, XmlStreamReader in)
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


    private Map<String, List<Integer>> excludeFromPom(
            List<String> exclusions,
            Map<File, MavenProject> projectsByPomLocation,
            Map<String, MavenProject> projectsByGroupArtifact,
            File reactorDirectory,
            MavenProject project,
            Model model) {
        Map<String, List<Integer>> removed = new HashMap<>();
        List<Integer> removedModules = new ArrayList<>();
        for (int i = 0; i < model.getModules().size(); i++) {
            String module = model.getModules().get(i);
            module = module.replace('\\', File.separatorChar).replace('/', File.separatorChar);
            File moduleFile = new File(project.getBasedir(), module);
            if (moduleFile.isDirectory()) {
                moduleFile = new File(moduleFile, "pom.xml");
            }
            MavenProject child = projectsByPomLocation.get(moduleFile);
            if (child != null && exclusions.stream().anyMatch(e -> isMatchingProject(child, e, reactorDirectory))) {
                removedModules.add(i);
            }
        }
        if (!removedModules.isEmpty()) {
            removed.put("modules", removedModules);
            removedModules.stream().sorted(Comparator.reverseOrder()).forEach(model.getModules()::remove);
        }
        // Remove dependency management
        if (model.getDependencyManagement() != null) {
            List<Integer> removedDepMgmt = new ArrayList<>();
            List<Dependency> dependencies = model.getDependencyManagement().getDependencies();
            for (int i = 0; i < dependencies.size(); i++) {
                Dependency dependency = dependencies.get(i);
                String ga = dependency.getGroupId() + ":" + dependency.getArtifactId();
                MavenProject dep = projectsByGroupArtifact.get(ga);
                if (dep != null && exclusions.stream().anyMatch(e -> isMatchingProject(dep, e, reactorDirectory))) {
                    removedDepMgmt.add(i);
                }
            }
            if (!removedDepMgmt.isEmpty()) {
                removed.put("dependencyManagement/dependencies", removedDepMgmt);
                removedDepMgmt.stream().sorted(Comparator.reverseOrder()).forEach(dependencies::remove);
            }
        }
        // Remove dependencies
        List<Integer> removedDeps = new ArrayList<>();
        List<Dependency> dependencies = model.getDependencies();
        for (int i = 0; i < dependencies.size(); i++) {
            Dependency dependency = dependencies.get(i);
            String ga = dependency.getGroupId() + ":" + dependency.getArtifactId();
            MavenProject dep = projectsByGroupArtifact.get(ga);
            if (dep != null && exclusions.stream().anyMatch(e -> isMatchingProject(dep, e, reactorDirectory))) {
                removedDeps.add(i);
            }
        }
        if (!removedDeps.isEmpty()) {
            removed.put("dependencies", removedDeps);
            removedDeps.stream().sorted(Comparator.reverseOrder()).forEach(dependencies::remove);
        }
        return removed;
    }

    private boolean isMatchingProject(MavenProject project, String selector, File reactorDirectory) {
        // [groupId]:artifactId
        if (selector.indexOf(':') >= 0) {
            String id = ':' + project.getArtifactId();
            if (id.equals(selector)) {
                return true;
            }
            id = project.getGroupId() + id;
            if (id.equals(selector)) {
                return true;
            }
        }
        // relative path, e.g. "sub", "../sub" or "."
        else if (reactorDirectory != null) {
            File selectedProject = new File(new File(reactorDirectory, selector).toURI().normalize());
            if (selectedProject.isFile()) {
                return selectedProject.equals(project.getFile());
            } else if (selectedProject.isDirectory()) {
                return selectedProject.equals(project.getBasedir());
            }
        }
        return false;
    }

    static class ExclusionParser extends BufferingParser {
        private final Map<String, List<Integer>> removed;
        private boolean inModules;
        private boolean inDepMgmt;
        private boolean inDependencies;
        private List<Event> buffer;

        public ExclusionParser(MXParser mxParser, Map<String, List<Integer>> removed) {
            super(mxParser);
            this.removed = removed;
        }

        @Override
        protected boolean accept() throws XmlPullParserException, IOException {
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
            List<Integer> toRemove = removed.get(key);
            if (toRemove != null) {
                int index = 0;
                for (Event e : buffer) {
                    if (e.event == START_TAG && nodeName.equals(e.name)) {
                        tmpBuffer = new ArrayList<>();
                        tmpBuffer.add(e);
                        sb = new StringBuilder();
                    } else if (tmpBuffer != null) {
                        tmpBuffer.add(e);
                        if (e.event == TEXT) {
                            sb.append(e.text);
                        } else if (e.event == END_TAG && nodeName.equals(e.name)) {
                            if (!toRemove.contains(index)) {
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
}
