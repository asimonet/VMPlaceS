    /**
     * Copyright 2012-2013-2014. The SimGrid Team. All rights reserved.
     *
     * This program is free software; you can redistribute it and/or modify it
     * under the terms of the license (GNU LGPL) which comes with this package.
     *
     * This class aims at controlling the interactions between the different components of the injector simulator.
     * It is mainly composed of static methods. Although it is rather ugly, this is the direct way to make a kind of
     * singleton ;)
     *
     * @author adrien.lebre@inria.fr
     * @contributor jsimao@cc.isel.ipl.pt
     */

    package simulation;

    import configuration.*;
    import injector.Injector;
    import org.apache.commons.io.FileUtils;
    import org.simgrid.msg.*;
    import org.simgrid.msg.Process;
    import scheduling.hierarchical.snooze.LocalController;
    import scheduling.hierarchical.snooze.Logger;
    import trace.Trace;

    import java.io.*;
    import java.io.File;
    import java.nio.file.Files;
    import java.nio.file.Paths;
    import java.nio.file.StandardOpenOption;
    import java.util.*;

    /**
     * Created with IntelliJ IDEA.
     * User: alebre
     * Date: 14/01/14
     * Time: 10:49
     * To change this template use File | Settings | File Templates.
     */
    public class SimulatorManager extends Process{
        public static int iSuspend = 0;
        public static int iResume = 0;

        /**
         * Stupid variable to monitor the duration of the simulation
         */
        private static double beginTimeOfSimulation = -1;
        /**
         * Stupid variable to monitor the duration of the simulation
         */
        private static double endTimeOfSimulation = -1;

        /**
         * The list of XVMs that are considered as off (i.e. the hosting machine is off)
         * @see configuration.XVM
         */
        private static HashMap<String,XVM> sgVMsOff = null;

        /**
         * The list of XVMs that run
         * @see configuration.XVM
         */
        private static HashMap<String,XVM> sgVMsOn = null;

        /**
         * The list of XVMs that should be suspend (right now, it is impossible to suspend VMs that are currently migrated)
         * This collection is used to suspend VMs after the completion of the migration process.
         * @see configuration.XVM
         */
        public static HashMap<String,XVM> sgVMsToSuspend = null;

        /**
         * The list of XHosts that are off
         * @see configuration.XHost
         */
        private static HashMap<String,XHost> sgHostsOff= null;
        /**
         * The list of Xhosts  that are running
         */
        private static HashMap<String,XHost> sgHostsOn= null;

        /**
         * The list of all XHosts
         * @see configuration.XHost
         */
        private static HashMap<String,XHost> sgHostingHosts= null;
        /**
         * The list of Xhosts  that are running
         */
        private static HashMap<String, XHost> sgServiceHosts= null;
        /**
         * Just a stupid sorted table to have a reference toward each host and vm
         * Used by the injector when generating the different event queues.
         */
        private static XHost[] xhosts = null;
        private static XVM[] xvms = null;
        /**
         * Average CPU demand of the infrastructure (just a hack to avoid to compute the CPUDemand each time (computing the CPU demand is O(n)
         */
        // TODO Adrien the currentCPUDemand is currently not correctly assigned (this should be done in the update function)
        private static double currentCPUDemand = 0;

        /**
         * The previous energy consumption
         */
        private static Map<XHost, Double> lastEnergy = new HashMap<>();

        /**
         * Reference toward the scheduler
         */
        private static boolean isSchedulerActive;

        public static boolean isSchedulerActive() {
            return isSchedulerActive;
        }

        public static void setSchedulerActive(boolean val) {
            isSchedulerActive=val;
        }

        /**
         * Set the scheduler
         */

        /**
         * When the injection is complete, we turn the endOfInjection boolean to true and kill the running daemon inside each VM
         */
        public static void setEndOfInjection() {
            endTimeOfSimulation = System.currentTimeMillis();
        }

        public static void finalizeSimulation(){
            Msg.info(String.format("Hosts up: %d/%d", sgHostsOn.size(), getSGHosts().size()));
            Msg.info(String.format("VMs up: %d/%d", sgVMsOn.size(), getSGVMs().size()));

            for (XHost host : SimulatorManager.getSGHosts()) {
                Msg.info(host.getName() + " has been turned off "+host.getTurnOffNb()+" times and violated "+host.getNbOfViolations());
            }

            // Kill all VMs daemons in order to finalize the simulation correctly
            int nvm = SimulatorManager.getSGVMs().size();
            int i = 0;
            for (XVM vm : SimulatorManager.getSGVMs()) {
                Msg.info(String.format("[%d/%d] %s load changes: %d/ migrated: %d",
                        ++i,
                        nvm,
                        vm.getName(),
                        vm.getNbOfLoadChanges(),
                        vm.getNbOfMigrations()));
                if(vm.isRunning()) {
                    Msg.info("VM is running");
                    Msg.info("VM is migrating: " + vm.isMigrating());
                    //Msg.info("Daemon is suspended: " + vm.getDaemon().isSuspended());
                    //vm.getDaemon().kill();
                }
            }
            Msg.info("Duration of the simulation in ms: "+(endTimeOfSimulation - beginTimeOfSimulation));
            Trace.close();
        }

        /**
         * @return whether the injection is completed or not
         */
        public static boolean isEndOfInjection(){
            return (endTimeOfSimulation != -1);
        }


        /**
         * @return the collection of XVMs: all VMs, the running and the ones that are considered as dead
         * (i.e. hosted on hosts that have been turned off)
         */
        public static Collection<XVM> getSGVMs(){
            LinkedList<XVM> tmp = new LinkedList<>(sgVMsOn.values());
            tmp.addAll(sgVMsOff.values());
            return tmp;
        }

        /**
         * @return the collection of running XVMs
         */
        public static Collection<XVM> getSGVMsOn(){
            return sgVMsOn.values();
        }

        /**
         * @return the collection of the XVMs considered as dead
         */
        public static Collection<XVM> getSGVMsOff(){
            return sgVMsOff.values();
        }


        /**
         * @return the collection of XHosts (i.e. the hosts that composed the infrastructure).
         * Please note that the returned collection is not sorted. If you need a sorted structure, you should call getSGHostsToArray() that returns an simple array
         */
        public static Collection<XHost> getSGHosts(){
            LinkedList<XHost> tmp = new LinkedList<XHost>(sgHostingHosts.values());
            tmp.addAll(sgServiceHosts.values());

            return tmp;
        }

        /**
         * @return the collection of XHosts (i.e. the hosts that composed the infrastructure).
         * Please note that the returned collection is not sorted. If you need a sorted structure, you should call getSGHosts() that returns an simple array
         */
        public static XHost[] getSGHostsToArray(){
            return xhosts;
        }

        public static XVM[] getSGVMsToArray() {
            return xvms;
        }

        /**
         * @return the collection of XHosts that have been declared as hosting nodes (i.e. that can host VMs)
         * Please note that all HostingHosts are returned (without making any distinctions between on and off hosts)
         */
        public static Collection<XHost> getSGHostingHosts(){
            return sgHostingHosts.values();
        }

        /**
         * @return the collection of XHosts that have been declared as hosting nodes (i.e. that can host VMs) and that are turned on.
         */
        public static Collection<XHost> getSGTurnOnHostingHosts() {
            LinkedList<XHost> tmp = new LinkedList<XHost>();
            for (XHost h: sgHostingHosts.values())
                if (!h.isOff())
                    tmp.add(h);

            return tmp;
        }

        public static Collection<XHost> getSGTurnOffHostingHosts() {
            LinkedList<XHost> tmp = new LinkedList<XHost>();
            for(XHost h: sgHostingHosts.values())
                if(h.isOff())
                    tmp.add(h);

            return tmp;
        }

        /**
         * @return the collection of XHosts that have been declared as services nodes (i.e. that cannot host VMs)
         */
        public static Collection<XHost> getSGServiceHosts(){
            return sgServiceHosts.values();
        }


        /**
         * @return the name of the service node (generally node0, if you do not change the first part of the main regarding the generation
         * of the deployment file).
         * If you change it, please note that you should then update the getInjectorNodeName code.
         */
        public static String getInjectorNodeName() {
            return "node"+(SimulatorProperties.getNbOfHostingNodes()+SimulatorProperties.getNbOfServiceNodes());
        }

        /**
         * For each MSG host (but the service node), the function creates an associated XHost.
         * As a reminder, the XHost class extends the Host one by aggregation.
         * At the end, all created hosts have been inserted into the sgHosts collection (see getSGHostingHosts function)
         * @param nbOfHostingHosts the number of hosts that will be used to host VMs
         * @param nbOfServiceHosts the number of hosts that will be used to host services
         */
        public static void initHosts(int nbOfHostingHosts, int nbOfServiceHosts){
            // Since SG does not make any distinction between Host and Virtual Host (VMs and Hosts belong to the Host SG table)
            // we should retrieve first the real host in a separated table
            // Please remind that node0 does not host VMs (it is a service node) and hence, it is managed separately (getInjectorNodeName())
            sgHostsOn = new HashMap<String,XHost>();
            sgHostsOff = new HashMap<String,XHost>();
            sgHostingHosts = new HashMap<String,XHost>();
            sgServiceHosts = new HashMap<String,XHost>();
            xhosts = new XHost[nbOfHostingHosts+nbOfServiceHosts];

            XHost xtmp;

            // Hosting hosts
            for(int i = 0 ; i < nbOfHostingHosts ; i ++){
                try {
                    Host tmp = Host.getByName("node" + i);
                    // The SimulatorProperties.getCPUCapacity returns the value indicated by nodes.cpucapacity in the simulator.properties file
                    xtmp = new XHost (tmp, SimulatorProperties.getMemoryTotal(), SimulatorProperties.getNbOfCPUs(), SimulatorProperties.getCPUCapacity(), SimulatorProperties.getNetCapacity(), "127.0.0.1");
                    xtmp.turnOn();
                    sgHostsOn.put("node"+i, xtmp);
                    sgHostingHosts.put("node" + i, xtmp);
                    xhosts[i]=xtmp;
                } catch (HostNotFoundException e) {
                    e.printStackTrace();
                }
            }

            //Service hosts
            for(int i = nbOfHostingHosts ; i < nbOfHostingHosts+nbOfServiceHosts ; i ++){
                try {
                    Host tmp = Host.getByName("node" + i);
                    // The SimulatorProperties.getCPUCapacity returns the value indicated by nodes.cpucapacity in the simulator.properties file
                    xtmp = new XHost (tmp, SimulatorProperties.getMemoryTotal(), SimulatorProperties.getNbOfCPUs(), SimulatorProperties.getCPUCapacity(), SimulatorProperties.getNetCapacity(), "127.0.0.1");
                    xtmp.turnOn();
                    sgHostsOn.put("node" + i, xtmp);
                    sgServiceHosts.put("node" + i, xtmp);
                    xhosts[i]=xtmp;
                } catch (HostNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * Create and assign the VMs on the different hosts.
         * For the moment, the initial placement follows a simple round robin strategy
         * The algorithm fill the first host with the n first VMs until it reaches either the memory limit, then it switches to the second host and so on.
         * Note that if the ''balance'' mode is enabled then the initial placement will put the same number of VMs on each node.
         * The function can crash if there are two many VMs for the physical resources.
         * At the end the collection SimulatorManager.getSGVMs() is filled.
         * @param nbOfHostingHosts the number of the hosts composing the infrastructure
         * @param nbOfServiceHosts the number of the hosts composing the infrastructure
         * @param nbOfVMs the number of the VMs to instanciate
         */
        public static void configureHostsAndVMs(int nbOfHostingHosts, int nbOfServiceHosts, int nbOfVMs, boolean balance) {
            int nodeIndex = 0;
            int[] nodeMemCons = new int[nbOfHostingHosts];
            int[] nodeCpuCons = new int[nbOfHostingHosts];
            int vmIndex= 0;
            int nbVMOnNode;
            Random r = new Random(SimulatorProperties.getSeed());
            int nbOfVMClasses = VMClasses.CLASSES.size();
            VMClasses.VMClass vmClass;

            initHosts(nbOfHostingHosts, nbOfServiceHosts);
            sgVMsOn = new HashMap<String,XVM>();
            sgVMsOff = new HashMap<String,XVM>();
            sgVMsToSuspend = new HashMap<String,XVM>();


            xvms = new XVM[nbOfVMs];

            XVM sgVMTmp;

            Iterator<XHost> sgHostsIterator = SimulatorManager.getSGHostingHosts().iterator();

            XHost sgHostTmp = sgHostsIterator.next();
            nodeMemCons[nodeIndex]=0;
            nodeCpuCons[nodeIndex]=0;
            nbVMOnNode =0;

            //Add VMs to each node, preventing memory over provisioning
            while(vmIndex < nbOfVMs){

                // Select the class for the VM
                vmClass = VMClasses.CLASSES.get(r.nextInt(nbOfVMClasses));

                //Check whether we can put this VM on the current node if not get the next one
                //The first condition controls the memory over provisioning issue while the second one enables to switch to
                // the next node if the ''balance'' mode is enabled.
                // If there is no more nodes, then we got an exception and the simulator.properties should be modified.

                double vmsPerNodeRatio = ((double) nbOfVMs)/nbOfHostingHosts;

                try {
                    while ((nodeMemCons[nodeIndex] + vmClass.getMemSize() > sgHostTmp.getMemSize()
                            || nodeCpuCons[nodeIndex] + SimulatorProperties.getMeanLoad() > sgHostTmp.getCPUCapacity())
                            || (balance && nbVMOnNode >= vmsPerNodeRatio)) {
                        sgHostTmp = sgHostsIterator.next();
                        nodeMemCons[++nodeIndex] = 0;
                        nodeCpuCons[nodeIndex] = 0;
                        nbVMOnNode = 0;
                    }
                } catch(NoSuchElementException ex){
                    System.err.println("There is not enough memory on the physical hosts to start all VMs");
                    System.err.println(String.format("Number of hosts: %d", nbOfHostingHosts));
                    System.err.println(String.format("Number of VMs: %d", nbOfVMs));
                    System.err.println(String.format("VM placed: %d", vmIndex));
                    System.err.println("(Please fix simulator.properties parameters and you should dive in the SimulatorManager.configureHostsAndVMs() function");
                    System.exit(1);
                }

                // Creation of the VM
                sgVMTmp = new XVM(sgHostTmp, "vm-" + vmIndex,
                        vmClass.getNbOfCPUs(), vmClass.getMemSize(), vmClass.getNetBW(), null, -1, vmClass.getMigNetBW(), vmClass.getMemIntensity());
                sgVMsOn.put("vm-"+vmIndex, sgVMTmp);

                xvms[vmIndex] = sgVMTmp;
                vmIndex++;

                Msg.info(String.format("vm: %s, %d, %d, %s",
                        sgVMTmp.getName(),
                        vmClass.getMemSize(),
                        vmClass.getNbOfCPUs(),
                        "NO IPs defined"
                ));
                Msg.info("vm " + sgVMTmp.getName() + " is " + vmClass.getName() + ", dp is " + vmClass.getMemIntensity());

                // Assign the new VM to the current host.
                sgHostTmp.start(sgVMTmp);     // When the VM starts, its getCPUDemand equals 0
                nbVMOnNode ++;
                nodeMemCons[nodeIndex] += sgVMTmp.getMemSize();
                nodeCpuCons[nodeIndex] += SimulatorProperties.getMeanLoad();
            }
        }

        /**
         * write the current configuration in the ''logs/simulatorManager/'' directory
         */
        public static void writeCurrentConfiguration(){
            try {
                File file = new File("logs/simulatorManager/conf-"+ System.currentTimeMillis() + ".txt");
                file.getParentFile().mkdirs();
                BufferedWriter bw = new BufferedWriter(new FileWriter(file));
                for (XHost h: SimulatorManager.getSGHostingHosts()){
                    bw.write(String.format("%s (%s) (%.2f/%d, %d/%d):",
                            h.getName(),
                            (h.isOn()? "on":"off"),
                            h.getCPUDemand(),
                            h.getCPUCapacity(),
                            h.getMemDemand(),
                            h.getMemSize()));
                    for (XVM vm: h.getRunnings()){
                        bw.write(" "+vm.getName());
                    }
                    bw.write("\n");
                    bw.flush();
                }

                bw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        /**
         * Remove all logs from the previous run
         */
        public static void cleanLog(){
            try {
                FileUtils.deleteDirectory(new File("logs"));;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /**
         * @return whether the current placement is viable or not (i.e. if every VM gets its expectations).
         * Please note that we are considering only the hosts that are running.
         * Complexity O(n)
         */
        public static boolean isViable() {
            for (XHost h: sgHostsOn.values()){
                if(!h.isViable())
                    return false;
            }
            return true;
        }

        /**
         * @return the average expected load at a particular moment (i.e. average load of each node)
         * Please note that we are considering only the hosts hosting VMs and that are up.
         */
        public static double computeCPUDemand() {

            double globalCpuDemand = 0.0;
            int globalCpuCapacity = 0;

            for(XHost h: sgHostingHosts.values()){
                if(h.isOn()) {
                    globalCpuDemand += h.getCPUDemand();
                    globalCpuCapacity += h.getCPUCapacity();
                }
            }
            return 100 * globalCpuDemand / globalCpuCapacity;
        }

        public static double getCPUDemand(){
            // TODO Adrien, maintain the current CPU Demand in order to avoid O(n)
            //return currentCPUDemand;
            return computeCPUDemand();
        }
        /**
         * @return the number of hosts that are active (i.e. that host at least one VM)
         * Complexity O(n)
         */
        public static int getNbOfUsedHosts() {
            int i=0;
            for (XHost h: sgHostsOn.values()){
                if(h.getNbVMs()>0)
                    i++;
            }
            return i;
        }

        /**
         * Return the XHost entitled ''name'', if not return null (please note that the search is performed by considering
         * all hosts (i.e. On/Off and Hosting/Service ones)
         * @param name the name of the host requested
         * @return the corresponding XHost instance (null if there is no corresponding host in the sgHosts collection)
         */
        public static XHost getXHostByName(String name) {
            XHost tmp = sgHostingHosts.get(name);
            if (tmp == null)
                tmp = sgServiceHosts.get(name);
            return tmp;
        }

        /**
         * Return the XVM entitled ''name'', if not return null please note that the search is performed by considering
         * all VMs (i.e. event the off ones)
         * @param name the name of the vm requested
         * @return the corresponding XVM instance (null if there is no corresponding vm in the sgVMs collection)
         */
        public static XVM getXVMByName(String name) {
            XVM tmp = sgVMsOn.get(name);
            if (tmp == null)
                tmp = sgVMsOff.get(name);
            if(tmp == null)
                tmp = sgVMsToSuspend.get(name);


            if(tmp == null) {
                Msg.error("No ");
            }
            return tmp;
        }

        /**
         * Change the load of a VM.
         * Please note that we do not detect violations on off hosts (i.e. if the nodes that hosts the VM is off, we change
         * the load of the vm for consistency reasons but we do not consider the violation that may state from this change).
         * @param sgVM the VM that should be updated
         * @param load the new expected load
         */
        public static void updateVM(XVM sgVM, double load) {

            if(sgVM.isRunning()) {

                XHost host = sgVM.getLocation();
                boolean previouslyViable = host.isViable();

                sgVM.setLoad(load);
                host.setCPUDemand(host.computeCPUDemand());

                // If the node is off, we change the VM load but we do not consider it for possible violation and do not update
                // neither the global load of the node nor the global load of the cluster.
                // Violations are detected only on running node
                if (!host.isOff()) {


                    //    Msg.info("Current getCPUDemand "+SimulatorManager.getCPUDemand()+"\n");


                    if (previouslyViable && (!host.isViable())) {
                        Msg.info("STARTING VIOLATION ON " + host.getName() + "\n");
                        host.incViolation();
                        Trace.hostSetState(host.getName(), "PM", "violation");

                    } else if ((!previouslyViable) && (host.isViable())) {
                        Msg.info("ENDING VIOLATION ON " + host.getName() + "\n");
                        Trace.hostSetState(host.getName(), "PM", "normal");
                    }
                    // else Do nothing the state does not change.

                    // Update getCPUDemand of the host
                    Trace.hostVariableSet(host.getName(), "LOAD", host.getCPUDemand());

                    // TODO this is costly O(HOST_NB) - SHOULD BE FIXED
                    //Update global getCPUDemand
                    Trace.hostVariableSet(SimulatorManager.getInjectorNodeName(), "LOAD", SimulatorManager.getCPUDemand());

                }

                double energy = host.getSGHost().getConsumedEnergy();
                if (lastEnergy.containsKey(host))
                    energy -= lastEnergy.get(host);

                Trace.hostVariableSet(host.getName(), "ENERGY", energy);
                lastEnergy.put(host, host.getSGHost().getConsumedEnergy());
            } else { // VM is suspended: just update the load for consistency reason (i.e. when the VM will be resumed, we should assign the expected load
                sgVM.setLoad(load);
            }
        }

        public static boolean willItBeViableWith(XVM sgVM, int load){
            XHost tmpHost = sgVM.getLocation();
            double hostPreviousLoad = tmpHost.getCPUDemand();
            double vmPreviousLoad = sgVM.getCPUDemand();
            return ((hostPreviousLoad-vmPreviousLoad+load) <= tmpHost.getCPUCapacity());
        }

        /**
         * Turn on the XHost host
         * @param host the host to turn on
         */
        public static void turnOn(XHost host) {
            String name = host.getName();
            if(host.isOff()) {
                Msg.info("Turn on node "+name);
                host.turnOn();
                Trace.hostVariableAdd(host.getName(), "NB_ON", 1);
                sgHostsOff.remove(name);
                sgHostsOn.put(name, host);

                // If your turn on an hosting node, then update the LOAD
                if(sgHostingHosts.containsKey(name)) {

                    for (XVM vm: host.getRunnings()){
                        Msg.info("TURNING NODE "+name+"ON - ADD VM "+vm.getName());
                        sgVMsOff.remove(vm.getName());
                        sgVMsOn.put(vm.getName(), vm);
                    }

                    // Update getCPUDemand of the host
                    Trace.hostVariableSet(name, "LOAD", host.getCPUDemand());

                    // TODO test whether the node is violated or not (this can occur)

                    //Update global getCPUDemand
                    Trace.hostVariableSet(SimulatorManager.getInjectorNodeName(), "LOAD", SimulatorManager.getCPUDemand());
                }
                if (SimulatorProperties.getAlgo().equals("hierarchical")) {
                    int hostNo = Integer.parseInt(name.replaceAll("\\D", ""));
                    if (hostNo < SimulatorProperties.getNbOfHostingNodes()) {
                        try {
                            String[] lcArgs = new String[]{name, "dynLocalController-" + hostNo};
                            LocalController lc =
                                    new LocalController(host.getSGHost(), "dynLocalController-" + hostNo, lcArgs);
                            lc.start();
                            Logger.info("[SimulatorManager.turnOn] Dyn. LC added: " + lcArgs[1]);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            } else{
                Msg.info("Weird... you are asking to turn on a host that is already on !");
            }
        }

        /**
         * Turn off the XHost host
         * @param host the host to turn off
         */
        public static void turnOff(XHost host) {

            if(host.isOnGoingMigration()){
                Msg.info("WARNING = WE ARE NOT GOING TO TURN OFF HOST "+host.getName()+" BECAUSE THERE IS AN ON-GOING MIGRATION");
                return;
            }
            if(!host.isOff()) {
                Msg.info("Turn off "+host.getName());

                // if this is an hosting host, then you should deal with VM aspects
                if(sgHostingHosts.containsKey(host.getName())) {
                    // First remove all VMs hosted on the node from the global collection
                    // The VMs are still referenced on the node
                    for (XVM vm : host.getRunnings()) {
                        Msg.info("TURNING NODE "+host.getName()+"OFF - REMOVE VM "+vm.getName());
                        sgVMsOn.remove(vm.getName());
                        sgVMsOff.put(vm.getName(), vm);
                    }
                    // Update getCPUDemand of the host
                    Trace.hostVariableSet(host.getName(), "LOAD", 0);

                    // TODO if the node is violated then it is no more violated
                    //Update global getCPUDemand
                    Trace.hostVariableSet(SimulatorManager.getInjectorNodeName(),  "LOAD", SimulatorManager.getCPUDemand());

                }


                int previousCount = org.simgrid.msg.Process.getCount();
                // Turn the node off
                host.turnOff();

                // Finally, remove the node from the collection of running host and add it to the collection of off ones
                sgHostsOn.remove(host.getName());
                sgHostsOff.put(host.getName(), host);

                //  Msg.info("Nb of remaining processes on " + host.getName() + ": " + (previousCount - org.simgrid.msg.Process.getCount()));
                Trace.hostVariableAdd(host.getName(), "NB_OFF", 1);


            }
            else{
                Msg.info("Weird... you are asking to turn off a host that is already off !");
            }
        }


        private static int getProcessCount(XHost host) {
            Msg.info ("TODO");
            System.exit(-1);
            return -1;

        }


        /**
         * Stupid variable to monitor the duration of the simulation
         */
        public static void setBeginTimeOfSimulation(double beginTimeOfSimulation) {
            SimulatorManager.beginTimeOfSimulation = beginTimeOfSimulation;
        }

        /**
         * Stupid variable to monitor the duration of the simulation
         */
        public static void setEndTimeOfSimulation(double endTimeOfSimulation) {
            SimulatorManager.endTimeOfSimulation = endTimeOfSimulation;
        }

        /**
         * Stupid variable to monitor the duration of the simulation
         */
        public static double getSimulationDuration() {
            return (endTimeOfSimulation != -1) ?  endTimeOfSimulation - beginTimeOfSimulation : endTimeOfSimulation;
        }

        public static void writeEnergy(String logPath) {
            Double energy = 0D;
            for(XHost h: SimulatorManager.getSGHosts())
                energy += h.getSGHost().getConsumedEnergy();

            try {
                String message = null;
                if(SimulatorProperties.getAlgo().equals("centralized")) {
                    String implem = SimulatorProperties.getImplementation();
                    implem = implem.substring(implem.lastIndexOf('.') + 1, implem.length());

                    if(SimulatorProperties.getImplementation().startsWith("scheduling.centralized.ffd"))
                        message = String.format(Locale.US, "%d %s %s %b %d %f\n",
                                SimulatorProperties.getNbOfHostingNodes(),
                                SimulatorProperties.getAlgo(),
                                implem,
                                SimulatorProperties.getHostsTurnoff(),
                                SimulatorProperties.getFfdThreshold(),
                                energy);
                    else
                        message = String.format(Locale.US, "%d %s %s %b %f\n",
                                SimulatorProperties.getNbOfHostingNodes(),
                                SimulatorProperties.getAlgo(),
                                implem,
                                SimulatorProperties.getHostsTurnoff(),
                                energy);
                    }
                else
                    message = String.format(Locale.US, "%d %s %b %f\n", SimulatorProperties.getNbOfHostingNodes(), SimulatorProperties.getAlgo(), SimulatorProperties.getHostsTurnoff(), energy);

                Msg.info("Total energy: " + message);
                if(logPath != null) {
                    Files.write(Paths.get(logPath), message.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    Msg.info("Wrote total energy consumption to " + logPath);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }



        public static boolean suspendVM(String vmName, String hostName){

            boolean correctlyCompleted= true;
            Msg.info("Suspending VM " + vmName + " on " + hostName);

            if (vmName != null) {
                XVM vm =  SimulatorManager.getXVMByName(vmName);
                XHost host = SimulatorManager.getXHostByName(hostName);

                if (vm != null) {
                    double timeStartingSuspension = Msg.getClock();
                    Trace.hostPushState(vmName, "SERVICE", "suspend", String.format("{\"vm_name\": \"%s\", \"on\": \"%s\"}", vmName, hostName));
                    boolean previouslyViable = host.isViable();
                    // 0 if success, 1 should be postponed, -1 if failure, -2 if already suspended
                    int res = host.suspendVM(vm);
                    Trace.hostPopState(vmName, "SERVICE", String.format("{\"vm_name\": \"%s\", \"state\": %d}", vmName, res));
                    double suspensionDuration = Msg.getClock() - timeStartingSuspension;

                    switch (res) {
                        case 0:
    //                        Msg.info("End of suspension operation of VM " + vmName + " on " + hostName);

                            if (!previouslyViable && host.isViable()){
                                Msg.info("END OF VIOLATION ON " + host.getName() + "\n");
                                Trace.hostSetState(host.getName(), "PM", "normal");
                            }

                            SimulatorManager.iSuspend++;

                            /* Export that the suspension has finished */
                            Trace.hostSetState(vmName, "suspension", "finished", String.format(Locale.US, "{\"vm_name\": \"%s\", \"on\": \"%s\", \"duration\": %f}", vmName, hostName, suspensionDuration));
                            Trace.hostPopState(vmName, "suspension");

                            if (sgVMsOn.remove(vm.getName()) == null && sgVMsToSuspend.remove(vm.getName())== null){
                                System.err.println("You are trying to suspend a VM which is not on... weird");
                                System.exit(-1);
                            }
                            sgVMsOff.put(vm.getName(), vm);
                            Trace.hostVariableSub(SimulatorManager.getInjectorNodeName(), "NB_VM", 1);
                            break;

                        case 1:
                            Msg.info("Suspension of VM has been postponed" + vmName + " on " + hostName);

                            Trace.hostSetState(vmName, "suspension", "postponed", String.format(Locale.US, "{\"vm_name\": \"%s\", \"on\": \"%s\", \"duration\": %f}", vmName, hostName, suspensionDuration));
                            Trace.hostPopState(vmName, "suspension");

                            sgVMsOn.remove(vm.getName());
                            sgVMsToSuspend.put(vm.getName(), vm);
                            break;

                        default:
                            correctlyCompleted = false;
                            System.err.println("Unexpected state from XHost.suspend()");
                            System.exit(-1);
                    }
                }

            } else {
                System.err.println("You are trying to suspend a non-existing VM");
                System.exit(-1);
            }
            return correctlyCompleted;
        }

        public static boolean resumeVM(String vmName, String hostName){

            boolean correctlyCompleted = true;

            Msg.info("Resuming VM " + vmName + " on " + hostName);

            if (vmName != null) {
                XVM vm =  SimulatorManager.getXVMByName(vmName);
                XHost host = SimulatorManager.getXHostByName(hostName);

                if (vm != null) {
                    double timeStartingSuspension = Msg.getClock();
                    Trace.hostPushState(vmName, "SERVICE", "resume", String.format("{\"vm_name\": \"%s\", \"on\": \"%s\"}", vmName, hostName));
                    boolean previouslyViable = host.isViable();
                    // 0 if success, -1 if failure, 1 if already running
                    int res = host.resumeVM(vm);
                    Msg.info(vm.getName() + " resume returned " + res);
                    Trace.hostPopState(vmName, "SERVICE", String.format("{\"vm_name\": \"%s\", \"state\": %d}", vmName, res));
                    double suspensionDuration = Msg.getClock() - timeStartingSuspension;

                    switch (res) {
                        case 0:
    //                        Msg.info("End of operation resume of VM " + vmName + " on " + hostName);

                            if (sgVMsOff.remove(vmName) == null)  { // If the VM is not marked off, there is an issue
                                System.err.println("Unexpected state from XHost.resume()");
                                System.exit(-1);
                            }
                            sgVMsOn.put(vm.getName(), vm);
                            Trace.hostVariableAdd(SimulatorManager.getInjectorNodeName(), "NB_VM", 1);
                            SimulatorManager.iResume++;

                            if ((previouslyViable) && (!host.isViable())) {
                                Msg.info("STARTING VIOLATION ON " + host.getName() + "\n");
                                Trace.hostSetState(host.getName(), "PM", "violation");
                            }

                            Trace.hostSetState(vmName, "resume", "finished", String.format(Locale.US, "{\"vm_name\": \"%s\", \"on\": \"%s\", \"duration\": %f}", vmName, hostName, suspensionDuration));
                            Trace.hostPopState(vmName, "resume");

                            break;

                        case 1:
                            if(sgVMsToSuspend.remove(vmName) == null) { // If the VM is not marked off, there is an issue
                                System.err.println("Unexpected state from XHost.resume()");
                                System.exit(-1);
                            }
                            sgVMsOn.put(vm.getName(), vm);
                            //SimulatorManager.iResume++;

                            /* Export that the suspension has finished */
                            Trace.hostSetState(vmName, "resume", "cancelled", String.format(Locale.US, "{\"vm_name\": \"%s\", \"on\": \"%s\", \"duration\": %f}", vmName, hostName, suspensionDuration));
                            Trace.hostPopState(vmName, "resume");


                            break;

                        default:
                            correctlyCompleted = false;
                            System.err.println("Unexpected state from XHost.resume()");
                            System.exit(-1);
                    }
                }

            } else {
                System.err.println("You are trying to resume a non-existing VM");
                System.exit(-1);
            }
            return correctlyCompleted;
        }

        /**
         * Migrate a VM
         * @param vmName
         * @param sourceName
         * @param destName
         * @return true migration has been correctly performed, false migration cannot complete.
         */


        public static boolean migrateVM(String vmName, String sourceName, String destName) {

            boolean completionOk = true;
            double timeStartingMigration = Msg.getClock();
            Trace.hostPushState(vmName, "SERVICE", "migrate", String.format("{\"vm_name\": \"%s\", \"from\": \"%s\", \"to\": \"%s\"}", vmName, sourceName, destName));

            XHost sourceHost = SimulatorManager.getXHostByName(sourceName);
            XHost destHost = SimulatorManager.getXHostByName(destName);

            int res = sourceHost.migrate(vmName, destHost);
            // TODO, we should record the res of the migration operation in order to count for instance how many times a migration crashes ?
            // To this aim, please extend the hostPopState API to add meta data information

            Trace.hostPopState(vmName, "SERVICE", String.format("{\"vm_name\": \"%s\", \"state\": %d}", vmName, res));
            double migrationDuration = Msg.getClock() - timeStartingMigration;

            if (res == 0) {
                Msg.info("End of migration of VM " + vmName + " from " + sourceName + " to " + destName);

                if (!destHost.isViable()) {
                    Msg.info("ARTIFICIAL VIOLATION ON " + destHost.getName() + "\n");
                    // If Trace.hostGetState(destHost.getName(), "PM").equals("normal")
                    Trace.hostSetState(destHost.getName(), "PM", "violation-out");
                }
                if (sourceHost.isViable()) {
                    Msg.info("END OF VIOLATION ON " + sourceHost.getName() + "\n");
                    Trace.hostSetState(sourceHost.getName(), "PM", "normal");
                }

                                        /* Export that the migration has finished */
                Trace.hostSetState(vmName, "migration", "finished", String.format(Locale.US, "{\"vm_name\": \"%s\", \"from\": \"%s\", \"to\": \"%s\", \"duration\": %f}", vmName, sourceName, destName, migrationDuration));
                Trace.hostPopState(vmName, "migration");

                // Patch to handle postponed supsend that may have been requested during the migration.
                XVM suspendedVm = SimulatorManager.sgVMsToSuspend.remove(vmName);
                if (suspendedVm != null) { // The VM has been marked to be suspended, so do it
                    Msg.info("The VM " + vmName + "has been marked to be suspended after migration");
                    SimulatorManager.sgVMsOn.put(vmName, suspendedVm);
                    SimulatorManager.suspendVM(vmName, destName);
                }

            } else {

                Trace.hostSetState(vmName, "migration", "failed", String.format(Locale.US, "{\"vm_name\": \"%s\", \"from\": \"%s\", \"to\": \"%s\", \"duration\": %f}", vmName, sourceName, destName, migrationDuration));
                Trace.hostPopState(vmName, "migration");

                Msg.info("Something was wrong during the migration of  " + vmName + " from " + sourceName + " to " + destName);
                Msg.info("Reconfiguration plan cannot be completely applied so abort it");
                completionOk = false;
            }
            return completionOk;
        }

        public SimulatorManager(Host host, String name, String[] args){
            super(host, name, args);

        }
        @Override
        public void main(String[] strings) throws MsgException {
            // True means round robin placement.
            SimulatorManager.configureHostsAndVMs(SimulatorProperties.getNbOfHostingNodes(), SimulatorProperties.getNbOfServiceNodes(), SimulatorProperties.getNbOfVMs(), false);
            SimulatorManager.writeCurrentConfiguration();


            Trace.hostVariableSet(SimulatorManager.getInjectorNodeName(), "NB_MIG", 0);
            Trace.hostVariableSet(SimulatorManager.getInjectorNodeName(), "NB_MC", 0);
            Trace.hostVariableSet(SimulatorManager.getInjectorNodeName(), "NB_VM", SimulatorManager.getSGVMsOn().size());
            Trace.hostVariableSet(SimulatorManager.getInjectorNodeName(), "NB_VM_TRUE", SimulatorManager.getSGVMsOn().size());

            // Start process,
            // first the injector
            Injector injector=new Injector(Host.getByName(SimulatorManager.getInjectorNodeName()),"Injector");
            injector.start();

            //Second the scheduler
            SimulatorManager.setSchedulerActive(false);
            if(SimulatorProperties.getAlgo().equals("centralized")){
                CentralizedResolver centralizedResolver=new CentralizedResolver(Host.getByName("node"+SimulatorProperties.getNbOfHostingNodes()), "CentralizedResolver");
                centralizedResolver.start();
            } else if(SimulatorProperties.getAlgo().equals("hierarchical")){
                // TODO Adrien for Anthony, Feb 10th 2017
                Msg.info("Contact Anthony Simonet");
            } else if(SimulatorProperties.getAlgo().equals("distributed")){
                // TODO Adrien for Anthony, Feb 10th 2017
                Msg.info("Contact Anthony Simonet");
            }

            // Wait for the end of the simulation.
            waitFor(SimulatorProperties.getDuration() - Msg.getClock());

            // Wait for termination of On going scheduling
            while (SimulatorManager.isSchedulerActive()) {
                //  Msg.info(String.format("Waiting for timeout (%d seconds)", 4));

                try {
                    waitFor(100);
                } catch (Exception e) {
                    System.err.println(e);
                }
            }
            SimulatorManager.writeEnergy(SimulatorProperties.getEnergyLogFile());

            Msg.info("Gonna finalize the simulation...");
            SimulatorManager.finalizeSimulation();

            Msg.info("Done");

            Msg.info("Suspended VMs: " + SimulatorManager.iSuspend);
            Msg.info("Resumed VMs: " + SimulatorManager.iResume);
            //   System.exit(0);

        }

    }
