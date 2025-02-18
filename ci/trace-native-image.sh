#!/usr/bin/env bash

# Trace the atom executable using Graal native-image-agent to produce an optimized native binary
#
# bash ci/trace-native-image.sh java <file path>
# bash ci/trace-native-image.sh js <file path>
# bash ci/trace-native-image.sh py <file path>
# bash ci/trace-native-image.sh c <file path>

sbt stage
./atom.sh -J-agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image reachables -l $1 -o /tmp/app.atom -s /tmp/reachables.slices.json $2

# bash ci/native-image.sh
