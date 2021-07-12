package org.jboss.fuse.maven;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;

import javax.inject.Named;

import com.google.common.io.Files;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.MavenExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Named
public class ExcludeExtension extends AbstractEventSpy {

    private final Logger LOGGER = LoggerFactory.getLogger( getClass() );

    public void onEvent(Object event) throws Exception {
        if (event instanceof MavenExecutionRequest) {
            MavenExecutionRequest request = (MavenExecutionRequest) event;
            File file = new File(request.getMultiModuleProjectDirectory(), ".mvn/excludes.txt");
            if (file.canRead()) {
                List<String> exclusions = Files.readLines(file, Charset.defaultCharset());
                request.setExcludedProjects(exclusions);
                LOGGER.info("***********************************************************");
                LOGGER.info("ExcludeExtension initialized with the following exclusions:");
                for (String exclusion : exclusions) {
                    LOGGER.info("    {}", exclusion);
                }
                LOGGER.info( "***********************************************************" );
            } else {
                LOGGER.warn( "*****************************************************************************" );
                LOGGER.info( "ExcludeExtension initialized but no exclusions provided in ./mvn/excludes.txt" );
                LOGGER.info( "*****************************************************************************" );
            }
        }
    }

}
