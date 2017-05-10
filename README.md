accrue-bytecode
===============

An inter-procedural analysis framework built on top of [WALA](https://github.com/wala/WALA).


Accrue Interprocedural Analysis Framework
-----------------------------------------

[http://people.seas.harvard.edu/~chong/accrue.html](http://people.seas.harvard.edu/~chong/accrue.html)

The Accrue Interprocedural Analysis Framework (Accrue) is a framework
for interprocedural analysis of Java bytecode, implemented on top of
WALA.  Accrue contains some common and useful analyses, such as a
non-null analysis and a precise exception tracker. More importantly,
it contains sufficient building blocks to make it easy to write new
interprocedural analyses.

Disclaimer
----------

Accrue is research software. Over time, some of the interfaces in the
framework may change.  This may require some clients of the framework
to be changed to conform to the new interfaces.  Also, Accrue is not
well documented.  If you use Accrue, we'd appreciate you letting us
know. Please send comments and bug reports to Stephen Chong at
chong@seas.harvard.edu.


Building with Maven
-------------------

$ mvn clean install

This should be sufficient to build accrue-bytecode with Maven. This will
download all third-party JARs necessary to build and run accrue-bytecode
(incling WALA) and perform a full rebuild.

The 'install' goal will also copy the library to the local Maven repository
(one that is private to your home directory), allowing it to be accessed by
other tools (such as Pidgin). After the build all the compiled class files
(and a JAR) will be in the 'target' subdirectory.

During normal development you can omit the 'clean' step (to avoid
unnecessary recompilation).


