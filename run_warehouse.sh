#!/bin/sh
mvn exec:java -Dexec.mainClass=demo.MockWarehouse -Dexec.args="127.0.0.1"
