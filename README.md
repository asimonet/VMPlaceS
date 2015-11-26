# VMPlaceS

This repository contains the source of a dedicated framework to evaluate and compare VM placement algorithms.

For more details, a deeper scientific description of the project can be found here: [http://beyondtheclouds.github.io/VMPlaceS](http://beyondtheclouds.github.io/VMPlaceS)

## Requirements
* sbt
* java 8 : **openjdk-8**
* python
* r language (optional: visualisation)

## Installation

Clone this branch using

```
git clone -b groupeA https://github.com/BeyondTheClouds/VMPlaceS.git
```

### 1- Simgrid

#### 1.1- Get Simgrid

```
git clone git://scm.gforge.inria.fr/simgrid/simgrid.git
```

#### 1.2- Compile and install Simgrid
In the simgrid folder, run the following:

```
cmake -Denable_tracing=ON -Denable_documentation=OFF -Denable_java=ON -Denable_smpi=OFF .
```
and then

```
make 
```
please note that you can compile the src code faster by using -j argument of make command (man make for further information)

```
make install
```
file named **simgrid.jar**, containing java bindings to simgrid should be located in the simgrid folder:


```
jonathan@artoo ~/simgrid (master)> ls -lh *.jar
-rw-r--r--  1 jonathan  staff    43K Nov  4 17:28 simgrid.jar
```

This file will be used during step **2.2**.

### 2- SBT

#### 2.1- Installation of sbt:

Please follow the instructions corresponding to your operating system: [http://www.scala-sbt.org/release/tutorial/Setup.html](http://www.scala-sbt.org/release/tutorial/Setup.html) .

#### 2.2- Installation of dependencies

Inside the project source folder, run the following:

```
$ sbt update
```

and then copy the **simgrid.jar** from **step 1.2** in the lib folder


### 3- Running the program with IntelliJ

#### 3.1- Make the project compatible with IntelliJ

Inside the project source folder, run the following:

```
$ sbt gen-idea
```

and open the folder in intelliJ: a fully configured project has been generated.

#### 3.2- Set the configuration

Click on **Run > Edit configurations...** and then the **plus (+)** button in the top left corner. Choose **Application**.

Name it as you wish, for instance _VMPlaceS_, and set the options:

Option Type             | Value
------------------------|-------------
Main class              | simulation.Main
VM_OPTIONS              | -Xmx4G -d64 -Dlogback.configurationFile=config/logback.xml
PROGRAM_ARGUMENTS       | ./config/cluster_platform.xml ./config/generated_deploy.xml  --cfg=cpu/optim:Full --cfg=tracing:1  --cfg=tracing/filename:simu.trace --cfg=tracing/platform:1
Use classpath of module | VMPlaceS

#### 3.3- Run the program

Set the algorithm you want to test in the property `simulator.implementation` in the `simulator.properties` file.
Click **Run > Run 'VMPlaceS'**.