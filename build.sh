#!/bin/bash

set -e

MVN_OPTS="-Duser.home=/var/maven"

# Runs maven in the build container with the given arguments.
mvn_run() {
  docker compose run --rm maven mvn $MVN_OPTS "$@"
}

# Writes the .env read by compose.yaml: the user to run as and the group owning the Docker socket.
init() {
  me=`id -u`:`id -g`
  docker_gid=`stat -c '%g' /var/run/docker.sock 2>/dev/null || echo 0`
  echo "DEFAULT_DOCKER_USER=$me" > .env
  echo "DOCKER_GID=$docker_gid" >> .env
}

# Removes the build outputs.
clean() {
  mvn_run clean
}

# Builds and installs the artifacts in the local repository, without the tests.
install() {
  mvn_run install -DskipTests
}

# Runs the unit and integration tests. They need Docker, for the NATS dev service.
test() {
  mvn_run verify
}

# Deploys the artifacts to the Nexus repository matching the version.
publish() {
  version=`mvn_run help:evaluate -Dexpression=project.version -q -DforceStdout`
  case "$version" in
    *SNAPSHOT) nexusRepository='snapshots' ;;
    *)         nexusRepository='releases' ;;
  esac
  mvn_run -DrepositoryId=ode-$nexusRepository -DskipTests --settings /var/maven/.m2/settings.xml deploy
}

if [ ! -f .env ]; then
  init
fi

for param in "$@"
do
  case $param in
    init)
      init
      ;;
    clean)
      clean
      ;;
    install)
      install
      ;;
    test)
      test
      ;;
    publish)
      publish
      ;;
    *)
      echo "Invalid argument : $param"
      exit 1
  esac
done
