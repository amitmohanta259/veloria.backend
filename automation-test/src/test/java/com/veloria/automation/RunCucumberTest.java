package com.veloria.automation;

import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * JUnit 5 entry point for every feature under src/test/resources/features.
 *
 * Tag filtering is read by the Cucumber engine from the system property
 * cucumber.filter.tags, which surefire passes through from the command line
 * (see pom.xml for the default), so one runner serves the smoke, regression
 * and module suites.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
public class RunCucumberTest {
}
