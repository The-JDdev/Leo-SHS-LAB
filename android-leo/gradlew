#!/bin/sh
##############################################################################
##  Gradle start up script for UN*X
##############################################################################

# Attempt to set APP_HOME
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")"/$link"
    fi
done

SAVED="$(pwd)"
cd "$(dirname "$PRG")" >/dev/null
APP_HOME="$(pwd -P)"
cd "$SAVED" >/dev/null

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

DEFAULT_JVM_OPTS='"-Xmx2g" "-Xms512m" "-XX:MaxMetaspaceSize=256m" "-XX:+UseG1GC"'

# Use the maximum available or user overridden JVM
if [ "$JAVA_HOME" != "" ] ; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="java"
fi

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec "$JAVA" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
