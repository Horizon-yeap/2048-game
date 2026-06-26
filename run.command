#!/bin/bash
cd "$(dirname "$0")"
mvn -f pom.xml clean package -q
java -jar target/game2048-1.0.jar
