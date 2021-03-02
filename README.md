# SensorThingsImporter [![Build Status](https://github.com/FraunhoferIOSB/SensorThingsImporter/workflows/Maven%20Build/badge.svg)](https://github.com/FraunhoferIOSB/SensorThingsImporter/actions)

A tool for importing observations from various sources such as CSV files into a SensorThings API compatible service.

Starting without parameters opens the gui, which can be used to create or edit a configuration file.

Command line options are described in the help output:
```
java -jar SensorThingsImporter-0.1-SNAPSHOT.jar -help

-noact -n :
    Read the file and give output, but do not actually post observations.

-config -c [file path] :
    The path to the config json file.
```
