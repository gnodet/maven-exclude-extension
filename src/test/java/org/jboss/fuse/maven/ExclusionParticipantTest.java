package org.jboss.fuse.maven;

import java.io.File;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.plexus.util.xml.XmlStreamReader;
import org.codehaus.plexus.util.xml.pull.MXParser;
import org.codehaus.plexus.util.xml.pull.XmlPullParser;
import org.jboss.fuse.maven.pull.XmlUtils;
import org.junit.jupiter.api.Test;

public class ExclusionParticipantTest {

    @Test
    public void testParser() throws Exception {
        MXParser mxParser = new MXParser();
        mxParser.setInput(new XmlStreamReader(new File("src/it/projects/simple/pom.xml")));
        Map<String, List<Integer>> removed = new HashMap<>();
        removed.put("modules", Arrays.asList(0));
        removed.put("dependencyManagement/dependencies", Arrays.asList(1));
        XmlPullParser parser = new ExcludeParticipant.ExclusionParser(mxParser, removed);
        StringWriter sw = new StringWriter();
        XmlUtils.writeDocument(parser, sw);
        System.out.println(sw);
    }
}
