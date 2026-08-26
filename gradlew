#!/bin/sh
# Gradle start up script for Unix
DIRNAME=$(dirname "$0")
exec java -Xmx64m -Xms64m -classpath "$DIRNAME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
