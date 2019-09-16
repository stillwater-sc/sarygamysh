# Sarygamysh posit-compliant co-processor

Source repository for the Sarygamysh posit-compliant co-processor 
derived from the Hwacha vector-thread co-processor.

To use this coprocessor, include this repo as a git submodule 
and add it to your chip's build.scala as a Project, e.g.

```
lazy val sarygamysh = Project("sarygamysh", file("sarygamysh"), settings = buildSettings)
```

Sarygamysh depends on the Chisel and Posit projects, make sure the jars
of these libraries are installed.

