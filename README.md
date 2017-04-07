accrue-bytecode
===============

An inter-procedural analysis framework built on top of WALA (https://github.com/wala/WALA).

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

Setting up IntelliJ
--------------------
The following configuration should be done under *File,  Project Structure*. The WALA and SCandroid
directories should be accessible.

1. Under the *Modules* sidebar, add four modules: `com.ibm.wala.core`, `com.ibm.wala.shrike`,
`com.ibm.wala.shrike`, `SCandroid`, navigating to the appropriate directories for each. 

2. Modify the `SCandroid` module under the *Dependencies* tab. Double-click "WALA" and only keep the
following .jars (*removing* all others -- having extraneous jars here will cause typing errors).
  * wala\_cast.jar
  * wala\_cast\_java.jar
  * wala\_cast\_java\_jdt.jar
  * wala\_core\_tests.jar
  * wala\_ide.jar

3. Modify the `SCandroid` module under the *Dependencies* tab. Click the + button in the bottom.
Add the `core`, `shrike`, `util` modules as Module *Dependencies*.

4. Modify the `accrue-bytecode` module under the *Dependencies* tab. Click the + button in the bottom.
Add the `core`, `shrike`, `util`, `SCandroid` modules as *Module Dependencies*.

5. Modify the `accrue-bytecode` module under the Sources tab. Find
`accrute-bytecode/generated-sources` and click *Mark as: Sources*.

6. Under the *Libraries* sidebar, click the + symbol and add all of the jar files under
`accrue-bytecode/lib`. Then add `junit-4.10.jar`, which is located in the `lib/` subdirectory of
the IntelliJ applications folder.

The dependencies are somewhat delicate, so make sure you are not including extra dependencies (only
the `core`, `shrike`, `util` modules, only import the listed jars for `SCandroid`). 
