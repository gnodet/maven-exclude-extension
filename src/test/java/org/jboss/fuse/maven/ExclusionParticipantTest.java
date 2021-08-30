package org.jboss.fuse.maven;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.XmlStreamReader;
import org.codehaus.plexus.util.xml.pull.MXParser;
import org.codehaus.plexus.util.xml.pull.XmlPullParser;
import org.jboss.fuse.maven.pull.XmlUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExclusionParticipantTest {

    @Test
    public void testTwoDeps() throws Exception {
        MXParser mxParser = new MXParser();
        mxParser.setInput(new XmlStreamReader(Objects.requireNonNull(getClass().getResource("/pom-1.xml"))));
        Map<String, List<InputLocation>> removed = new HashMap<>();
        removed.put("modules", Arrays.asList(loc(18, 17), loc(20, 17)));
        removed.put("dependencyManagement/dependencies", Arrays.asList(loc(32, 25), loc(38, 25)));
        XmlPullParser parser = new ExcludeParticipant.ExclusionParser(mxParser, removed);
        StringWriter sw = new StringWriter();
        XmlUtils.writeDocument(parser, sw);
        System.out.println(sw);
    }

    @Test
    public void testParser() throws Exception {
        MXParser mxParser = new MXParser();
        mxParser.setInput(new XmlStreamReader(new File("src/it/projects/simple/pom.xml")));
        Map<String, List<InputLocation>> removed = new HashMap<>();
        removed.put("modules", Arrays.asList(loc(18, 17), loc(20, 17)));
        removed.put("dependencyManagement/dependencies", Arrays.asList(loc(32, 25), loc(38, 25)));
        XmlPullParser parser = new ExcludeParticipant.ExclusionParser(mxParser, removed);
        StringWriter sw = new StringWriter();
        XmlUtils.writeDocument(parser, sw);
        System.out.println(sw);
    }

    private InputLocation loc(int line, int col) {
        return new InputLocation(line, col);
    }
}
