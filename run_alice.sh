#!/bin/sh
mvn exec:java -Dexec.mainClass=demo.MockDepositor -Dexec.args="127.0.0.1 ALICE"

