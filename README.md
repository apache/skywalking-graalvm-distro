# SkyWalking GraalVM Distro (Experimental)
<img src="http://skywalking.apache.org/assets/logo.svg" alt="Sky Walking logo" height="90px" align="right" />

GraalVM compiles your Java applications ahead of time into standalone binaries. These binaries are smaller, start up to 100x faster, 
provide peak performance with no warmup, and use less memory and CPU than applications running on a Java Virtual Machine (JVM).

SkyWalking GraalVM Distro is a re-distribution version of the official Apache SkyWalking OAP server.
This distro could bring the OAP benefits from the native build powered by Graal VM.
But as the limitation of the GraalVM, this distro may provide features in different implementations, and require recompilation 
rather than change config files. 

⚠️ This distro is facing the lack of support about Groovy on GraalVM issues, only OAL relative analysis codes can't be compiled and run on GraalVM. ⚠️

**MAL, LAL and (Service/Instance) Hierarchy Analysis(since v10) are heavily relying on Groovy and GroovyShell, so all of these are not support in this distro.**

## ⚠️ Release Policy ⚠️
This repository is only on the experimental stage, no release will be made and driven by the PMC by the current status, until we have a way out.

# License 
Apache 2.0
