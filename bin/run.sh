#!/bin/sh

MACHINE=`bin/machine.sh`

runtimes/$MACHINE/bin/java -server -Dhttps.protocols=TLSv1.2 -jar xapipe.jar $@
