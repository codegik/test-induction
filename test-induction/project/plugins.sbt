// Produces a self-contained app layout (bin/ launcher + lib/ jars) via `sbt stage`,
// which the Dockerfile copies into a slim JRE image. Avoids running sbt — or
// resolving fat-jar merge conflicts across WireMock's many deps — at runtime.
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")
