import Dependencies._

import AssemblyKeys._

name := "datastore-cli"

libraryDependencies ++= Seq(slf4j, scopt, logbackClassic, logbackCore)

assemblySettings

jarName in assembly := "DatastoreCli.jar"

mainClass in assembly := Some("org.allenai.datastore.cli.DatastoreCli")

version := "0.1"