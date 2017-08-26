# Jenkins Random Job Builder Plugin

This plugin will offer the ability to run jobs to generate load on a Jenkins master. 

It is useful for load-testing with jobs.  

There are two modes: 

* Random Builder (see the Manage Jenkins > Configure page) - randomly selects jobs and builds them at a specified average rate
* LoadGenerator - individual, named load generators that will select and run sets of jobs with specific concurrency
    - Generators are configured in the Manage Jenkins > Configure page
    - There's a controller than enables/disable generators and overall autostart (see the LoadGenerator link in the left sidepanel)

# Environment

The following build environment is required to build this plugin

* `java-1.6` and `maven-3.0.5`

# Build

To build the plugin locally:

    mvn clean verify

# Release

To release the plugin:

    mvn release:prepare release:perform -B

# Test local instance

To test in a local Jenkins instance

    mvn hpi:run

  [wiki]: http://wiki.jenkins-ci.org/display/JENKINS/Random+Job+Builder+Plugin
